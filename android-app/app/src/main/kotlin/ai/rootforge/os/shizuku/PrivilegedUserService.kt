package ai.rootforge.os.shizuku

import android.content.Context
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit

/**
 * Runs as shell (Shizuku via adb) or root (Sui / Shizuku via root) in a
 * process Shizuku starts. Only the argv lists from [PrivilegedCommands]
 * are executed — never a shell string, never a write.
 *
 * A future destructive operation must not be added here without a typed
 * confirmation in the UI on every call.
 */
class PrivilegedUserService : IPrivilegedOps.Stub {
    @Keep
    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun destroy() {
        System.exit(0)
    }

    override fun listPackages(thirdPartyOnly: Boolean): String =
        run(PrivilegedCommands.listPackages(thirdPartyOnly))

    override fun packageInfo(packageName: String): String {
        val argv = PrivilegedCommands.packageInfo(packageName)
            ?: return "Refused: not a plausible package name."
        return run(argv)
    }

    override fun settingsGet(namespace: String, key: String): String {
        val argv = PrivilegedCommands.settingsGet(namespace, key)
            ?: return "Refused: namespace must be system, secure or global, and the key must be a simple token."
        return run(argv)
    }

    override fun getprop(key: String): String {
        val argv = PrivilegedCommands.getprop(key)
            ?: return "Refused: not a plausible property name."
        return run(argv)
    }

    override fun listBlockDevices(): String = PrivilegedCommands.blockDeviceCommands().joinToString("\n\n") { argv ->
        val header = argv.joinToString(" ")
        "$header\n${run(argv)}"
    }

    override fun deviceInfo(): String = PrivilegedCommands.DEVICE_INFO_KEYS.joinToString("\n") { key ->
        val argv = PrivilegedCommands.getprop(key) ?: return@joinToString "$key="
        "$key=${run(argv).trim()}"
    }

    private fun run(argv: List<String>): String {
        val proc = ProcessBuilder(argv).redirectErrorStream(true).apply {
            val path = environment()["PATH"].orEmpty()
            environment()["PATH"] = "$SYSTEM_PATH:$path"
        }.start()
        val out = try {
            proc.inputStream.bufferedReader().use { it.readText() }
        } finally {
            if (!proc.waitFor(15, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return "timed out"
            }
        }
        val trimmed = out.take(MAX_OUTPUT)
        val code = proc.exitValue()
        return if (code != 0 && trimmed.isBlank()) "exit $code" else trimmed
    }

    private companion object {
        const val MAX_OUTPUT = 64 * 1024
        const val SYSTEM_PATH = "/system/bin:/system/xbin:/product/bin:/apex/com.android.runtime/bin"
    }
}
