package ai.rootforge.os.desktopmode

/**
 * How far RootForge can go toward launching a Linux desktop through VictorSuite.
 * Pure data; the Android app supplies [CompanionPackage] from PackageManager.
 */
enum class DesktopModeStatus {
    /** VictorSuite is not installed. The card is a hint, never a crash. */
    NOT_INSTALLED,

    /** A candidate package is present but exports neither RUN_COMMAND nor the dedicated action. */
    INSTALLED_NO_ENTRYPOINT,

    /** Installed, but the owner has not pinned this package+certificate yet. */
    NEEDS_PIN,

    /** Same package as the pin, different signing certificate. */
    CERT_CHANGED,

    /** Dedicated Desktop-mode action is present and the pin matches. */
    READY_DEDICATED,

    /** Only Termux RUN_COMMAND is present; pin matches. Dedicated action not implemented yet. */
    READY_RUN_COMMAND,
}

data class CompanionPackage(
    val packageName: String,
    val certSha256: String,
    val exportsRunCommand: Boolean,
    val exportsDesktopAction: Boolean,
)

data class DesktopModeView(
    val status: DesktopModeStatus,
    val packageName: String = "",
    val certSha256: String = "",
    val headline: String,
    val body: String,
    val canLaunch: Boolean = false,
    val needsPin: Boolean = false,
    val transportLabel: String = "",
)

/**
 * An intent RootForge is allowed to send. The Android layer copies this into
 * `android.content.Intent`; nothing here starts a component.
 */
data class IntentSpec(
    val packageName: String,
    val action: String,
    val className: String?,
    val isService: Boolean,
    val permission: String,
    val stringExtras: Map<String, String> = emptyMap(),
    val stringArrayExtras: Map<String, List<String>> = emptyMap(),
    val booleanExtras: Map<String, Boolean> = emptyMap(),
)

data class PinnedCompanion(val packageName: String, val certSha256: String) {
    fun wire(): String = "$packageName:$certSha256"

    companion object {
        fun parse(raw: String): PinnedCompanion? {
            val idx = raw.indexOf(':')
            if (idx <= 0 || idx == raw.lastIndex) return null
            val pkg = raw.substring(0, idx)
            val cert = raw.substring(idx + 1).lowercase()
            if (pkg.isBlank() || cert.isBlank()) return null
            return PinnedCompanion(pkg, cert)
        }
    }
}

object DesktopMode {
    fun probe(installed: List<CompanionPackage>, pinned: PinnedCompanion?): DesktopModeView {
        val found = pick(installed)
        if (found == null) {
            return DesktopModeView(
                status = DesktopModeStatus.NOT_INSTALLED,
                headline = "VictorSuite is not installed",
                body = "Desktop mode needs VictorSuite (package ${VictorSuite.PACKAGE}) on this phone. " +
                    "Without it this card does nothing; RootForge will not crash. " +
                    "VictorSuite is a Termux-based Linux environment that can host XFCE over X11.",
            )
        }
        val entry = found.exportsDesktopAction || found.exportsRunCommand
        if (!entry) {
            return DesktopModeView(
                status = DesktopModeStatus.INSTALLED_NO_ENTRYPOINT,
                packageName = found.packageName,
                certSha256 = found.certSha256,
                headline = "VictorSuite cannot start Desktop mode yet",
                body = "${found.packageName} is installed, but it does not export the Desktop mode action " +
                    "(${VictorSuite.ACTION_DESKTOP_MODE}) or Termux RUN_COMMAND. " +
                    "That entrypoint has to be added in VictorSuite. See docs/DESKTOP_MODE.md.",
            )
        }
        val pin = pinned
        if (pin == null) {
            return DesktopModeView(
                status = DesktopModeStatus.NEEDS_PIN,
                packageName = found.packageName,
                certSha256 = found.certSha256,
                headline = "Confirm this is your VictorSuite",
                body = "RootForge talks to VictorSuite by package name and signing certificate, not a shared key. " +
                    "Pin ${found.packageName} (sha256 ${shortCert(found.certSha256)}) before any command is sent.",
                needsPin = true,
            )
        }
        if (pin.packageName != found.packageName || !pin.certSha256.equals(found.certSha256, ignoreCase = true)) {
            return DesktopModeView(
                status = DesktopModeStatus.CERT_CHANGED,
                packageName = found.packageName,
                certSha256 = found.certSha256,
                headline = "VictorSuite’s signing certificate changed",
                body = "The installed ${found.packageName} is not the one you pinned. " +
                    "A different app reusing the package name is not trusted. Pin again only if you installed this build.",
                needsPin = true,
            )
        }
        return if (found.exportsDesktopAction) {
            DesktopModeView(
                status = DesktopModeStatus.READY_DEDICATED,
                packageName = found.packageName,
                certSha256 = found.certSha256,
                headline = "Desktop mode is ready",
                body = "VictorSuite exposes the dedicated Desktop mode action. " +
                    "Start opens XFCE on the X11 display (VNC if X11 is down) and launches the RootForge desktop UI and VS Code inside that session.",
                canLaunch = true,
                transportLabel = "dedicated action",
            )
        } else {
            DesktopModeView(
                status = DesktopModeStatus.READY_RUN_COMMAND,
                packageName = found.packageName,
                certSha256 = found.certSha256,
                headline = "Desktop mode (via RUN_COMMAND)",
                body = "VictorSuite does not yet export ${VictorSuite.ACTION_DESKTOP_MODE}. " +
                    "Until it does, RootForge sends only `rootforge-desktop-mode <start|stop|setup|status>` through Termux RUN_COMMAND — never a free-form shell. " +
                    "Grant “Run commands in Termux environment”, and set allow-external-apps=true in VictorSuite. See docs/DESKTOP_MODE.md.",
                canLaunch = true,
                transportLabel = "RUN_COMMAND",
            )
        }
    }

    fun intentFor(view: DesktopModeView, op: DesktopModeOp, display: String = VictorSuite.DEFAULT_DISPLAY): IntentSpec? {
        if (!view.canLaunch || view.packageName.isBlank()) return null
        return when (view.status) {
            DesktopModeStatus.READY_DEDICATED -> dedicatedIntent(view.packageName, op, display)
            DesktopModeStatus.READY_RUN_COMMAND -> runCommandIntent(view.packageName, op)
            else -> null
        }
    }

    fun dedicatedIntent(packageName: String, op: DesktopModeOp, display: String = VictorSuite.DEFAULT_DISPLAY): IntentSpec =
        IntentSpec(
            packageName = packageName,
            action = VictorSuite.ACTION_DESKTOP_MODE,
            className = VictorSuite.SERVICE_DESKTOP_MODE,
            isService = true,
            permission = VictorSuite.PERMISSION_DESKTOP_MODE,
            stringExtras = mapOf(
                VictorSuite.EXTRA_OPERATION to op.wire,
                VictorSuite.EXTRA_DISPLAY to display,
            ),
        )

    fun runCommandIntent(packageName: String, op: DesktopModeOp): IntentSpec =
        IntentSpec(
            packageName = packageName,
            action = VictorSuite.RUN_COMMAND_ACTION,
            className = VictorSuite.RUN_COMMAND_SERVICE,
            isService = true,
            permission = VictorSuite.RUN_COMMAND_PERMISSION,
            stringExtras = mapOf(
                VictorSuite.EXTRA_COMMAND_PATH to VictorSuite.BASH_PATH,
                VictorSuite.EXTRA_WORKDIR to VictorSuite.HOME_PATH,
                VictorSuite.EXTRA_COMMAND_LABEL to "RootForge Desktop mode",
                VictorSuite.EXTRA_SESSION_ACTION to "0",
            ),
            stringArrayExtras = mapOf(
                VictorSuite.EXTRA_ARGUMENTS to listOf("-lc", runCommandLine(op)),
            ),
            booleanExtras = mapOf(VictorSuite.EXTRA_BACKGROUND to false),
        )

    /** Fixed command line. The UI never interpolates user text into this. */
    fun runCommandLine(op: DesktopModeOp): String =
        "command -v ${VictorSuite.ENTRY_COMMAND} >/dev/null && exec ${VictorSuite.ENTRY_COMMAND} ${op.wire} || " +
            "{ echo 'rootforge-desktop-mode is not on PATH. Inside the Debian rootfs run setup_desktop_mode.sh, " +
            "or install the Termux wrapper from the RootForge tree.'; exit 1; }"

    fun pick(installed: List<CompanionPackage>): CompanionPackage? {
        val byName = installed.associateBy { it.packageName }
        return VictorSuite.CANDIDATE_PACKAGES.firstNotNullOfOrNull { byName[it] }
    }

    fun shortCert(hex: String): String = if (hex.length <= 16) hex else hex.take(16) + "…"
}
