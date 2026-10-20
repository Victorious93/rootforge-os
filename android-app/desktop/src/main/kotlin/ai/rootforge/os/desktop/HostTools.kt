package ai.rootforge.os.desktop

import ai.rootforge.os.device.HostPlatform
import java.io.File
import java.util.concurrent.TimeUnit

data class CmdResult(val exit: Int, val stdout: String, val stderr: String) {
    val output: String get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
    val ok: Boolean get() = exit == 0
}

data class HostToolset(
    val adb: File?,
    val fastboot: File?,
    val edl: File?,
) {
    fun summary(): String = buildString {
        append("adb: ").append(adb?.absolutePath ?: "not found")
        append(" | fastboot: ").append(fastboot?.absolutePath ?: "not found")
        append(" | edl: ").append(edl?.absolutePath ?: "not found")
    }

    companion object {
        fun detect(extraDirs: List<File> = emptyList()): HostToolset {
            val namesAdb = exeNames("adb")
            val namesFastboot = exeNames("fastboot")
            val namesEdl = exeNames("edl") + exeNames("QSaharaServer")
            val dirs = searchDirs(extraDirs)
            return HostToolset(
                adb = find(namesAdb, dirs),
                fastboot = find(namesFastboot, dirs),
                edl = find(namesEdl, dirs),
            )
        }

        fun exeNames(base: String): List<String> =
            if (isWindows()) listOf("$base.exe", base) else listOf(base)

        fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)

        private fun searchDirs(extra: List<File>): List<File> {
            val home = System.getProperty("user.home")
            val env = listOfNotNull(
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
                System.getenv("ROOTFORGE_PLATFORM_TOOLS"),
            ).map { File(it) }
            val platformTools = env.map { File(it, "platform-tools") } + env
            val pathDirs = System.getenv("PATH").orEmpty()
                .split(File.pathSeparatorChar)
                .filter { it.isNotBlank() }
                .map { File(it) }
            val bundled = listOf(
                File("platform-tools"),
                File(home, "rootforge/platform-tools"),
                File(home, "AppData/Local/Android/Sdk/platform-tools"),
                File(home, "Library/Android/sdk/platform-tools"),
                File("/usr/lib/android-sdk/platform-tools"),
                // Raspberry Pi OS / Debian: apt platform-tools live on PATH; keep the
                // SDK default too so a Pi with a local SDK still resolves.
                File("/opt/android-sdk/platform-tools"),
            )
            return (extra + platformTools + bundled + pathDirs).filter { it.isDirectory }
        }

        private fun find(names: List<String>, dirs: List<File>): File? {
            for (dir in dirs) {
                for (n in names) {
                    val f = File(dir, n)
                    if (f.isFile && f.canExecute()) return f.canonicalFile
                }
            }
            for (n in names) {
                val fromPath = which(n)
                if (fromPath != null) return fromPath
            }
            return null
        }

        fun which(name: String): File? {
            val pb = ProcessBuilder(if (isWindows()) listOf("where", name) else listOf("which", name))
            pb.redirectErrorStream(true)
            return try {
                val p = pb.start()
                val text = p.inputStream.bufferedReader().readText().trim()
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    return null
                }
                val line = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
                File(line).takeIf { it.isFile }
            } catch (_: Exception) {
                null
            }
        }
    }
}

open class ProcessRunner {
    open fun run(
        exe: File,
        args: List<String>,
        timeoutMs: Long = 60_000,
        stdinBytes: ByteArray? = null,
    ): CmdResult {
        val pb = ProcessBuilder(listOf(exe.absolutePath) + args)
        pb.redirectErrorStream(false)
        val proc = pb.start()
        if (stdinBytes != null) {
            proc.outputStream.use { it.write(stdinBytes) }
        } else {
            proc.outputStream.close()
        }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { proc.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } }
        val errThread = Thread { proc.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
        outThread.start()
        errThread.start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            outThread.join(1_000)
            errThread.join(1_000)
            return CmdResult(124, stdout.toString(), stderr.toString() + "\n(timed out after ${timeoutMs}ms)")
        }
        outThread.join(5_000)
        errThread.join(5_000)
        return CmdResult(proc.exitValue(), stdout.toString(), stderr.toString())
    }
}

/**
 * Reads `/proc/device-tree/model` so a Raspberry Pi can reuse [HostController]
 * as an ordinary Linux host. Not an FRP or screen-lock helper.
 */
object LinuxBoard {
    fun deviceTreeModel(file: File = File("/proc/device-tree/model")): String? {
        if (!file.isFile) return null
        return runCatching {
            file.readBytes().toString(Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun hostCaption(file: File = File("/proc/device-tree/model")): String =
        HostPlatform.hostCaption(deviceTreeModel(file), androidHost = false)
}
