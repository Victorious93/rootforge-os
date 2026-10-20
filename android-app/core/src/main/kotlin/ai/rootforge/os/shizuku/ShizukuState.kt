package ai.rootforge.os.shizuku

/**
 * Fleet Shizuku standard — four distinct runtime states. Every fleet app
 * (DroidCommand AI, File Manager, RootForge OS, Pentest-Swarm-AI, HackerAI)
 * must surface exactly these, never a combined "unavailable" blob.
 *
 * Detection order is deliberate: a live binder (Shizuku *or* Sui — same API)
 * is proof the service is present even when the manager package is invisible
 * to PackageManager (Sui). Permission is only meaningful once the binder is
 * alive; `Shizuku.pingBinder()` must be checked before any permission call.
 */
enum class ShizukuState {
    /** Binder alive and `moe.shizuku.manager.permission.API_V23` granted. */
    GRANTED,

    /** Binder alive, permission not granted (denied or not yet requested). */
    DENIED,

    /** Neither the Shizuku manager nor Sui is installed, and no binder is alive. */
    NOT_INSTALLED,

    /** Shizuku or Sui is installed, but `pingBinder()` is false (not started). */
    NOT_RUNNING,
}

/**
 * Raw probes [ShizukuStateResolver] consumes. Keep this a plain data class so
 * JVM tests can drive the four states without the Android Shizuku SDK.
 */
data class ShizukuSignals(
    /** True if [ShizukuPackages.SHIZUKU] or [ShizukuPackages.SUI] is installed. */
    val managerInstalled: Boolean,
    /** True if `Shizuku.pingBinder()` succeeded. Sui uses the same binder. */
    val binderAlive: Boolean,
    /** True if `Shizuku.checkSelfPermission()` is granted. Ignored unless [binderAlive]. */
    val permissionGranted: Boolean,
)

object ShizukuPackages {
    /** Official Shizuku manager. Must be named in the app's `<queries>`. */
    const val SHIZUKU = "moe.shizuku.privileged.api"

    /** Sui (Magisk module) — same Shizuku API. May not be queryable as a normal app. */
    const val SUI = "rikka.sui"

    const val API_PERMISSION = "moe.shizuku.manager.permission.API_V23"

    val queryPackages: Set<String> = setOf(SHIZUKU, SUI)
}

/**
 * Pure four-state machine. Other fleet apps should copy this function
 * rather than re-deriving the order; tests in this module are the contract.
 */
object ShizukuStateResolver {
    fun resolve(signals: ShizukuSignals): ShizukuState {
        // A live binder means Shizuku or Sui is present even if PackageManager
        // cannot see a manager APK (the usual Sui topology).
        val present = signals.managerInstalled || signals.binderAlive
        if (!present) return ShizukuState.NOT_INSTALLED
        if (!signals.binderAlive) return ShizukuState.NOT_RUNNING
        return if (signals.permissionGranted) ShizukuState.GRANTED else ShizukuState.DENIED
    }
}
