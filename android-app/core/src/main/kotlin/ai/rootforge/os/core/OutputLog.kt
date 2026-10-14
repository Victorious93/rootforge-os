package ai.rootforge.os.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel { COMMAND, OUTPUT, INFO, WARN, ERROR }

data class LogLine(val id: Long, val level: LogLevel, val text: String, val timeMs: Long)

/**
 * The live command output shown in the terminal panel. Bounded so a long
 * logcat session cannot grow without limit, and redacted before display so a
 * pasted token never lands in a shareable log.
 */
class OutputLog(private val maxLines: Int = 2000, private val clock: () -> Long = System::currentTimeMillis) {
    private val state = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = state.asStateFlow()
    private var nextId = 0L

    @Synchronized
    fun add(level: LogLevel, text: String) {
        val clean = Redaction.redact(text)
        val parts = clean.split('\n').filter { it.isNotEmpty() || clean.isEmpty() }
        state.update { old ->
            val added = parts.map { LogLine(nextId++, level, it.trimEnd('\r'), clock()) }
            (old + added).takeLast(maxLines)
        }
    }

    fun command(text: String) = add(LogLevel.COMMAND, text)
    fun out(text: String) = add(LogLevel.OUTPUT, text)
    fun info(text: String) = add(LogLevel.INFO, text)
    fun warn(text: String) = add(LogLevel.WARN, text)
    fun error(text: String) = add(LogLevel.ERROR, text)

    fun clear() = state.update { emptyList() }

    fun asText(): String = state.value.joinToString("\n") { it.text }
}

object Redaction {
    private val patterns = listOf(
        Regex("(?i)(password|passwd|token|secret|api[_-]?key)(\\s*[=:]\\s*)\\S+") to "$1$2[REDACTED]",
        Regex("(?i)(authorization:\\s*bearer\\s+)\\S+") to "$1[REDACTED]",
        Regex("\\b(ghp|gho|ghs|sk|xox[abp])[-_][A-Za-z0-9_-]{16,}") to "[REDACTED]",
    )

    fun redact(text: String): String = patterns.fold(text) { acc, (re, repl) -> re.replace(acc, repl) }
}
