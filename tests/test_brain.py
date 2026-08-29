"""Unit tests for the second-brain CLI's pure logic.

Only the parts that don't need Ollama: chunking, similarity, slugs. Each
test here corresponds to a bug that produced plausible-looking but wrong
output rather than an error.
"""
import importlib.util
import pathlib
import unittest

BRAIN_PY = (
    pathlib.Path(__file__).resolve().parents[1]
    / "config/includes.chroot/usr/local/lib/rootforge/second-brain/brain.py"
)

spec = importlib.util.spec_from_file_location("brain", BRAIN_PY)
brain = importlib.util.module_from_spec(spec)
spec.loader.exec_module(brain)


class TestChunking(unittest.TestCase):
    def test_empty_text_yields_no_chunks(self):
        self.assertEqual(brain.chunk_text(""), [])
        self.assertEqual(brain.chunk_text("   \n\n  "), [])

    def test_short_note_is_one_chunk(self):
        chunks = brain.chunk_text("# Title\n\nfirst para\n\nsecond para\n")
        self.assertEqual(len(chunks), 1)
        self.assertIn("first para", chunks[0])
        self.assertIn("second para", chunks[0])

    def test_oversized_single_paragraph_is_split(self):
        """A paragraph longer than CHUNK_CHARS used to pass through whole.

        Paragraph-level packing only ever started a *new* chunk; it never
        split one. A pasted log or minified line therefore became a single
        oversized chunk that the embedding model silently truncates, making
        the tail of the note unsearchable with no error anywhere.
        """
        blob = "x" * (brain.CHUNK_CHARS * 3)
        chunks = brain.chunk_text(blob)
        self.assertGreater(len(chunks), 1)
        for chunk in chunks:
            self.assertLessEqual(len(chunk), brain.CHUNK_CHARS)

    def test_oversized_paragraph_preserves_content(self):
        blob = "".join(f"line {i}\n" for i in range(2000))
        chunks = brain.chunk_text(blob)
        rejoined = "".join(chunks).replace("\n", "").replace(" ", "")
        self.assertEqual(rejoined, blob.replace("\n", "").replace(" ", ""))

    def test_long_prose_splits_on_sentence_boundaries(self):
        prose = "This is a sentence. " * 400
        chunks = brain.chunk_text(prose)
        for chunk in chunks:
            self.assertLessEqual(len(chunk), brain.CHUNK_CHARS)
        # Most pieces should end at a sentence boundary, not mid-word.
        boundary_ends = sum(1 for c in chunks if c.rstrip().endswith("."))
        self.assertGreaterEqual(boundary_ends, len(chunks) - 1)

    def test_every_chunk_respects_the_limit(self):
        mixed = "short para\n\n" + ("y" * 4000) + "\n\nanother short para\n"
        for chunk in brain.chunk_text(mixed):
            self.assertLessEqual(len(chunk), brain.CHUNK_CHARS)


class TestCosine(unittest.TestCase):
    def test_identical_vectors_score_one(self):
        self.assertAlmostEqual(brain.cosine([1.0, 2.0, 3.0], [1.0, 2.0, 3.0]), 1.0)

    def test_orthogonal_vectors_score_zero(self):
        self.assertAlmostEqual(brain.cosine([1.0, 0.0], [0.0, 1.0]), 0.0)

    def test_zero_vector_does_not_divide_by_zero(self):
        self.assertEqual(brain.cosine([0.0, 0.0], [1.0, 1.0]), 0.0)

    def test_dimension_mismatch_raises(self):
        """zip() used to truncate to the shorter vector.

        Changing BRAIN_EMBED_MODEL without re-indexing leaves chunks of a
        different dimension in the database. Scoring a prefix of them gives
        confident, meaningless rankings — so this must be detectable, not
        silently absorbed.
        """
        with self.assertRaises(ValueError):
            brain.cosine([1.0, 2.0, 3.0], [1.0, 2.0])


class TestSlugify(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(brain.slugify("Hello World"), "hello-world")

    def test_punctuation_collapses(self):
        self.assertEqual(brain.slugify("A -- B!!! C"), "a-b-c")

    def test_non_alnum_title_still_produces_a_filename(self):
        self.assertEqual(brain.slugify("???"), "untitled")

    def test_leading_and_trailing_separators_stripped(self):
        self.assertEqual(brain.slugify("  -- notes --  "), "notes")


if __name__ == "__main__":
    unittest.main()
