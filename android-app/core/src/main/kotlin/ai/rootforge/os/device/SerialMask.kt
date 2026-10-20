package ai.rootforge.os.device

/**
 * Serial numbers are shown and logged with only the last four characters
 * visible. The full value is kept in memory for `adb -s` / USB targeting.
 */
object SerialMask {
    const val UNKNOWN = "unknown"

    fun mask(serial: String?): String {
        val t = serial?.trim().orEmpty()
        if (t.isEmpty()) return UNKNOWN
        return "••••" + t.takeLast(4)
    }

    /**
     * Replaces each known serial with [mask], longest first so a short
     * serial cannot punch a hole in a longer one.
     */
    fun replaceKnown(text: String, serials: Iterable<String?>): String {
        var out = text
        val known = serials.mapNotNull { it?.trim()?.takeIf { s -> s.length >= 6 } }
            .distinct()
            .sortedByDescending { it.length }
        for (serial in known) {
            if (serial in out) out = out.replace(serial, mask(serial))
        }
        return out
    }
}
