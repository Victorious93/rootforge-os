package ai.rootforge.os.shizuku

/**
 * The only interface the rest of the app uses for privileged Shizuku/Sui
 * operations. Android binds a UserService behind this; tests and desktop
 * use [NullPrivilegedShell]. Callers must already have passed
 * `ApprovalCenter` — this type does not confirm.
 *
 * Implementations must run work off the app's main thread and must never
 * log stdout/stderr (command output can contain credentials, dumpsys, etc.).
 */
interface PrivilegedShell {
    fun isAvailable(): Boolean

    /** argv vector, never a shell string. Empty argv is a failure, not a spawn. */
    fun exec(argv: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): PrivilegedResult

    /** Small, fixed device-state snapshot (build props + `id`). No caller-supplied command. */
    fun readDeviceState(): PrivilegedResult

    /** `pm grant <package> <permission>` after name validation. */
    fun pmGrant(packageName: String, permission: String): PrivilegedResult

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

sealed class PrivilegedResult {
    data class Success(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    ) : PrivilegedResult()

    data class Failure(val reason: String, val cause: Throwable? = null) : PrivilegedResult()
}

/**
 * Wire format between the app process and the Shizuku UserService (AIDL
 * returns a String). Base64 so payloads may contain newlines. Never log
 * an encoded or decoded value.
 */
object PrivilegedResultCodec {
    fun encode(result: PrivilegedResult): String = when (result) {
        is PrivilegedResult.Success ->
            "S\n${result.exitCode}\n${if (result.timedOut) 1 else 0}\n${b64(result.stdout)}\n${b64(result.stderr)}"
        is PrivilegedResult.Failure ->
            "F\n${b64(result.reason)}"
    }

    fun decode(encoded: String): PrivilegedResult {
        val lines = encoded.split('\n')
        return try {
            when (lines.firstOrNull()) {
                "S" -> PrivilegedResult.Success(
                    exitCode = lines[1].toInt(),
                    timedOut = lines[2] == "1",
                    stdout = unb64(lines.getOrElse(3) { "" }),
                    stderr = unb64(lines.getOrElse(4) { "" }),
                )
                "F" -> PrivilegedResult.Failure(unb64(lines.getOrElse(1) { "" }).ifBlank { "privileged operation failed" })
                else -> PrivilegedResult.Failure("Malformed privileged result")
            }
        } catch (t: Throwable) {
            PrivilegedResult.Failure("Malformed privileged result", t)
        }
    }

    private fun b64(text: String): String =
        java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun unb64(text: String): String =
        String(java.util.Base64.getDecoder().decode(text), Charsets.UTF_8)
}

object PrivilegedNames {
    private val ANDROID_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isSafeAndroidName(value: String): Boolean =
        value.length in 3..256 && ANDROID_NAME.matches(value)
}
