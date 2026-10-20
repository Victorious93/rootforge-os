package ai.rootforge.os.desktopmode

/**
 * Companion constants for VictorSuite ([Certain] from Victorious93/VictorSuite on 2026-10-20):
 * it is a Termux fork that keeps `applicationId` `com.termux` and the Termux
 * `RUN_COMMAND` service. A dedicated Desktop-mode action is specified here and
 * in `docs/DESKTOP_MODE.md`; VictorSuite does not export it yet.
 *
 * Pairing is package name plus signing-certificate SHA-256. There is no shared
 * signing key with RootForge.
 */
object VictorSuite {
    /** Confirmed application id of VictorSuite (same as upstream Termux). */
    const val PACKAGE = "com.termux"

    /**
     * Extra package ids RootForge looks for if VictorSuite ever ships under its
     * own id. None of these are installed as VictorSuite today.
     */
    val CANDIDATE_PACKAGES: List<String> = listOf(
        PACKAGE,
        "ai.victorsuite.app",
        "ai.victorsuite.app.debug",
    )

    const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"

    const val TERMUX_FILES = "/data/data/com.termux/files"
    const val BASH_PATH = "$TERMUX_FILES/usr/bin/bash"
    const val HOME_PATH = "$TERMUX_FILES/home"

    /** Termux-side wrapper installed by `setup_desktop_mode.sh`. */
    const val ENTRY_COMMAND = "rootforge-desktop-mode"

    /**
     * Narrower action VictorSuite should add. Until it does, RootForge falls
     * back to RUN_COMMAND with only this documented command, never a free-form
     * shell from the UI.
     */
    const val ACTION_DESKTOP_MODE = "ai.victorsuite.action.DESKTOP_MODE"
    const val PERMISSION_DESKTOP_MODE = "ai.victorsuite.permission.DESKTOP_MODE"
    const val SERVICE_DESKTOP_MODE = "com.termux.zerocore.desktop.DesktopModeService"
    const val EXTRA_OPERATION = "ai.victorsuite.extra.OPERATION"
    const val EXTRA_DISPLAY = "ai.victorsuite.extra.DISPLAY"

    const val DEFAULT_DISPLAY = ":0"
}

enum class DesktopModeOp(val wire: String) {
    START("start"),
    STOP("stop"),
    SETUP("setup"),
    STATUS("status"),
    ;

    companion object {
        fun fromWire(value: String): DesktopModeOp? = entries.firstOrNull { it.wire == value }
    }
}
