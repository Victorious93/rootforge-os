package ai.rootforge.os.manual

import ai.rootforge.os.guide.Block
import ai.rootforge.os.guide.GuideLink
import ai.rootforge.os.guide.plainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against the bundled how-to so a missing or empty copy fails the build. */
class ManualLibraryTest {
    private val library = ManualLibrary()

    @Test
    fun bundledManualParsesAndHasTheRequiredSections() {
        assertTrue("manual is nearly empty", library.blocks.size > 20)
        val titles = library.headings.filter { it.level == 2 }.map { it.title.lowercase() }
        for (needed in REQUIRED_SECTIONS) {
            assertTrue("missing section: $needed\n$titles", titles.any { needed in it })
        }
        val text = library.blocks.joinToString("\n") { it.plainText() }
        assertTrue("fleet role missing", "EXTERNAL_NODE" in text)
        assertTrue("protocol major 1 missing", "protocol_major" in text || "protocol major 1" in text.lowercase())
        assertTrue("pairing rule missing", "package" in text.lowercase() && "certificate" in text.lowercase())
        assertTrue("v0.2.0 uninstall missing", "v0.2.0" in text && "uninstall" in text.lowercase())
        assertTrue("remote-control default missing", "off by default" in text.lowercase())
        assertTrue("INTERNET reason missing", "INTERNET" in text)
        assertTrue("Shizuku missing", "Shizuku" in text)
        assertTrue("Desktop mode pin missing", "Pin this VictorSuite" in text)
        assertTrue("Desktop mode RUN_COMMAND missing", "RUN_COMMAND" in text && "VictorSuite" in text)
        assertTrue("Custom Recovery installer missing", "FLASH RECOVERY" in text && "PitchBlack" in text && "TWRP" in text)
        assertTrue("Custom Recovery spec missing", "CUSTOM_RECOVERY_INSTALLER" in text)
        assertTrue("Custom RR install action missing", "Install with Custom Recovery" in text)
        assertTrue("Custom RR PitchBlack host missing", "pitchblack.tech" in text)
        assertTrue("typed FLASH missing", "FLASH" in text)
        assertTrue("privacy: no analytics missing", "analytics" in text.lowercase())
        assertTrue("node.status.get missing", "node.status.get" in text)
        assertTrue("STATUS-TRAY snapshot missing", "NodeStatusSnapshot" in text)
        assertTrue("glossary missing Root", "**Root.**" in library.raw)
        assertTrue("glossary missing Bootloop", "**Bootloop.**" in library.raw)
        assertTrue("Devices screen missing", "### Devices" in library.raw)
        assertTrue("Root plan missing", "Root plan" in library.raw)
        assertTrue("config backup ops missing", "config.export" in text && "config.import" in text)
        assertTrue("config backup remote refuse missing", "not remotely allowed" in text.lowercase())
    }

    @Test
    fun githubLinkIsHttpsAndPresentationMarkupIsStripped() {
        assertTrue(ManualLibrary.GITHUB_URL.startsWith("https://github.com/"))
        assertTrue(library.raw.contains("<img"))
        assertTrue(library.raw.contains("!["))
        assertFalse(library.displayText.contains("<img"))
        assertFalse(library.displayText.contains("!["))
        val htmlParagraphs = library.blocks.filterIsInstance<Block.Paragraph>().count {
            it.spans.plainText().contains("<img") || it.spans.plainText().startsWith("![")
        }
        assertEquals(0, htmlParagraphs)
    }

    @Test
    fun internalAnchorsAndHttpsLinksResolve() {
        val anchors = library.headings.map { it.anchor }.toSet()
        val bad = ArrayList<String>()
        for (block in library.blocks) {
            for (span in spansOf(block)) {
                when (val link = span.link?.let(GuideLink::parse)) {
                    is GuideLink.Anchor -> if (link.anchor !in anchors) bad += "#${link.anchor}"
                    is GuideLink.External -> if (!link.url.startsWith("https://")) bad += link.url
                    is GuideLink.Page -> bad += "guide page ${link.id} (manual is a single document)"
                    null -> if (span.link != null) bad += "disallowed ${span.link}"
                }
            }
        }
        assertTrue("broken manual links:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun headerArtIsBundled() {
        val bytes = ManualLibrary::class.java.getResourceAsStream(ManualLibrary.HEADER_RESOURCE)?.readBytes()
        assertTrue("header.png missing from classpath", bytes != null && bytes.size > 1024)
        assertTrue("header.png is not a PNG", bytes!!.copyOfRange(0, 8).contentEquals(PNG_SIG))
    }

    private fun spansOf(block: Block): List<ai.rootforge.os.guide.Span> = when (block) {
        is Block.Paragraph -> block.spans
        is Block.Heading -> block.spans
        is Block.Table -> (listOf(block.header) + block.rows).flatten().flatten()
        is Block.ListBlock -> block.items.flatten().flatMap { spansOf(it) }
        is Block.Callout -> block.blocks.flatMap { spansOf(it) }
        is Block.Quote -> block.blocks.flatMap { spansOf(it) }
        else -> emptyList()
    }

    private companion object {
        val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val REQUIRED_SECTIONS = listOf(
            "what rootforge os is",
            "install and first-run",
            "every feature",
            "every permission",
            "fleet integration",
            "troubleshooting",
            "privacy",
            "glossary",
        )
    }
}
