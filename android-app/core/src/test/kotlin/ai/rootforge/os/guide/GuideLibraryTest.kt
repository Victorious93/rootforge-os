package ai.rootforge.os.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against the pages actually shipped in the app, so a bad import fails the build. */
class GuideLibraryTest {
    private val library = GuideLibrary()

    private fun walk(blocks: List<Block>, visit: (Block) -> Unit) {
        for (b in blocks) {
            visit(b)
            when (b) {
                is Block.Callout -> walk(b.blocks, visit)
                is Block.Quote -> walk(b.blocks, visit)
                is Block.ListBlock -> b.items.forEach { walk(it, visit) }
                else -> Unit
            }
        }
    }

    private fun spansOf(b: Block): List<Span> = when (b) {
        is Block.Paragraph -> b.spans
        is Block.Heading -> b.spans
        is Block.Table -> (listOf(b.header) + b.rows).flatten().flatten()
        else -> emptyList()
    }

    @Test
    fun manifestListsUniqueEntriesAndAttributesTheSource() {
        val m = library.manifest
        assertEquals("MIT", m.source.license)
        assertTrue(m.source.repository.startsWith("https://github.com/"))
        assertTrue("commit pin missing", Regex("[0-9a-f]{40}").matches(m.source.commit))
        val ids = m.entries.map { it.id }
        assertEquals("duplicate guide ids", ids.size, ids.toSet().size)
        assertTrue(m.entries.count { it.origin == "rootforge" } >= 1)
        assertTrue(m.entries.size >= 25)
    }

    @Test
    fun everyEntryParsesToHeadingsAndContent() {
        for (e in library.manifest.entries) {
            val doc = assertNotNull_("page ${e.file} missing", library.document(e.id))
            assertTrue("${e.id} has no headings", doc.headings.isNotEmpty())
            assertEquals("${e.id}: first heading should be the title", e.title, doc.headings.first().title)
            assertTrue("${e.id} is nearly empty", doc.blocks.size > 5)
        }
    }

    @Test
    fun noRawHtmlOrBadgesSurviveTheImport() {
        for (e in library.manifest.entries) {
            val raw = GuideLibrary::class.java.getResourceAsStream("/ai/rootforge/os/guide/${e.file}")!!.readBytes().toString(Charsets.UTF_8)
            var fenced = false
            for ((n, line) in raw.lines().withIndex()) {
                if (line.trimStart().startsWith("```")) { fenced = !fenced; continue }
                if (fenced) continue
                assertFalse("${e.file}:${n + 1} still has an image: $line", Regex("!\\[").containsMatchIn(line))
                assertFalse("${e.file}:${n + 1} still has <details>/<summary>: $line", Regex("</?(details|summary|div|br)\\b").containsMatchIn(line))
            }
        }
    }

    @Test
    fun everyInternalLinkResolvesToAPageAndHeading() {
        val bad = ArrayList<String>()
        for (e in library.manifest.entries) {
            val doc = library.document(e.id)!!
            walk(doc.blocks) { b ->
                for (s in spansOf(b)) {
                    when (val l = s.link?.let(GuideLink::parse)) {
                        is GuideLink.Page -> {
                            val target = library.document(l.id)
                            if (target == null) bad += "${e.id}: unknown page ${l.id}"
                            else if (l.anchor != null && !target.hasAnchor(l.anchor)) bad += "${e.id}: ${l.id}#${l.anchor}"
                        }
                        is GuideLink.Anchor -> if (!doc.hasAnchor(l.anchor)) bad += "${e.id}: #${l.anchor}"
                        is GuideLink.External, null -> if (s.link != null && l == null) bad += "${e.id}: disallowed link ${s.link}"
                    }
                }
            }
        }
        // Anchors written in the upstream pages that point at no heading are upstream defects; they only
        // lose their scroll target. Pages and disallowed schemes must always resolve.
        val hard = bad.filter { "#" !in it }
        assertTrue("broken links:\n" + hard.joinToString("\n"), hard.isEmpty())
        val brokenAnchors = bad.size - hard.size
        assertTrue("too many dangling anchors ($brokenAnchors):\n" + bad.joinToString("\n"), brokenAnchors <= MAX_DANGLING_ANCHORS)
    }

    @Test
    fun rootForgePageOnlyNamesRealScreens() {
        val doc = library.document("rootforge-workflow")!!
        val text = doc.blocks.joinToString("\n") { it.plainText() }
        for (screen in listOf("Dashboard", "ADB", "Fastboot", "Root / Patching", "Recovery", "Module Builder", "EDL")) {
            assertTrue("workflow page does not mention $screen", screen in text)
        }
        assertTrue("UNLOCK" in text)
    }

    @Test
    fun searchRanksHeadingsFirstAndReturnsSnippets() {
        val hits = library.search("bootloader unlock")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.size <= 30)
        assertTrue(hits.first().snippet.isNotBlank())
        assertTrue(library.search("x").isEmpty())
        assertTrue(library.search("zzzz-not-in-any-guide-qqq").isEmpty())
        val fb = library.search("fastboot flash")
        assertTrue(fb.any { it.anchor != null })
    }

    @Test
    fun unknownPageIsNullNotAnException() {
        assertEquals(null, library.document("nope"))
        assertEquals(null, library.entry("nope"))
    }

    private fun <T : Any> assertNotNull_(message: String, v: T?): T {
        assertNotNull(message, v)
        return v!!
    }

    private companion object {
        /** Measured on the first import; lower it if the upstream pages are fixed. */
        const val MAX_DANGLING_ANCHORS = 0
    }
}
