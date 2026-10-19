package ai.rootforge.os.shizuku

/**
 * What the Android app can do with Shizuku or Sui on this phone.
 * Sui is treated as the same provider: if it is present, [NotInstalled] is not used.
 */
enum class ShizukuStatus {
    NotInstalled,
    NotRunning,
    PermissionDenied,
    Granted,
    ;

    fun label(): String = when (this) {
        NotInstalled -> "Not installed"
        NotRunning -> "Not running"
        PermissionDenied -> "Permission needed"
        Granted -> "Ready"
    }

    /** Plain-language copy for Settings. Mentions Sui wherever "not installed" or "not running" applies. */
    fun explanation(): String = when (this) {
        NotInstalled ->
            "Shizuku is not on this phone. Install Shizuku from shizuku.rikka.app, or, if this phone is already rooted, the Sui Magisk module. " +
                "The rest of RootForge works without it — USB ADB, fastboot and Flash Tools do not need Shizuku."
        NotRunning ->
            "Shizuku is installed but not running. Open the Shizuku app and start it (Wireless debugging on Android 11+, or adb from a computer). " +
                "Sui starts with the system after you install the Magisk module. Pre-v11 Shizuku is treated as not running."
        PermissionDenied ->
            "Shizuku (or Sui) is running. Tap Grant so RootForge can run read-only lookups on this phone. " +
                "Nothing is written, flashed, unlocked or wiped through this path."
        Granted ->
            "Shizuku (or Sui) is ready. Read-only lookups on this phone (packages, settings, properties, block devices) use it. " +
                "Flashing, unlock, wipe and any write still go over USB and always ask you to type a confirmation."
    }
}

/** Inputs the mapper needs. No Android types, so tests run on the JVM. */
data class ShizukuProbe(
    /** Shizuku app installed, or Sui reported present. */
    val providerPresent: Boolean,
    /** [rikka.shizuku.Shizuku.pingBinder] succeeded. */
    val binderAlive: Boolean,
    /** Shizuku permission API returned granted. */
    val permissionGranted: Boolean,
    /** Shizuku older than v11 cannot use the permission / UserService APIs we rely on. */
    val preV11: Boolean = false,
)

object ShizukuLinks {
    const val DOWNLOAD = "https://shizuku.rikka.app/download/"
    const val SUI = "https://github.com/RikkaApps/Sui"
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val PERMISSION = "moe.shizuku.manager.permission.API_V23"
}
