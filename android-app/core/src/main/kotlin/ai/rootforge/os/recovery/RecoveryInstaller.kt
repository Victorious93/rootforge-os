package ai.rootforge.os.recovery

import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.core.FileDigest
import ai.rootforge.os.core.Library
import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.OutputLog
import ai.rootforge.os.core.humanSize
import ai.rootforge.os.core.readPrefix
import java.io.File
import java.io.IOException

data class RootShellResult(val output: String, val exitCode: Int?) {
    /**
     * Missing status is not success. The Android shell reports the exit
     * code with a trailer; if the stream closes first (su killed, adb
     * drop) that trailer never arrives and a half-written backup must
     * not be treated as finished.
     */
    val ok: Boolean get() = exitCode == 0
}

/**
 * How the installer talks to a rooted phone. Android USB ADB, desktop
 * host `adb`, and on-device `su` each implement this; command strings
 * come from [RecoveryCommands].
 */
interface RootSession {
    fun su(command: String, timeoutMs: Long = 120_000): RootShellResult
    fun push(local: File, remote: String)
    fun pull(remote: String, local: File)
    fun rebootRecovery()
}

data class RecoveryInstallResult(
    val dryRun: Boolean,
    val backup: LibraryFile?,
    val plan: RecoveryPlan,
)

/**
 * Probe helpers plus the dry-run / backup / `dd` sequence. Destructive
 * writes go through [ApprovalCenter] with `FLASH RECOVERY`. Nothing here
 * reboots unless the caller asks after a successful write.
 */
class RecoveryInstaller(
    private val log: OutputLog,
    private val library: Library,
    private val approvals: ApprovalCenter,
) {
    fun parseRoot(idOutput: String, exitCode: Int?): Boolean =
        (exitCode == null || exitCode == 0) && Regex("""uid=0\b""").containsMatchIn(idOutput)

    fun probe(source: RecoverySource, props: Map<String, String>, listing: String, idOutput: String, idExit: Int?): RecoveryProbe {
        val identity = RecoveryIdentity.fromProps(props)
        val rooted = parseRoot(idOutput, idExit)
        val layout = RecoveryLayoutDetector.detect(identity, listing)
        val detail = idOutput.trim().ifBlank { "su produced no output (exit ${idExit ?: "none"})" }
        return RecoveryProbe(source, identity, rooted, detail.take(240), layout)
    }

    fun evaluate(
        probe: RecoveryProbe,
        image: LibraryFile,
        dryRun: Boolean,
        publishedMd5: String? = null,
        publishedSha256: String? = null,
        partitionSize: Long? = null,
    ): RecoveryPlan {
        val head = image.file.readPrefix(4096)
        val sha = FileDigest.sha256Hex(image.file)
        val md5 = FileDigest.md5Hex(image.file)
        return RecoveryPlanner.plan(
            layout = probe.layout,
            rooted = probe.rooted,
            imageName = image.name,
            imageSize = image.size,
            imageHead = head,
            sha256 = sha,
            md5 = md5,
            publishedMd5 = publishedMd5,
            publishedSha256 = publishedSha256,
            partitionSize = partitionSize,
            dryRun = dryRun,
        )
    }

    fun readPartitionSize(session: RootSession, layout: RecoveryLayout): Long? {
        val path = layout.writePath ?: return null
        val r = session.su(RecoveryCommands.partitionSize(path), 15_000)
        return RecoveryCommands.parseBlockdevSize(r.output)
    }

    /**
     * Dry-run logs the commands and stats the partition. Write backs up
     * the current recovery into the library, then `dd`s the image. Never
     * reboots.
     */
    suspend fun install(
        session: RootSession,
        probe: RecoveryProbe,
        image: LibraryFile,
        dryRun: Boolean,
        publishedMd5: String? = null,
        publishedSha256: String? = null,
        source: String = "RootForge OS",
    ): RecoveryInstallResult {
        if (!probe.rooted) throw IOException("su is not available; nothing was written.")
        val size = runCatching { readPartitionSize(session, probe.layout) }.getOrNull()
        val plan = evaluate(probe, image, dryRun, publishedMd5, publishedSha256, size)
        plan.lines.forEach { log.info(it) }
        if (!plan.allowed) {
            plan.blockers.forEach { log.error("Refused: $it") }
            throw IOException("Refused: " + plan.blockers.first())
        }
        if (dryRun) {
            val path = probe.layout.writePath
            log.info("Dry-run commands (not executed as a write):")
            log.command(RecoveryCommands.suShell(RecoveryCommands.listPartitions()))
            if (path != null && plan.payload == RecoveryPayload.IMAGE) {
                log.command(RecoveryCommands.suShell(RecoveryCommands.statPartition(path)))
                log.command(RecoveryCommands.suShell(RecoveryCommands.backup(probe.layout)))
                log.command(RecoveryCommands.suShell(RecoveryCommands.write(probe.layout)))
                val stat = session.su(RecoveryCommands.statPartition(path), 15_000)
                log.out(stat.output.trim())
            }
            log.info("Dry-run finished. Nothing was written.")
            return RecoveryInstallResult(dryRun = true, backup = null, plan = plan)
        }
        if (plan.payload != RecoveryPayload.IMAGE || probe.layout.writePath == null) {
            throw IOException("Only a recovery.img can be written with dd, and only onto a dedicated recovery partition.")
        }
        val phrase = plan.confirmationPhrase ?: throw IOException("Internal error: write plan has no confirmation phrase.")
        val approved = approvals.request(
            title = "Write custom recovery",
            lines = plan.lines,
            source = source,
            phrase = phrase,
        )
        if (!approved) throw IOException("Write was not confirmed; nothing was written.")

        val listing = session.su(RecoveryCommands.listPartitions(), 15_000).output
        val again = RecoveryLayoutDetector.detect(probe.identity, listing)
        if (again.writePath != probe.layout.writePath) {
            throw IOException("The recovery partition changed after you confirmed (${probe.layout.writePath} → ${again.writePath}); nothing was written.")
        }
        RecoveryCommands.requireWritePath(again)

        log.warn("The phone may show a Superuser prompt; allow it once.")
        log.command("push ${image.name} ${RecoveryCommands.IMAGE_REMOTE}")
        session.push(image.file, RecoveryCommands.IMAGE_REMOTE)

        log.command(RecoveryCommands.suShell(RecoveryCommands.backup(again)))
        val backupRun = session.su(RecoveryCommands.backup(again), 180_000)
        if (!backupRun.ok) throw IOException("Backup of the current recovery failed: ${backupRun.output.take(240)}")
        session.su(RecoveryCommands.chmodReadable(RecoveryCommands.BACKUP_REMOTE), 15_000)
        val backupName = Library.sanitize(
            "recovery-backup-${probe.identity.device ?: "device"}-${again.writePartition}-${System.currentTimeMillis()}.img",
        )
        val dest = library.fileFor(backupName)
        log.command("pull ${RecoveryCommands.BACKUP_REMOTE}")
        session.pull(RecoveryCommands.BACKUP_REMOTE, dest)
        library.refresh()
        val backup = library.get(backupName) ?: throw IOException("Backup was not stored in app storage; refusing to write.")
        val partitionBytes = size
        if (partitionBytes == null || partitionBytes <= 0L || backup.file.length() != partitionBytes) {
            val got = backup.file.length()
            backup.file.delete()
            library.refresh()
            throw IOException(
                "Backup of the current recovery is $got bytes; the partition is ${partitionBytes ?: "unknown"} bytes. " +
                    "The incomplete copy was discarded and nothing was written.",
            )
        }
        log.info("Saved current recovery as ${backup.name} (${humanSize(backup.size)}, sha256 ${FileDigest.sha256Hex(backup.file)}).")

        log.command(RecoveryCommands.suShell(RecoveryCommands.write(again)))
        val writeRun = session.su(RecoveryCommands.write(again), 180_000)
        session.su(RecoveryCommands.removeRemote(RecoveryCommands.IMAGE_REMOTE), 15_000)
        session.su(RecoveryCommands.removeRemote(RecoveryCommands.BACKUP_REMOTE), 15_000)
        if (!writeRun.ok) {
            throw IOException("dd of the new recovery failed: ${writeRun.output.take(240)}. The previous partition is in the library as ${backup.name}.")
        }
        log.info("Wrote ${image.name} to ${again.writePath}. Reboot to recovery is a separate button and was not run.")
        return RecoveryInstallResult(dryRun = false, backup = backup, plan = plan)
    }

    fun rebootToRecovery(session: RootSession) {
        log.command(RecoveryCommands.suShell(RecoveryCommands.rebootRecovery()))
        session.rebootRecovery()
        log.info("Reboot to recovery requested.")
    }
}
