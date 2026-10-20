package ai.rootforge.os.device

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One mutex per device serial so two phones can run work at the same time
 * while each phone still only has one destructive op in flight.
 */
class SerialOpLock {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val busyState = MutableStateFlow<Map<String, String>>(emptyMap())
    val busyMap: StateFlow<Map<String, String>> = busyState.asStateFlow()

    fun key(serial: String?): String = Companion.key(serial)

    fun isBusy(serial: String?): Boolean = busyState.value.containsKey(key(serial))

    fun busyLabel(serial: String?): String? = busyState.value[key(serial)]

    suspend fun <T> withLock(serial: String?, label: String, block: suspend () -> T): T {
        val k = Companion.key(serial)
        val mutex = locks.getOrPut(k) { Mutex() }
        return mutex.withLock {
            busyState.update { it + (k to label) }
            try {
                block()
            } finally {
                busyState.update { it - k }
            }
        }
    }

    companion object {
        const val UNKNOWN_KEY = "unknown"

        fun key(serial: String?): String =
            serial?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_KEY
    }
}
