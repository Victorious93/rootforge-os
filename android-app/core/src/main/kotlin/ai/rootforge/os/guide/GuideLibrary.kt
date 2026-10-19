package ai.rootforge.os.guide

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GuideSource(
    val name: String,
    val repository: String,
    val website: String,
    val license: String,
    /** Checkout the pages were generated from (may be a fork of [repository]). */
    val importedFrom: String = "",
    /** Commit of that checkout. */
    val commit: String,
)

@Serializable
data class GuideEntry(
    val id: String,
    val title: String,
    val summary: String,
    val file: String,
    /** `rootforge` for pages written in this project, otherwise the upstream project's name. */
    val origin: String,
    val sourcePath: String = "",
)

@Serializable
data class GuideSection(val title: String, val entries: List<GuideEntry>)

@Serializable
data class GuideManifest(val source: GuideSource, val sections: List<GuideSection>) {
    val entries: List<GuideEntry> get() = sections.flatMap { it.entries }
}

data class GuideHeading(val level: Int, val title: String, val anchor: String)

class GuideDocument(val entry: GuideEntry, val blocks: List<Block>) {
    /** Headings at every depth, in document order, including those inside callouts. */
    val headings: List<GuideHeading> by lazy { buildList { collectHeadings(blocks, this) } }

    fun hasAnchor(anchor: String): Boolean = headings.any { it.anchor == anchor }

    private fun collectHeadings(blocks: List<Block>, into: MutableList<GuideHeading>) {
        for (b in blocks) when (b) {
            is Block.Heading -> into += GuideHeading(b.level, b.spans.plainText(), b.anchor)
            is Block.Callout -> collectHeadings(b.blocks, into)
            is Block.Quote -> collectHeadings(b.blocks, into)
            is Block.ListBlock -> b.items.forEach { collectHeadings(it, into) }
            else -> Unit
        }
    }
}

data class GuideHit(
    val entry: GuideEntry,
    /** Heading of the matching section, or the page title when the match is before the first heading. */
    val heading: String,
    val anchor: String?,
    val snippet: String,
)

/**
 * The Root Guide shipped inside the app: `manifest.json` plus one markdown page per entry, read from
 * the classpath so the same content is available offline on Android and desktop. Pages are parsed on
 * first use and cached.
 */
class GuideLibrary(private val read: (String) -> String? = ::readResource) {
    val manifest: GuideManifest by lazy {
        val text = requireNotNull(read(MANIFEST)) { "Root Guide manifest is missing from the app" }
        json.decodeFromString(GuideManifest.serializer(), text)
    }

    private val cache = HashMap<String, GuideDocument>()

    fun entry(id: String): GuideEntry? = manifest.entries.firstOrNull { it.id == id }

    @Synchronized
    fun document(id: String): GuideDocument? {
        cache[id]?.let { return it }
        val entry = entry(id) ?: return null
        val text = read(entry.file) ?: return null
        return GuideDocument(entry, GuideMarkdown.parse(text)).also { cache[id] = it }
    }

    /** Sections whose heading, page title or text contain every word of [query]; headings rank first. */
    fun search(query: String, limit: Int = 30): List<GuideHit> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || query.trim().length < 2) return emptyList()
        data class Scored(val score: Int, val order: Int, val hit: GuideHit)
        val scored = ArrayList<Scored>()
        var order = 0
        for (entry in manifest.entries) {
            val doc = document(entry.id) ?: continue
            for (section in sections(doc)) {
                val haystack = (entry.title + "\n" + section.heading + "\n" + section.text).lowercase()
                if (!words.all { it in haystack }) continue
                val headingText = section.heading.lowercase()
                val score = (if (words.all { it in headingText }) 4 else 0) +
                    (if (words.all { it in entry.title.lowercase() }) 2 else 0) + 1
                scored += Scored(score, order++, GuideHit(entry, section.heading, section.anchor, snippet(section.text, words)))
            }
        }
        return scored.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.order }).take(limit).map { it.hit }
    }

    /**
     * Looser search for natural-language questions: sections matching at least half of [words] (and
     * never fewer than one), ranked by how many matched, headings and page titles counting extra.
     */
    fun searchAny(words: List<String>, limit: Int = 5): List<GuideHit> {
        val terms = words.map { it.lowercase() }.filter { it.length >= 2 }.distinct()
        if (terms.isEmpty()) return emptyList()
        val needed = maxOf(1, (terms.size + 1) / 2)
        data class Scored(val score: Int, val order: Int, val hit: GuideHit)
        val scored = ArrayList<Scored>()
        var order = 0
        for (entry in manifest.entries) {
            val doc = document(entry.id) ?: continue
            val title = entry.title.lowercase()
            for (section in sections(doc)) {
                val heading = section.heading.lowercase()
                val body = section.text.lowercase()
                val matched = terms.count { it in heading || it in title || it in body }
                if (matched < needed) continue
                val score = matched * 4 + terms.count { it in heading } * 3 + terms.count { it in title }
                scored += Scored(score, order++, GuideHit(entry, section.heading, section.anchor, snippet(section.text, terms)))
            }
        }
        return scored.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.order }).take(limit).map { it.hit }
    }

    private class Section(val heading: String, val anchor: String?, val text: String)

    private fun sections(doc: GuideDocument): List<Section> {
        val out = ArrayList<Section>()
        var heading = doc.entry.title
        var anchor: String? = null
        val text = StringBuilder()
        fun close() { if (text.isNotBlank() || anchor != null) out += Section(heading, anchor, text.toString()); text.setLength(0) }
        fun walk(blocks: List<Block>) {
            for (b in blocks) when (b) {
                is Block.Heading -> { close(); heading = b.spans.plainText(); anchor = b.anchor }
                is Block.Callout -> { text.append(b.title).append('\n'); walk(b.blocks) }
                else -> text.append(b.plainText()).append('\n')
            }
        }
        walk(doc.blocks)
        close()
        return out
    }

    private fun snippet(text: String, words: List<String>): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val at = words.map { flat.lowercase().indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val from = maxOf(0, at - 40)
        val to = minOf(flat.length, from + 140)
        return (if (from > 0) "…" else "") + flat.substring(from, to) + (if (to < flat.length) "…" else "")
    }

    companion object {
        const val MANIFEST = "manifest.json"
        private const val ROOT = "/ai/rootforge/os/guide/"
        private val json = Json { ignoreUnknownKeys = true }

        private fun readResource(name: String): String? =
            GuideLibrary::class.java.getResourceAsStream(ROOT + name)?.use { it.readBytes().toString(Charsets.UTF_8) }

        val default: GuideLibrary by lazy { GuideLibrary() }
    }
}
