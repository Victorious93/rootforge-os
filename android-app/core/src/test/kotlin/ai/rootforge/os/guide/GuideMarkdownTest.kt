package ai.rootforge.os.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideMarkdownTest {
    private fun parse(s: String) = GuideMarkdown.parse(s.trimIndent())

    @Test
    fun headingsGetVitePressAnchorsAndDuplicatesAreNumbered() {
        val blocks = parse(
            """
            # Method 0: Pixel Flasher (GUI Tool)
            ## Setup
            ## Setup
            ## 2024 notes
            """,
        )
        val anchors = blocks.filterIsInstance<Block.Heading>().map { it.anchor }
        assertEquals(listOf("method-0-pixel-flasher-gui-tool", "setup", "setup-1", "_2024-notes"), anchors)
    }

    @Test
    fun fencedCodeIsVerbatimAndIgnoresMarkdown() {
        val blocks = parse(
            """
            ```bash
            # not a heading
            fastboot flash boot **x**
            ```
            after
            """,
        )
        val code = blocks[0] as Block.CodeBlock
        assertEquals("bash", code.language)
        assertEquals("# not a heading\nfastboot flash boot **x**", code.code)
        assertEquals("after", (blocks[1] as Block.Paragraph).spans.plainText())
    }

    @Test
    fun nestedListsKeepStructureAndNumbering() {
        val blocks = parse(
            """
            3. Patch the image
               - Open Magisk
               - Tap **Install**
            4. Flash
            """,
        )
        val list = blocks.single() as Block.ListBlock
        assertTrue(list.ordered)
        assertEquals(3, list.start)
        assertEquals(2, list.items.size)
        val nested = list.items[0][1] as Block.ListBlock
        assertEquals(2, nested.items.size)
        val second = (nested.items[1][0] as Block.Paragraph).spans
        assertEquals(listOf("Tap ", "Install"), second.map { it.text })
        assertTrue(second[1].bold)
    }

    @Test
    fun listItemKeepsFencedCodeAndLazyContinuation() {
        val blocks = parse(
            """
            1. Run:
               ```bash
               adb devices
               ```
            2. Wrapped
            line here
            """,
        )
        val list = blocks.single() as Block.ListBlock
        assertEquals("adb devices", (list.items[0][1] as Block.CodeBlock).code)
        assertEquals("Wrapped line here", (list.items[1][0] as Block.Paragraph).spans.plainText())
    }

    @Test
    fun bulletMayInterruptParagraphButYearMayNot() {
        val blocks = parse(
            """
            Steps:
            - one
            - two
            """,
        )
        assertEquals(2, blocks.size)
        val wrapped = parse(
            """
            released in
            2024. It works
            """,
        )
        assertEquals(1, wrapped.size)
    }

    @Test
    fun calloutsNestAndEndAtTheirOwnClose() {
        val blocks = parse(
            """
            ::: details More
            text
            ::: danger Careful
            wipes data
            :::
            ```
            :::
            ```
            :::
            tail
            """,
        )
        val outer = blocks[0] as Block.Callout
        assertEquals(CalloutKind.DETAILS, outer.kind)
        assertEquals("More", outer.title)
        val inner = outer.blocks[1] as Block.Callout
        assertEquals(CalloutKind.DANGER, inner.kind)
        assertEquals("Careful", inner.title)
        assertTrue(outer.blocks[2] is Block.CodeBlock)
        assertEquals("tail", (blocks[1] as Block.Paragraph).spans.plainText())
    }

    @Test
    fun tablesNormaliseRowWidthAndUnescapePipes() {
        val blocks = parse(
            """
            | Device | Image |
            | :--- | ---: |
            | Pixel 7 | `init_boot` |
            | Short |
            | a \| b | c | extra |
            """,
        )
        val table = blocks.single() as Block.Table
        assertEquals(2, table.header.size)
        assertEquals(3, table.rows.size)
        assertTrue(table.rows[0][1][0].code)
        assertEquals("", table.rows[1][1].plainText())
        assertEquals("a | b", table.rows[2][0].plainText())
        assertEquals(2, table.rows[2].size)
    }

    @Test
    fun inlineStylesLinksAndBareUrls() {
        val spans = GuideMarkdown.parseInline(
            "Use `fastboot` **now** or *later*, see [the guide](guide:magisk#install) and https://example.org/x.",
        )
        assertEquals("fastboot", spans.first { it.code }.text)
        assertTrue(spans.first { it.text == "now" }.bold)
        assertTrue(spans.first { it.text == "later" }.italic)
        assertEquals("guide:magisk#install", spans.first { it.text == "the guide" }.link)
        val url = spans.first { it.link == "https://example.org/x" }
        assertEquals("https://example.org/x", url.text)
        assertEquals(".", spans.last().text)
    }

    @Test
    fun snakeCaseAndLoneAsterisksAreLiteral() {
        assertEquals("magisk_patched_boot.img", GuideMarkdown.parseInline("magisk_patched_boot.img").plainText())
        assertEquals("5 * 3 * 2", GuideMarkdown.parseInline("5 * 3 * 2").plainText())
        val bi = GuideMarkdown.parseInline("***both***").single()
        assertTrue(bi.bold && bi.italic)
        assertEquals("both", bi.text)
    }

    @Test
    fun linksWithParenthesesAndNestedCodeLabels() {
        val spans = GuideMarkdown.parseInline("[`adb` (docs)](https://example.org/a_(b)) done")
        val link = spans.first { it.link != null }
        assertEquals("https://example.org/a_(b)", link.link)
        assertTrue(spans.any { it.code && it.text == "adb" && it.link != null })
        assertEquals(" done", spans.last().text)
    }

    @Test
    fun linkTargetsAreAllowListed() {
        assertEquals(GuideLink.Page("magisk", "install"), GuideLink.parse("guide:magisk#install"))
        assertEquals(GuideLink.Page("faq", null), GuideLink.parse("guide:faq"))
        assertEquals(GuideLink.Anchor("top"), GuideLink.parse("#top"))
        assertEquals(GuideLink.External("https://example.org"), GuideLink.parse("https://example.org"))
        assertNull(GuideLink.parse("javascript:alert(1)"))
        assertNull(GuideLink.parse("intent://scan#Intent;end"))
        assertNull(GuideLink.parse("file:///sdcard/x"))
        assertNull(GuideLink.parse("http://example.org"))
        assertNull(GuideLink.parse("mailto:a@example.org"))
        assertNull(GuideLink.parse("guide:"))
        assertNull(GuideLink.parse(""))
    }

    @Test
    fun quotesAndRules() {
        val blocks = parse(
            """
            > quoted
            > - item

            ---
            """,
        )
        val quote = blocks[0] as Block.Quote
        assertTrue(quote.blocks[0] is Block.Paragraph || quote.blocks[0] is Block.ListBlock)
        assertEquals(Block.Rule, blocks[1])
    }
}
