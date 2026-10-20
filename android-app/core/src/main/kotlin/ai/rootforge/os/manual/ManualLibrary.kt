package ai.rootforge.os.manual

import ai.rootforge.os.guide.Block
import ai.rootforge.os.guide.GuideHeading
import ai.rootforge.os.guide.GuideMarkdown
import ai.rootforge.os.guide.plainText

/**
 * The how-to manual shipped inside the app. [docs/MANUAL.md] is copied to the
 * classpath so Android and desktop read the same bytes offline.
 *
 * Presentation-only markup (HTML header art, markdown images) is stripped
 * before parse so [GuideMarkdown] can render the rest. The GitHub copy keeps
 * those images.
 */
class ManualLibrary(private val read: () -> String? = ::readResource) {
    val raw: String by lazy {
        requireNotNull(read()) { "How-to manual is missing from the app" }
    }

    /** Text after HTML / image lines are removed. */
    val displayText: String by lazy { stripPresentation(raw) }

    val blocks: List<Block> by lazy { GuideMarkdown.parse(displayText) }

    val headings: List<GuideHeading> by lazy {
        blocks.filterIsInstance<Block.Heading>().map {
            GuideHeading(it.level, it.spans.plainText(), it.anchor)
        }
    }

    fun hasAnchor(anchor: String): Boolean = headings.any { it.anchor == anchor }

    companion object {
        const val RESOURCE = "/ai/rootforge/os/manual/MANUAL.md"
        const val HEADER_RESOURCE = "/ai/rootforge/os/manual/header.png"
        const val GITHUB_URL = "https://github.com/Victorious93/rootforge-os/blob/main/docs/MANUAL.md"
        const val TITLE = "How-to manual"

        private val HTML_LINE = Regex("^\\s*</?[A-Za-z][^>]*>\\s*$")
        private val IMAGE_LINE = Regex("^\\s*!\\[[^\\]]*\\]\\([^)]+\\)\\s*$")

        fun stripPresentation(text: String): String {
            val kept = ArrayList<String>()
            for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    kept += ""
                    continue
                }
                if (HTML_LINE.matches(trimmed)) continue
                if (IMAGE_LINE.matches(trimmed)) continue
                if (trimmed.startsWith("<!--") && trimmed.endsWith("-->")) continue
                kept += line
            }
            return kept.joinToString("\n").trim() + "\n"
        }

        private fun readResource(): String? =
            ManualLibrary::class.java.getResourceAsStream(RESOURCE)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }

        val default: ManualLibrary by lazy { ManualLibrary() }
    }
}
