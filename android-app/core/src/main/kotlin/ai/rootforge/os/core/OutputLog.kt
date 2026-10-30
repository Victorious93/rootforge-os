package ai.rootforge.os.core

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class LogLevel { COMMAND, OUTPUT, INFO, WARN, ERROR }

data class LogLine(val id: Long, val level: LogLevel, val text: String, val timeMs: Long)

/**
 * The live command output shown in the terminal panel. Bounded so a long
 * logcat session cannot grow without limit, and redacted before display so a
 * pasted token never lands in a shareable log. When [persistFile] is set the
 * same redacted lines are stored as JSON-lines and reloaded on the next start.
 */
class OutputLog(
    private val maxLines: Int = 2000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val persistFile: File? = null,
) {
    private val state = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = state.asStateFlow()
    private var nextId = 0L

    init {
        load()
    }

    @Synchronized
    fun add(level: LogLevel, text: String) {
        val clean = Redaction.redact(text)
        val parts = clean.split('\n').filter { it.isNotEmpty() || clean.isEmpty() }
        val added = parts.map { LogLine(nextId++, level, it.trimEnd('\r'), clock()) }
        var overflow = false
        state.update { old ->
            val merged = old + added
            overflow = merged.size > maxLines
            merged.takeLast(maxLines)
        }
        if (persistFile != null) {
            if (overflow) rewrite() else append(added)
        }
    }

    fun command(text: String) = add(LogLevel.COMMAND, text)
    fun out(text: String) = add(LogLevel.OUTPUT, text)
    fun info(text: String) = add(LogLevel.INFO, text)
    fun warn(text: String) = add(LogLevel.WARN, text)
    fun error(text: String) = add(LogLevel.ERROR, text)

    @Synchronized
    fun clear() {
        state.update { emptyList() }
        persistFile?.let { file ->
            PrivateFile.prepare(file)
            file.writeText("")
            PrivateFile.restrictToOwner(file)
        }
    }

    fun asText(): String = state.value.joinToString("\n") { it.text }

    private fun load() {
        val file = persistFile ?: return
        if (!file.isFile) return
        PrivateFile.restrictToOwner(file)
        val restored = mutableListOf<LogLine>()
        file.forEachLine { raw ->
            if (raw.isBlank()) return@forEachLine
            val parsed = runCatching { json.decodeFromString(PersistLine.serializer(), raw) }.getOrNull() ?: return@forEachLine
            val level = runCatching { LogLevel.valueOf(parsed.level) }.getOrNull() ?: return@forEachLine
            restored += LogLine(parsed.id, level, Redaction.redact(parsed.text), parsed.timeMs)
        }
        val kept = restored.takeLast(maxLines)
        nextId = (kept.maxOfOrNull { it.id } ?: -1L) + 1L
        state.value = kept
        if (restored.size > maxLines) rewrite()
    }

    private fun append(added: List<LogLine>) {
        val file = persistFile ?: return
        PrivateFile.prepare(file)
        file.appendText(added.joinToString("") { json.encodeToString(PersistLine.from(it)) + "\n" })
        PrivateFile.restrictToOwner(file)
    }

    private fun rewrite() {
        val file = persistFile ?: return
        PrivateFile.prepare(file)
        file.writeText(state.value.joinToString("") { json.encodeToString(PersistLine.from(it)) + "\n" })
        PrivateFile.restrictToOwner(file)
    }

    @Serializable
    private data class PersistLine(val id: Long, val level: String, val text: String, val timeMs: Long) {
        companion object {
            fun from(line: LogLine) = PersistLine(line.id, line.level.name, line.text, line.timeMs)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

object Redaction {
    private val patterns = listOf(
        Regex("(?i)(password|passwd|token|secret|api[_-]?key)(\\s*[=:]\\s*)\\S+") to "$1$2[REDACTED]",
        Regex("(?i)(authorization:\\s*bearer\\s+)\\S+") to "$1[REDACTED]",
        Regex("\\b(ghp|gho|ghs|sk|xox[abp])[-_][A-Za-z0-9_-]{16,}") to "[REDACTED]",
    )

    fun redact(text: String): String = patterns.fold(text) { acc, (re, repl) -> re.replace(acc, repl) }
}
