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
    val updateJson: String = "",
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
            if (spec.customizeSh) put("customize.sh", customize(spec).toByteArray())
            if (spec.postFsDataSh) put("post-fs-data.sh", script("post-fs-data.sh (runs early in boot, before /data is fully available)").toByteArray())
            if (spec.serviceSh) put("service.sh", script("service.sh (runs late in boot as a service)").toByteArray())
            if (spec.uninstallSh) put("uninstall.sh", script("uninstall.sh (runs when the module is removed)").toByteArray())
            if (spec.systemProp) put("system.prop", "# Properties set via resetprop at boot\n".toByteArray())
            if (spec.sepolicyRule) put("sepolicy.rule", "# Extra SELinux rules, one per line\n".toByteArray())
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
