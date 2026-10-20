package ai.rootforge.os.recovery

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * On-device `su` (the phone running the app). Desktop must not use this
 * to flash an Android recovery: host `su` is the PC, not the phone.
 */
class ProcessRootSession(
    private val suBinary: String = "su",
) : RootSession {
    override fun su(command: String, timeoutMs: Long): RootShellResult {
        val r = ProcessCapture.run(RecoveryCommands.suArgv(command), timeoutMs)
        return RootShellResult(r.output, r.exit)
    }

    override fun push(local: File, remote: String) {
        require(remote == RecoveryCommands.IMAGE_REMOTE)
        val r = su(RecoveryCommands.copyToTmp(local.absolutePath, remote), 60_000)
        if (!r.ok) throw IOException("Could not copy ${local.name} to $remote: ${r.output.take(200)}")
        su(RecoveryCommands.chmodReadable(remote), 10_000)
    }

    override fun pull(remote: String, local: File) {
        require(remote == RecoveryCommands.BACKUP_REMOTE || remote == RecoveryCommands.IMAGE_REMOTE)
        // Binary: do not decode stdout as text. cat via su, write bytes.
        val inner = "cat " + RecoveryCommands.shSingleQuote(remote)
        val p = ProcessBuilder(RecoveryCommands.suArgv(inner)).redirectErrorStream(false).start()
        local.parentFile?.mkdirs()
        local.outputStream().use { out -> p.inputStream.copyTo(out) }
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        if (!p.waitFor(120_000, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            throw IOException("su cat $remote timed out")
        }
        if (p.exitValue() != 0) throw IOException("su cat $remote failed: $err")
        if (local.length() == 0L) throw IOException("Pulled an empty file from $remote")
    }

    override fun rebootRecovery() {
        su(RecoveryCommands.rebootRecovery(), 10_000)
    }
}

/** Text-only process helper (getprop, `su -c id`). */
object ProcessCapture {
    data class Result(val output: String, val exit: Int)

    fun run(argv: List<String>, timeoutMs: Long): Result {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            throw IOException("Command timed out: ${argv.firstOrNull()}")
        }
        return Result(out, p.exitValue())
    }
}
