package ai.rootforge.os.guide

/** A run of text with one style. Adjacent runs with equal style are merged by the parser. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    /** Raw link target as written in the guide: `guide:<id>[#anchor]`, `#anchor`, or an https URL. */
    val link: String? = null,
)

enum class CalloutKind(val label: String) {
    TIP("Tip"),
    INFO("Info"),
    NOTE("Note"),
    WARNING("Warning"),
    DANGER("Danger"),
    DETAILS("Details"),
}

/** Block-level markdown, already reduced to what the guide pages use. */
sealed interface Block {
    data class Heading(val level: Int, val spans: List<Span>, val anchor: String) : Block
    data class Paragraph(val spans: List<Span>) : Block
    data class CodeBlock(val language: String, val code: String) : Block
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<Block>>) : Block
    data class Quote(val blocks: List<Block>) : Block
    data class Callout(val kind: CalloutKind, val title: String, val blocks: List<Block>) : Block
    data class Table(val header: List<List<Span>>, val rows: List<List<List<Span>>>) : Block
    data object Rule : Block
}

fun List<Span>.plainText(): String = joinToString("") { it.text }

/** Text of a block with no styling, used for search. Code is included: people search for commands. */
fun Block.plainText(): String = when (this) {
    is Block.Heading -> spans.plainText()
    is Block.Paragraph -> spans.plainText()
    is Block.CodeBlock -> code
    is Block.ListBlock -> items.joinToString("\n") { item -> item.joinToString("\n") { it.plainText() } }
    is Block.Quote -> blocks.joinToString("\n") { it.plainText() }
    is Block.Callout -> title + "\n" + blocks.joinToString("\n") { it.plainText() }
    is Block.Table -> (listOf(header) + rows).joinToString("\n") { row -> row.joinToString(" ") { it.plainText() } }
    Block.Rule -> ""
}

/** Where a link in a guide page points. Only https is followed, matching `AppActions.openUrl`. */
sealed interface GuideLink {
    data class Page(val id: String, val anchor: String?) : GuideLink
    data class Anchor(val anchor: String) : GuideLink
    data class External(val url: String) : GuideLink

    companion object {
        /** Null for targets the app must not follow (javascript:, file:, intent:, empty). */
        fun parse(target: String): GuideLink? {
            val t = target.trim()
            return when {
                t.startsWith("guide:") -> {
                    val rest = t.removePrefix("guide:")
                    val id = rest.substringBefore('#')
                    if (id.isEmpty()) null else Page(id, rest.substringAfter('#', "").ifEmpty { null })
                }
                t.startsWith("#") -> if (t.length > 1) Anchor(t.substring(1)) else null
                t.startsWith("https://") -> External(t)
                else -> null
            }
        }
    }
}
