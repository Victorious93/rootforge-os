package ai.rootforge.os.module

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ModuleTarget(val label: String) { MAGISK("Magisk"), KERNELSU("KernelSU"), APATCH("APatch") }

data class ModuleSpec(
    val id: String,
    val name: String,
    val version: String = "v1.0",
    val versionCode: Int = 1,
    val author: String = "RootForge",
    val description: String = "",
    val target: ModuleTarget = ModuleTarget.MAGISK,
    val customizeSh: Boolean = true,
    val postFsDataSh: Boolean = false,
    val serviceSh: Boolean = true,
    val systemProp: Boolean = false,
    val sepolicyRule: Boolean = false,
    val uninstallSh: Boolean = false,
    val webroot: Boolean = false,
    /** action.sh: runs when you tap Action on the module in the manager. */
    val actionSh: Boolean = false,
    /** boot-completed.sh: runs once Android has finished booting. */
    val bootCompletedSh: Boolean = false,
    val updateJson: String = "",
    /**
     * Your own text for a generated file, keyed by its name (see [ModuleBuilder.EDITABLE]). Absent or
     * blank keeps the starter stub. A key for a file that is not enabled is a validation error, so
     * text is never silently dropped.
     */
    val bodies: Map<String, String> = emptyMap(),
    /** Extra files keyed by their path inside the zip (for example system/bin/tool). */
    val files: Map<String, ByteArray> = emptyMap(),
)

class BuiltModule(val zip: ByteArray, val fileName: String, val sha256: String, val entries: List<String>)

/**
 * Generates a flashable Magisk-format module zip (also what KernelSU and
 * APatch install). The validation rules match `lint_module.sh` in the
 * repository: same id pattern, same required module.prop keys, and no
 * control characters in any value (a newline would add keys).
 */
object ModuleBuilder {
    private val ID = Regex("^[a-zA-Z][a-zA-Z0-9_.-]*$")

    /** Files whose contents the author may supply. */
    val EDITABLE = listOf(
        "customize.sh", "post-fs-data.sh", "service.sh", "boot-completed.sh", "action.sh",
        "uninstall.sh", "system.prop", "sepolicy.rule",
    )
    const val MAX_BODY_BYTES = 64 * 1024
    private val STARTS_WITH_SHEBANG_REQUIRED = setOf("post-fs-data.sh", "service.sh", "boot-completed.sh", "action.sh", "uninstall.sh")

    /** Whether [file] is generated for [spec]. */
    fun enabled(spec: ModuleSpec, file: String): Boolean = when (file) {
        "customize.sh" -> spec.customizeSh
        "post-fs-data.sh" -> spec.postFsDataSh
        "service.sh" -> spec.serviceSh
        "boot-completed.sh" -> spec.bootCompletedSh
        "action.sh" -> spec.actionSh
        "uninstall.sh" -> spec.uninstallSh
        "system.prop" -> spec.systemProp
        "sepolicy.rule" -> spec.sepolicyRule
        else -> false
    }

    /**
     * Cautions that depend on the chosen manager. Hedged on purpose: they come from the managers'
     * documentation as remembered, not from running each one.
     */
    fun compatibilityNotes(spec: ModuleSpec): List<String> = buildList {
        if (spec.webroot && spec.target == ModuleTarget.MAGISK) add("webroot/ is a WebUI for KernelSU and APatch; Magisk ignores it [Likely].")
        if (spec.actionSh) add("action.sh needs a recent manager (Magisk 28 or newer, or a current KernelSU/APatch) [Likely]; older ones ignore it.")
        if (spec.bootCompletedSh) add("boot-completed.sh needs a recent manager [Likely]; older ones ignore it.")
        if (spec.sepolicyRule) add("sepolicy.rule changes SELinux policy as root; a wrong rule can stop the device booting.")
        if (spec.postFsDataSh) add("post-fs-data.sh blocks early boot; a hang or crash here can cause a bootloop. Keep it short.")
    }

    fun validate(spec: ModuleSpec): List<String> {
        val problems = mutableListOf<String>()
        if (!ID.matches(spec.id)) problems += "id must start with a letter and use only letters, digits, '.', '_' or '-'."
        if (spec.id.length > 64) problems += "id is longer than 64 characters."
        fun clean(label: String, value: String, required: Boolean) {
            if (required && value.isBlank()) problems += "$label is required."
            if (value.any { it.code < 32 || it.code == 127 }) problems += "$label contains a control character or newline."
        }
        clean("name", spec.name, true)
        clean("version", spec.version, true)
        clean("author", spec.author, true)
        clean("description", spec.description, false)
        clean("updateJson", spec.updateJson, false)
        if (spec.versionCode < 1) problems += "versionCode must be a positive integer."
        if (spec.updateJson.isNotBlank() && !spec.updateJson.startsWith("https://")) problems += "updateJson must be an https:// URL."
        for ((file, body) in spec.bodies) {
            when {
                file !in EDITABLE -> problems += "'$file' cannot have custom contents."
                !enabled(spec, file) -> problems += "$file has custom contents but is not enabled."
                body.toByteArray().size > MAX_BODY_BYTES -> problems += "$file is larger than ${MAX_BODY_BYTES / 1024} KiB."
                body.contains('\u0000') -> problems += "$file contains a NUL character."
            }
        }
        for (path in spec.files.keys) {
            if (path.startsWith("/") || path.split('/').any { it == ".." || it.isEmpty() }) problems += "file path '$path' is not a safe relative path."
            if (path.startsWith("META-INF/")) problems += "file path '$path' would overwrite the installer."
            if (path == "module.prop") problems += "module.prop is generated; do not supply it."
        }
        return problems
    }

    fun modulePropText(spec: ModuleSpec): String = buildString {
        appendLine("id=${spec.id}")
        appendLine("name=${spec.name}")
        appendLine("version=${spec.version}")
        appendLine("versionCode=${spec.versionCode}")
        appendLine("author=${spec.author}")
        appendLine("description=${spec.description.ifBlank { spec.name }}")
        if (spec.updateJson.isNotBlank()) appendLine("updateJson=${spec.updateJson}")
    }

    fun build(spec: ModuleSpec): BuiltModule {
        val problems = validate(spec)
        require(problems.isEmpty()) { problems.joinToString("; ") }
        val out = ByteArrayOutputStream()
        val names = mutableListOf<String>()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: ByteArray) {
                val entry = ZipEntry(name)
                // A fixed timestamp makes the zip reproducible for the same spec.
                entry.time = 315532800000L
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
                names += name
            }
            put("META-INF/com/google/android/update-binary", UPDATE_BINARY.toByteArray())
            put("META-INF/com/google/android/updater-script", "#MAGISK\n".toByteArray())
            put("module.prop", modulePropText(spec).toByteArray())
            fun file(name: String, stub: String) {
                if (enabled(spec, name)) put(name, (custom(spec, name) ?: stub).toByteArray())
            }
            file("customize.sh", customize(spec))
            file("post-fs-data.sh", script("post-fs-data.sh (runs early in boot, before /data is fully available)"))
            file("service.sh", script("service.sh (runs late in boot as a service)"))
            file("boot-completed.sh", script("boot-completed.sh (runs once Android has finished booting)"))
            file("action.sh", script("action.sh (runs when you tap Action on the module in the manager)"))
            file("uninstall.sh", script("uninstall.sh (runs when the module is removed)"))
            file("system.prop", "# Properties set via resetprop at boot\n")
            file("sepolicy.rule", "# Extra SELinux rules, one per line\n")
            if (spec.webroot) put("webroot/index.html", WEBROOT.toByteArray())
            for ((path, bytes) in spec.files.toSortedMap()) put(path, bytes)
        }
        val bytes = out.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return BuiltModule(bytes, "${spec.id}-${spec.version}.zip", sha, names)
    }

    fun writeTo(spec: ModuleSpec, out: OutputStream): BuiltModule {
        val built = build(spec)
        out.write(built.zip)
        return built
    }

    /**
     * customize.sh is sourced by the root manager. The display name has to be
     * one shell word: turning `"` into `'` still leaves `$(...)` and backticks
     * inside double quotes, and those run as root at install time.
     */
    private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun customize(spec: ModuleSpec): String {
        val line = shellSingleQuote("- Installing ${spec.name}")
        return """
            # ${spec.id}: install-time hook, sourced by the root manager's installer.
            ui_print $line
            # set_perm_recursive ${'$'}MODPATH/system 0 0 0755 0644
        """.trimIndent() + "\n"
    }

    /**
     * The author's own text for [name], or null to keep the stub. Windows line endings are converted
     * because `\r` after a command breaks sh scripts on the device; boot scripts get a shebang if
     * missing; the file always ends with a newline.
     */
    private fun custom(spec: ModuleSpec, name: String): String? {
        val raw = spec.bodies[name]?.takeIf { it.isNotBlank() } ?: return null
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (name in STARTS_WITH_SHEBANG_REQUIRED && !text.startsWith("#!")) text = "#!/system/bin/sh\n$text"
        return if (text.endsWith("\n")) text else text + "\n"
    }

    private fun script(title: String) = "#!/system/bin/sh\n# $title\nMODDIR=\${0%/*}\n"

    private const val WEBROOT = "<!doctype html><meta charset=\"utf-8\"><title>Module</title><h1>Module WebUI</h1>\n"

    /** The recovery-path installer: load Magisk's helpers and let install_module do the work. */
    const val UPDATE_BINARY = """#!/sbin/sh
umask 022
OUTFD=${'$'}2
ZIPFILE=${'$'}3
mount /data 2>/dev/null
ui_print() { echo "ui_print ${'$'}1" >&${'$'}OUTFD; echo "ui_print" >&${'$'}OUTFD; }
require_new_magisk() {
  ui_print "*******************************"
  ui_print " Please install Magisk v20.4+ "
  ui_print "*******************************"
  exit 1
}
[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
. /data/adb/magisk/util_functions.sh
[ "${'$'}{MAGISK_VER_CODE:-0}" -ge 20400 ] || require_new_magisk
install_module
exit 0
"""
}
