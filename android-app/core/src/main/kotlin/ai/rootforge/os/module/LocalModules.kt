package ai.rootforge.os.module

import ai.rootforge.os.core.humanSize
import ai.rootforge.os.root.RootManager

/** One Magisk/KernelSU/APatch module read from a `module.prop` under `/data/adb/modules`. */
data class InstalledModule(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: String,
    val author: String,
    val description: String,
    val enabled: Boolean,
    val removePending: Boolean,
    val updatePending: Boolean,
)

/**
 * The command RootForge would run as root on this phone. [script] is the
 * inner `su -c` body; it never contains a reboot.
 */
data class LocalInstallPlan(
    val manager: RootManager,
    val zipName: String,
    val zipSize: Long,
    val sha256: String,
    val staging: String,
    val installLine: String,
    val script: String,
    val displayCommand: String,
    val approvalLines: List<String>,
    val dryRunLines: List<String>,
)

/**
 * Command construction and module.prop parsing for installing a zip on the
 * phone running the app. Callers execute through `su`; this object never
 * runs a process and never reboots.
 */
object LocalModules {
    const val STAGING = "/data/local/tmp/rf-module.zip"
    const val MODULES_DIR = "/data/adb/modules"
    const val NEVER_REBOOT = "RootForge will not reboot this phone. Use the Reboot button after install if the manager asks for it."
    const val MARKERS_SCRIPT = "ls -1 /data/adb"

    /**
     * One-line POSIX dump of each `module.prop` under `/data/adb/modules` plus
     * disable/remove/update flags. Parsed by [parseList]. No reboot.
     */
    val LIST_SCRIPT: String =
        "for d in /data/adb/modules/*; do " +
            "[ -d \"\$d\" ] || continue; " +
            "[ -f \"\$d/module.prop\" ] || continue; " +
            "echo ---; echo dir:\"\${d##*/}\"; " +
            "if [ -f \"\$d/disable\" ]; then echo disabled:yes; else echo disabled:no; fi; " +
            "if [ -f \"\$d/remove\" ]; then echo remove:yes; else echo remove:no; fi; " +
            "if [ -f \"\$d/update\" ]; then echo update:yes; else echo update:no; fi; " +
            "cat \"\$d/module.prop\"; echo; done"

    fun shellSingleQuote(value: String): String {
        require(value.none { it == '\n' || it == '\r' || it == '\u0000' }) { "refusing a path with a line break" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun suShell(inner: String): String = "su -c " + shellSingleQuote(inner)

    fun suArgv(inner: String): List<String> = listOf("su", "-c", inner)

    /** `magisk --install-module /data/local/tmp/rf-module.zip` (and KSU/APatch equivalents). */
    fun installInner(manager: RootManager): String =
        "${manager.moduleBinary} ${manager.moduleInstallCommand.substringAfter(' ')} $STAGING"

    fun rebootInner(): String = "reboot"

    fun copyAndInstallScript(localPath: String, manager: RootManager): String {
        val src = shellSingleQuote(requireLocalFile(localPath))
        val install = installInner(manager)
        return "cp $src $STAGING && chmod 644 $STAGING && $install; ec=${'$'}?; rm -f $STAGING; exit ${'$'}ec"
    }

    fun plan(
        localPath: String,
        zipName: String,
        zipSize: Long,
        sha256: String,
        manager: RootManager,
    ): LocalInstallPlan {
        val script = copyAndInstallScript(localPath, manager)
        val display = suShell(script)
        val install = installInner(manager)
        val size = humanSize(zipSize)
        val approval = listOf(
            "Module zip: $zipName ($size)",
            "SHA-256: $sha256",
            "Installed through: ${manager.label} on THIS phone (not a USB target).",
            "Command: $display",
            "A module runs scripts as root on this phone at install and at every boot. RootForge cannot check what is inside it.",
            "Only continue for a module you built or trust. A bad module can cause a bootloop.",
            NEVER_REBOOT,
        )
        val dryRun = listOf(
            "Dry run — nothing will be installed and this phone will not reboot.",
            "Module zip: $zipName ($size)",
            "SHA-256: $sha256",
            "Manager: ${manager.label} ($install)",
            "Would run: $display",
            NEVER_REBOOT,
        )
        return LocalInstallPlan(
            manager = manager,
            zipName = zipName,
            zipSize = zipSize,
            sha256 = sha256,
            staging = STAGING,
            installLine = install,
            script = script,
            displayCommand = display,
            approvalLines = approval,
            dryRunLines = dryRun,
        )
    }

    fun markersFromAdbLs(listing: String): List<String> =
        listing.lineSequence()
            .map { it.trim().substringAfterLast('/') }
            .filter { it.isNotEmpty() && !it.startsWith("ls:") && it != "*" }
            .toList()

    fun parseRooted(idOutput: String, exitCode: Int?): Boolean =
        exitCode == 0 && Regex("""uid=0\b""").containsMatchIn(idOutput)

    fun parseList(output: String): List<InstalledModule> {
        val modules = mutableListOf<InstalledModule>()
        var dir = ""
        var disabled = false
        var remove = false
        var update = false
        val props = LinkedHashMap<String, String>()

        fun flush() {
            val id = props["id"]?.trim().orEmpty().ifEmpty { dir }
            if (id.isNotEmpty()) {
                modules += InstalledModule(
                    id = id,
                    name = props["name"]?.trim().orEmpty().ifEmpty { id },
                    version = props["version"]?.trim().orEmpty(),
                    versionCode = props["versionCode"]?.trim().orEmpty(),
                    author = props["author"]?.trim().orEmpty(),
                    description = props["description"]?.trim().orEmpty(),
                    enabled = !disabled,
                    removePending = remove,
                    updatePending = update,
                )
            }
            dir = ""
            disabled = false
            remove = false
            update = false
            props.clear()
        }

        val text = output.replace("\r\n", "\n").replace('\r', '\n')
        for (raw in text.lineSequence()) {
            val trimmed = raw.trim()
            if (trimmed == "---") {
                flush()
                continue
            }
            when {
                trimmed.startsWith("dir:") -> dir = trimmed.removePrefix("dir:").trim()
                trimmed == "disabled:yes" -> disabled = true
                trimmed == "disabled:no" -> disabled = false
                trimmed == "remove:yes" -> remove = true
                trimmed == "remove:no" -> remove = false
                trimmed == "update:yes" -> update = true
                trimmed == "update:no" -> update = false
                trimmed.startsWith("#") || trimmed.isEmpty() -> Unit
                trimmed.contains('=') -> {
                    val key = trimmed.substringBefore('=').trim()
                    val value = trimmed.substringAfter('=').trim()
                    if (key.isNotEmpty()) props[key] = value
                }
            }
        }
        flush()
        return modules
    }

    private fun requireLocalFile(path: String): String {
        require(path.startsWith("/")) { "Local path must be absolute" }
        require(path.none { it == '\n' || it == '\r' || it == '\u0000' }) { "refusing a path with a line break" }
        require(".." !in path.split('/')) { "Refusing a path with .." }
        return path
    }
}
