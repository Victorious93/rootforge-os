package ai.rootforge.os.recovery

import ai.rootforge.os.adb.AdbConnection
import java.io.File

/** Rooted USB target, through the in-app ADB client. */
class AdbRootSession(private val conn: AdbConnection) : RootSession {
    override fun su(command: String, timeoutMs: Long): RootShellResult {
        val r = conn.shell(RecoveryCommands.suShell(command), timeoutMs)
        return RootShellResult(r.output, r.exitCode)
    }

    override fun push(local: File, remote: String) {
        require(remote == RecoveryCommands.IMAGE_REMOTE)
        local.inputStream().use { conn.push(it, local.length(), remote) }
    }

    override fun pull(remote: String, local: File) {
        require(remote == RecoveryCommands.BACKUP_REMOTE || remote == RecoveryCommands.IMAGE_REMOTE)
        local.outputStream().use { conn.pull(remote, it) }
    }

    override fun rebootRecovery() {
        conn.reboot("recovery")
    }
}
