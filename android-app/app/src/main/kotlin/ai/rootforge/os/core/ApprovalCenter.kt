package ai.rootforge.os.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One request waiting for the person holding the host phone. Destructive
 * requests carry a phrase that must be typed; there is no default-accept and
 * no setting that skips this. A request nobody answers is denied.
 */
data class ApprovalRequest(
    val id: Long,
    val title: String,
    val source: String,
    val lines: List<String>,
    val phrase: String?,
    val destructive: Boolean,
)

class ApprovalCenter(private val onNewRequest: (ApprovalRequest) -> Unit = {}) {
    private class Entry(val request: ApprovalRequest, val answer: CompletableDeferred<Boolean>)

    private val entries = LinkedHashMap<Long, Entry>()
    private val state = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pending: StateFlow<List<ApprovalRequest>> = state.asStateFlow()
    private var nextId = 1L

    suspend fun request(
        title: String,
        lines: List<String>,
        source: String = "RootForge OS",
        phrase: String? = null,
        destructive: Boolean = phrase != null,
        timeoutMs: Long = 120_000,
    ): Boolean {
        val entry: Entry
        synchronized(this) {
            val req = ApprovalRequest(nextId++, title, source, lines, phrase, destructive)
            entry = Entry(req, CompletableDeferred())
            entries[req.id] = entry
            state.update { it + req }
        }
        onNewRequest(entry.request)
        try {
            return withTimeoutOrNull(timeoutMs) { entry.answer.await() } ?: false
        } finally {
            synchronized(this) {
                entries.remove(entry.request.id)
                state.update { list -> list.filterNot { it.id == entry.request.id } }
            }
        }
    }

    /** [typed] must equal the request's phrase exactly when one is required. */
    fun answer(id: Long, approve: Boolean, typed: String = ""): Boolean {
        val entry = synchronized(this) { entries[id] } ?: return false
        if (approve && entry.request.phrase != null && typed != entry.request.phrase) return false
        entry.answer.complete(approve)
        return true
    }
}
