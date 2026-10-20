package ai.rootforge.os.bridge

import kotlin.math.abs

enum class ReplayVerdict { OK, EXPIRED, REPLAY }

/**
 * Rejects expired envelopes and duplicate nonces. Seen nonces are keyed by
 * ([sourceNodeRef], [nonce]) where [sourceNodeRef] comes from the kernel
 * caller identity, never from JSON. Entries are kept at least
 * [FLEET_NONCE_RETAIN_MS] (10 minutes).
 */
class ReplayGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val retainMs: Long = FLEET_NONCE_RETAIN_MS,
    private val maxSkewMs: Long = FLEET_MAX_SKEW_MS,
    private val maxEntries: Int = 4_096,
) {
    private val lock = Any()
    private val seen = LinkedHashMap<String, Long>()

    fun check(sourceNodeRef: String, nonce: String, timestampMs: Long, ttlMs: Long): ReplayVerdict {
        val now = clock()
        val key = key(sourceNodeRef, nonce)
        synchronized(lock) {
            pruneLocked(now)
            if (seen.containsKey(key)) return ReplayVerdict.REPLAY
            seen[key] = now
            while (seen.size > maxEntries) {
                val oldest = seen.keys.first()
                seen.remove(oldest)
            }
        }
        if (abs(now - timestampMs) > maxSkewMs) return ReplayVerdict.EXPIRED
        if (now > timestampMs + ttlMs) return ReplayVerdict.EXPIRED
        return ReplayVerdict.OK
    }

    fun size(): Int = synchronized(lock) { seen.size }

    private fun pruneLocked(now: Long) {
        val cutoff = now - retainMs
        val stale = seen.entries.filter { it.value < cutoff }.map { it.key }
        stale.forEach { seen.remove(it) }
    }

    companion object {
        fun key(sourceNodeRef: String, nonce: String): String =
            "$sourceNodeRef\u0000${nonce.lowercase()}"
    }
}
