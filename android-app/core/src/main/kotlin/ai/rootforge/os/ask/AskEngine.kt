package ai.rootforge.os.ask

import ai.rootforge.os.guide.GuideHit
import ai.rootforge.os.guide.GuideLibrary

data class AskResult(
    val question: String,
    /** Curated answers, best first. */
    val topics: List<AskTopic>,
    /** Root Guide sections that mention the question's words, best first. */
    val guideHits: List<GuideHit>,
) {
    val isEmpty: Boolean get() = topics.isEmpty() && guideHits.isEmpty()
}

/**
 * Answers a typed question from material bundled in the app: [AskTopics] first, then a loose search of
 * the Root Guide. This is keyword matching, not a language model: it cannot reason about a particular
 * phone, and an unmatched question simply returns nothing rather than a guess. Works offline.
 */
class AskEngine(
    private val topics: List<AskTopic> = AskTopics.all,
    private val library: GuideLibrary = GuideLibrary.default,
) {
    fun ask(question: String, maxTopics: Int = 3, maxGuideHits: Int = 4): AskResult {
        val q = question.trim()
        if (q.length < 3) return AskResult(q, emptyList(), emptyList())
        val lower = " " + q.lowercase() + " "
        val words = keywords(q)

        val ranked = topics
            .map { it to score(it, lower, words) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(maxTopics)
            .map { it.first }

        val skipPages = ranked.flatMap { it.guide }.toSet()
        val hits = library.searchAny(words, maxGuideHits + skipPages.size)
            .filter { it.entry.id !in skipPages || ranked.isEmpty() }
            .take(maxGuideHits)
        return AskResult(q, ranked, hits)
    }

    private fun score(topic: AskTopic, lowerQuestion: String, words: List<String>): Int {
        // A multi-word phrase is strong evidence; a lone stem is weaker.
        val phrase = topic.keywords.sumOf { k -> if (startsWord(lowerQuestion, k)) 3 + k.count { it == ' ' } * 2 else 0 }
        val title = topic.title.lowercase()
        val titleWords = words.count { it in title }
        return if (phrase == 0) 0 else phrase + titleWords
    }

    /** [keyword] occurs in [text] beginning at a word start, so `ota` does not match inside `rotate`. */
    private fun startsWord(text: String, keyword: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(keyword, from)
            if (at < 0) return false
            if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
            from = at + 1
        }
    }

    companion object {
        private val stop = setOf(
            "the", "a", "an", "and", "or", "to", "of", "in", "on", "for", "my", "me", "i", "is", "it", "do", "does",
            "how", "what", "why", "can", "should", "would", "with", "this", "that", "are", "was", "be", "get", "use",
            "from", "when", "which", "there", "will", "not", "about", "your", "you", "if", "any", "than", "then",
        )

        /** Lowercase words of [question] with filler removed; commands such as `adb` and `su` are kept. */
        fun keywords(question: String): List<String> =
            question.lowercase().split(Regex("[^a-z0-9_.+-]+"))
                .map { it.trim('.', '-', '+') }
                .filter { it.length >= 2 && it !in stop }
                .distinct()
    }
}
