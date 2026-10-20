package ai.rootforge.os.guide

/**
 * Parser for the markdown subset the Root Guide pages use: headings, paragraphs, fenced code,
 * nested lists, GFM tables, block quotes, `::: kind title` containers (tip, warning, danger,
 * info, details), emphasis, inline code and links. Pure Kotlin so the same pages render on the
 * Android and desktop clients and can be unit-tested without a UI.
 *
 * Not a CommonMark implementation: reference links, setext headings, indented code and raw
 * HTML are not supported. `tools/import_root_guide.py` removes them from imported pages.
 */
object GuideMarkdown {
    fun parse(text: String): List<Block> =
        Parser().blocks(text.replace("\r\n", "\n").replace('\r', '\n').split('\n'))

    fun parseInline(text: String): List<Span> = Inline.parse(text)

    /** Anchor id for a heading, following VitePress' slugify so authored `#anchor` links resolve. */
    fun slug(heading: String): String {
        val sb = StringBuilder()
        var pendingDash = false
        for (ch in heading) {
            if (ch.isWhitespace() || ch in SLUG_SPECIAL) {
                pendingDash = true
            } else {
                if (pendingDash) sb.append('-')
                pendingDash = false
                sb.append(ch)
            }
        }
        // VitePress collapses runs of '-' and trims them; the loop above already emits at most one.
        var s = sb.toString().trim('-').lowercase()
        if (s.isNotEmpty() && s[0].isDigit()) s = "_$s"
        return s
    }

    private const val SLUG_SPECIAL = "~`!@#$%^&*()-_+=[]{}|\\;:\"'“”‘’<>,.?/"

    private val FENCE = Regex("^\\s*(`{3,}|~{3,})\\s*([^`\\s]*)")
    private val HEADING = Regex("^ {0,3}(#{1,6})\\s+(.+?)\\s*$")
    private val HEADING_CLOSE = Regex("\\s+#+$")
    private val RULE = Regex("^ {0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val LIST = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])(?:\\s+(.*))?$")
    private val CONTAINER_OPEN = Regex("^\\s*:::\\s*([A-Za-z]+)\\s*(.*)$")
    private val CONTAINER_CLOSE = Regex("^\\s*:::\\s*$")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    private fun indentOf(line: String): Int {
        var n = 0
        for (ch in line) {
            when (ch) {
                ' ' -> n++
                '\t' -> n += 4
                else -> return n
            }
        }
        return n
    }

    private fun dedent(line: String, by: Int): String {
        var removed = 0
        var i = 0
        while (i < line.length && removed < by) {
            when (line[i]) {
                ' ' -> removed++
                '\t' -> removed += 4
                else -> break
            }
            i++
        }
        return line.substring(i)
    }

    private class Parser {
        /** Slug -> times seen, shared across nested containers so duplicate headings get -1, -2 like VitePress. */
        private val seen = HashMap<String, Int>()

        fun blocks(lines: List<String>): List<Block> {
            val out = ArrayList<Block>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val fence = FENCE.find(line)
                val heading = HEADING.matchEntire(line)
                val container = CONTAINER_OPEN.matchEntire(line)
                i = when {
                    line.isBlank() -> i + 1
                    fence != null -> fenced(lines, i, fence, out)
                    heading != null -> {
                        val spans = Inline.parse(heading.groupValues[2].replace(HEADING_CLOSE, ""))
                        out += Block.Heading(heading.groupValues[1].length, spans, uniqueAnchor(spans.plainText()))
                        i + 1
                    }
                    RULE.matches(line) -> { out += Block.Rule; i + 1 }
                    container != null -> container(lines, i, container, out)
                    line.trimStart().startsWith(">") -> quote(lines, i, out)
                    isTableStart(lines, i) -> table(lines, i, out)
                    LIST.matches(line) -> list(lines, i, out)
                    else -> paragraph(lines, i, out)
                }
            }
            return out
        }

        private fun uniqueAnchor(text: String): String {
            val base = GuideMarkdown.slug(text)
            val n = seen[base] ?: 0
            seen[base] = n + 1
            return if (n == 0) base else "$base-$n"
        }

        private fun fenced(lines: List<String>, start: Int, m: MatchResult, out: MutableList<Block>): Int {
            val marker = m.groupValues[1]
            val indent = indentOf(lines[start])
            val body = ArrayList<String>()
            var i = start + 1
            while (i < lines.size) {
                val l = lines[i]
                val t = l.trim()
                if (t.length >= marker.length && t[0] == marker[0] && t.all { it == marker[0] }) { i++; break }
                body += dedent(l, indent)
                i++
            }
            out += Block.CodeBlock(m.groupValues[2], body.joinToString("\n"))
            return i
        }

        private fun container(lines: List<String>, start: Int, m: MatchResult, out: MutableList<Block>): Int {
            var depth = 1
            var fence: String? = null
            var i = start + 1
            while (i < lines.size) {
                val l = lines[i]
                val f = FENCE.find(l)
                if (fence == null) {
                    when {
                        f != null -> fence = f.groupValues[1]
                        CONTAINER_CLOSE.matches(l) -> { depth--; if (depth == 0) break }
                        CONTAINER_OPEN.matches(l) -> depth++
                    }
                } else if (f != null && f.groupValues[1][0] == fence[0] && f.groupValues[1].length >= fence.length &&
                    l.trim().all { it == fence!![0] }
                ) {
                    fence = null
                }
                i++
            }
            val kind = when (m.groupValues[1].lowercase()) {
                "tip" -> CalloutKind.TIP
                "info" -> CalloutKind.INFO
                "warning" -> CalloutKind.WARNING
                "danger" -> CalloutKind.DANGER
                "details" -> CalloutKind.DETAILS
                else -> CalloutKind.NOTE
            }
            val inner = blocks(lines.subList(start + 1, minOf(i, lines.size)))
            out += Block.Callout(kind, m.groupValues[2].trim(), inner)
            return minOf(i + 1, lines.size)
        }

        private fun quote(lines: List<String>, start: Int, out: MutableList<Block>): Int {
            val body = ArrayList<String>()
            var i = start
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                body += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                i++
            }
            out += Block.Quote(blocks(body))
            return i
        }

        private fun isTableStart(lines: List<String>, i: Int): Boolean =
            lines[i].contains('|') && i + 1 < lines.size && lines[i + 1].contains('-') &&
                TABLE_SEPARATOR.matches(lines[i + 1]) && lines[i + 1].contains('|')

        private fun cells(line: String): List<String> {
            val t = line.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
            val parts = ArrayList<String>()
            val cur = StringBuilder()
            var i = 0
            while (i < t.length) {
                val ch = t[i]
                if (ch == '\\' && i + 1 < t.length && t[i + 1] == '|') { cur.append('|'); i += 2; continue }
                if (ch == '|') { parts += cur.toString().trim(); cur.setLength(0) } else cur.append(ch)
                i++
            }
            parts += cur.toString().trim()
            return parts
        }

        private fun table(lines: List<String>, start: Int, out: MutableList<Block>): Int {
            val header = cells(lines[start])
            val width = header.size
            val rows = ArrayList<List<List<Span>>>()
            var i = start + 2
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                val row = cells(lines[i])
                rows += List(width) { c -> Inline.parse(row.getOrElse(c) { "" }) }
                i++
            }
            out += Block.Table(header.map(Inline::parse), rows)
            return i
        }

        private fun startsBlock(line: String): Boolean =
            FENCE.containsMatchIn(line) || HEADING.matches(line) || RULE.matches(line) ||
                CONTAINER_OPEN.matches(line) || CONTAINER_CLOSE.matches(line) || line.trimStart().startsWith(">") ||
                LIST.matchEntire(line)?.let { m ->
                    // Only a bullet or "1." may interrupt a paragraph; "2024." at a wrap point must not.
                    val marker = m.groupValues[2]
                    marker[0] in "-*+" || marker.startsWith("1.") || marker.startsWith("1)")
                } == true

        private fun paragraph(lines: List<String>, start: Int, out: MutableList<Block>): Int {
            val sb = StringBuilder()
            var i = start
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) break
                if (i > start && (startsBlock(l) || isTableStart(lines, i))) break
                if (sb.isNotEmpty()) sb.append(if (lines[i - 1].endsWith("  ") || lines[i - 1].endsWith("\\")) '\n' else ' ')
                sb.append(l.trim().removeSuffix("\\"))
                i++
            }
            out += Block.Paragraph(Inline.parse(sb.toString()))
            return i
        }

        private fun list(lines: List<String>, start: Int, out: MutableList<Block>): Int {
            val first = LIST.matchEntire(lines[start])!!
            val baseIndent = indentOf(lines[start])
            val ordered = first.groupValues[2][0].isDigit()
            val startNumber = if (ordered) first.groupValues[2].dropLast(1).toInt() else 1
            val items = ArrayList<List<Block>>()
            var i = start
            while (i < lines.size) {
                val m = LIST.matchEntire(lines[i]) ?: break
                if (indentOf(lines[i]) != baseIndent || m.groupValues[2][0].isDigit() != ordered) break
                val marker = m.groupValues[2]
                val contentCol = baseIndent + marker.length + 1
                val body = ArrayList<String>()
                body += m.groupValues[3]
                var j = i + 1
                while (j < lines.size) {
                    val l = lines[j]
                    if (l.isBlank()) {
                        var k = j + 1
                        while (k < lines.size && lines[k].isBlank()) k++
                        if (k < lines.size && indentOf(lines[k]) > baseIndent) {
                            repeat(k - j) { body += "" }
                            j = k
                            continue
                        }
                        break
                    }
                    val indent = indentOf(l)
                    val lazy = indent <= baseIndent && !startsBlock(l) && lines[j - 1].isNotBlank() &&
                        !LIST.matches(l) && !isTableStart(lines, j)
                    if (indent > baseIndent || lazy) {
                        body += if (indent > baseIndent) dedent(l, minOf(indent, contentCol)) else l.trim()
                        j++
                    } else {
                        break
                    }
                }
                items += blocks(body)
                i = j
            }
            out += Block.ListBlock(ordered, startNumber, items)
            return i
        }
    }

    /** Inline spans. Splits into plain runs, `code`, **bold**, *italic*, [links](target) and bare URLs. */
    private object Inline {
        private val BARE_URL = Regex("https://[^\\s<>()\\[\\]]+")
        private val AUTOLINK = Regex("^<(https://[^>\\s]+)>")

        fun parse(text: String): List<Span> {
            val out = ArrayList<Span>()
            run(text, Span(""), out)
            return merge(out)
        }

        private fun merge(spans: List<Span>): List<Span> {
            val out = ArrayList<Span>()
            for (s in spans) {
                if (s.text.isEmpty()) continue
                val last = out.lastOrNull()
                if (last != null && !last.code && !s.code && last.bold == s.bold && last.italic == s.italic && last.link == s.link) {
                    out[out.lastIndex] = last.copy(text = last.text + s.text)
                } else {
                    out += s
                }
            }
            return out
        }

        private fun isWord(c: Char) = c.isLetterOrDigit()

        private fun emit(plain: String, style: Span, out: MutableList<Span>) {
            if (plain.isEmpty()) return
            if (style.link != null) { out += style.copy(text = plain); return }
            var pos = 0
            for (m in BARE_URL.findAll(plain)) {
                var end = m.range.last + 1
                while (end > m.range.first && plain[end - 1] in ".,;:!?'\"") end--
                if (end <= m.range.first) continue
                if (m.range.first > pos) out += style.copy(text = plain.substring(pos, m.range.first))
                out += style.copy(text = plain.substring(m.range.first, end), link = plain.substring(m.range.first, end))
                pos = end
            }
            if (pos < plain.length) out += style.copy(text = plain.substring(pos))
        }

        private fun run(s: String, style: Span, out: MutableList<Span>) {
            val plain = StringBuilder()
            var i = 0
            val n = s.length
            fun flush() { emit(plain.toString(), style, out); plain.setLength(0) }
            while (i < n) {
                val c = s[i]
                when {
                    c == '\\' && i + 1 < n && !s[i + 1].isLetterOrDigit() && !s[i + 1].isWhitespace() -> {
                        plain.append(s[i + 1]); i += 2
                    }
                    c == '`' -> {
                        var run = 1
                        while (i + run < n && s[i + run] == '`') run++
                        val fence = "`".repeat(run)
                        val close = s.indexOf(fence, i + run)
                        if (close < 0) { plain.append(fence); i += run; continue }
                        flush()
                        var code = s.substring(i + run, close)
                        if (code.length > 1 && code.startsWith(" ") && code.endsWith(" ")) code = code.substring(1, code.length - 1)
                        out += style.copy(text = code, code = true)
                        i = close + run
                    }
                    c == '<' -> {
                        val m = AUTOLINK.find(s.substring(i))
                        if (m != null) {
                            flush()
                            out += style.copy(text = m.groupValues[1], link = m.groupValues[1])
                            i += m.value.length
                        } else { plain.append(c); i++ }
                    }
                    c == '*' || (c == '_' && (i == 0 || !isWord(s[i - 1]))) -> {
                        val double = i + 1 < n && s[i + 1] == c
                        val width = if (double) 2 else 1
                        val delim = c.toString().repeat(width)
                        var close = findClose(s, delim, i + width)
                        if (close > 0 && close > i + width && !s[i + width].isWhitespace()) {
                            // Prefer the rightmost two of a run like ***x***.
                            if (double) while (close + 2 < n && s[close + 2] == c && s.indexOf(delim, close + 1) == close + 1) close++
                            flush()
                            val inner = s.substring(i + width, close)
                            run(inner, if (double) style.copy(bold = true) else style.copy(italic = true), out)
                            i = close + width
                        } else { plain.append(c); i++ }
                    }
                    c == '[' -> {
                        val link = parseLink(s, i)
                        if (link == null) { plain.append(c); i++ } else {
                            flush()
                            run(link.label, style.copy(link = link.target.takeIf { it.isNotEmpty() }), out)
                            i = link.end
                        }
                    }
                    else -> { plain.append(c); i++ }
                }
            }
            flush()
        }

        /** Index of the closing delimiter, skipping code spans; -1 if none. For `_` the closer must end a word. */
        private fun findClose(s: String, delim: String, from: Int): Int {
            var i = from
            while (i < s.length) {
                val c = s[i]
                if (c == '`') {
                    var run = 1
                    while (i + run < s.length && s[i + run] == '`') run++
                    val close = s.indexOf("`".repeat(run), i + run)
                    i = if (close < 0) i + run else close + run
                    continue
                }
                if (c == '\\') { i += 2; continue }
                if (s.startsWith(delim, i) && !s[i - 1].isWhitespace()) {
                    val after = i + delim.length
                    if (delim[0] == '*' || after >= s.length || !isWord(s[after])) {
                        // A lone '*' must not be the first half of '**'.
                        if (delim.length == 1 && after < s.length && s[after] == delim[0]) { i += 2; continue }
                        return i
                    }
                }
                i++
            }
            return -1
        }

        private class LinkMatch(val label: String, val target: String, val end: Int)

        private fun parseLink(s: String, start: Int): LinkMatch? {
            var depth = 0
            var i = start
            var closeBracket = -1
            while (i < s.length) {
                when (s[i]) {
                    '\\' -> i++
                    '`' -> {
                        val close = s.indexOf('`', i + 1)
                        if (close > 0) i = close
                    }
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { closeBracket = i; break } }
                }
                i++
            }
            if (closeBracket < 0 || closeBracket + 1 >= s.length || s[closeBracket + 1] != '(') return null
            var j = closeBracket + 2
            var parens = 1
            while (j < s.length) {
                when (s[j]) {
                    '(' -> parens++
                    ')' -> { parens--; if (parens == 0) break }
                }
                j++
            }
            if (parens != 0) return null
            val target = s.substring(closeBracket + 2, j).trim().substringBefore(' ')
            return LinkMatch(s.substring(start + 1, closeBracket), target, j + 1)
        }
    }
}
