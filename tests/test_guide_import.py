#!/usr/bin/env python3
"""Hermetic tests for android-app/tools/import_root_guide.py (Victorious Framework Root Guide importer)."""
from __future__ import annotations

import importlib.util
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("import_root_guide", ROOT / "android-app/tools/import_root_guide.py")
imp = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(imp)

GUIDE_DIR = ROOT / "android-app/core/src/main/resources/ai/rootforge/os/guide"


def convert(text: str, src: str = "rooting-guides/magisk-guide.md") -> str:
    return imp.convert(text, src, imp.build_route_map())


class ConvertTests(unittest.TestCase):
    def test_frontmatter_removed_and_title_kept(self):
        out = convert("---\ntitle: SEO title\nhead:\n  - x\n---\n\n# Real Title\n\nBody\n")
        self.assertTrue(out.startswith("# Real Title"))
        self.assertNotIn("SEO", out)

    def test_details_with_inline_summary_and_trailing_br(self):
        out = convert("<details><summary>More <b>info</b></summary><br>\n\ntext\n\n</details>\n")
        self.assertIn("::: details More info", out)
        self.assertEqual(out.count(":::"), 2)
        self.assertNotIn("<", out)

    def test_details_with_summary_on_next_line(self):
        out = convert("<details>\n<summary>Open me</summary>\n\nbody\n\n</details>\n")
        self.assertIn("::: details Open me", out)

    def test_containers_normalised(self):
        out = convert(":::danger\nx\n:::\n")
        self.assertTrue(out.startswith("::: danger"))

    def test_images_and_badges_removed(self):
        out = convert("[![Badge](https://img.shields.io/x)](https://x.example) text ![i](a.png)\n")
        self.assertNotIn("![", out)
        self.assertNotIn("shields", out)
        self.assertIn("text", out)

    def test_fenced_code_is_untouched(self):
        body = "```bash\n<div>x</div>\n[a](./magisk-guide.md)\n![i](x.png)\n```\n"
        self.assertIn(body.strip(), convert(body))

    def test_internal_links_resolve_to_guide_ids_and_anchors(self):
        out = convert("[a](./kernelsu-guide.md#install) [b](../faqs.md) [c](/rooting-guides/apatch-guide)")
        self.assertIn("](guide:kernelsu#install)", out)
        self.assertIn("](guide:faq)", out)
        self.assertIn("](guide:apatch)", out)

    def test_links_to_unimported_pages_become_website_urls(self):
        out = convert("[apps](../apps-and-modules/) [rm](../apps-and-modules/root-management#hiding)")
        self.assertIn(f"]({imp.SITE}/apps-and-modules)", out)
        self.assertIn(f"]({imp.SITE}/apps-and-modules/root-management#hiding)", out)

    def test_external_and_same_page_links_kept(self):
        out = convert("[x](https://example.org/a) [y](#section)")
        self.assertIn("](https://example.org/a)", out)
        self.assertIn("](#section)", out)

    def test_slug_matches_vitepress_rules(self):
        self.assertEqual(imp.slug("Method 0: Pixel Flasher (GUI Tool)"), "method-0-pixel-flasher-gui-tool")


class CommittedGuideTests(unittest.TestCase):
    """The committed output, not a regeneration: guards hand edits and stale files."""

    def setUp(self):
        self.manifest = json.loads((GUIDE_DIR / "manifest.json").read_text(encoding="utf-8"))

    def test_manifest_matches_importer_table(self):
        table = [(gid, src) for _, items in imp.SECTIONS for gid, src, _ in items]
        listed = [(e["id"], e["sourcePath"] or None) for s in self.manifest["sections"] for e in s["entries"]]
        self.assertEqual(table, listed)

    def test_every_page_exists_and_no_extra_pages(self):
        files = {e["file"] for s in self.manifest["sections"] for e in s["entries"]}
        self.assertEqual({p.name for p in GUIDE_DIR.glob("*.md")}, files)

    def test_source_is_pinned_and_licensed(self):
        src = self.manifest["source"]
        self.assertEqual(src["license"], "MIT")
        self.assertRegex(src["commit"], r"^[0-9a-f]{40}$")

    def test_notice_credits_the_upstream_project(self):
        notice = (ROOT / "NOTICE").read_text(encoding="utf-8")
        self.assertIn("Awesome Android Root", notice)
        self.assertIn("MIT", notice)


if __name__ == "__main__":
    unittest.main()
