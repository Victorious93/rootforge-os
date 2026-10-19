package ai.rootforge.os.shizuku

/**
 * Shared copy for the four Shizuku states and the permission request.
 * Fleet apps reuse this structure so the prompt does not drift.
 */
object ShizukuCopy {
    const val REQUEST_PERMISSION =
        "Grant this app permission to use Shizuku. Each privileged command still asks for confirmation before it runs."

    const val REQUEST_BUTTON = "Request Shizuku permission"

    const val SECTION_TITLE = "Shizuku / Sui"

    const val DOWNLOAD = "https://shizuku.rikka.app/download/"

    const val SUI = "https://github.com/RikkaApps/Sui"

    fun statusTitle(state: ShizukuState): String = when (state) {
        ShizukuState.GRANTED -> "Shizuku connected"
        ShizukuState.DENIED -> "Shizuku permission needed"
        ShizukuState.NOT_RUNNING -> "Shizuku is not running"
        ShizukuState.NOT_INSTALLED -> "Shizuku is not installed"
    }

    fun statusBody(state: ShizukuState): String = when (state) {
        ShizukuState.GRANTED ->
            "Privileged read-only lookups can run after you confirm each one. " +
                "Flashing, unlock, wipe and writes still go over USB and always ask you to type a confirmation."
        ShizukuState.DENIED -> REQUEST_PERMISSION
        ShizukuState.NOT_RUNNING ->
            "Start Shizuku with wireless debugging, or start Sui if you use Magisk. " +
                "The rest of the app still works without it."
        ShizukuState.NOT_INSTALLED ->
            "Install Shizuku (wireless debugging) or Sui (Magisk) to run privileged " +
                "commands without full root. The rest of the app still works without it."
    }

    const val UNAVAILABLE_FAILURE =
        "Shizuku is not connected. Install or start Shizuku/Sui and grant permission; the rest of the app still works."
}
