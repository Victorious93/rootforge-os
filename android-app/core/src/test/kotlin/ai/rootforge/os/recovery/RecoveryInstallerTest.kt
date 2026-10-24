package ai.rootforge.os.recovery

import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.core.Library
import ai.rootforge.os.core.OutputLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class RecoveryInstallerTest {
    private val listing = """
        DIR /dev/block/by-name
        recovery
        boot
    """.trimIndent()
    private val props = mapOf(
        "ro.product.device" to "olive",
        "ro.build.product" to "olive",
    )

    @Test
    fun uidZeroCountsAsRoot() {
        val installer = installer()
        assertTrue(installer.parseRoot("uid=0(root) gid=0(root)", 0))
        assertFalse(installer.parseRoot("uid=2000(shell)", 0))
        assertFalse(installer.parseRoot("su: not found", 127))
    }

    @Test
    fun dryRunDoesNotPushOrWrite() = runBlocking {
        val env = env()
        val probe = env.installer.probe(RecoverySource.THIS_PHONE, props, listing, "uid=0(root)", 0)
        val image = env.library.importStream("twrp.img", "ANDROID!".toByteArray().plus(ByteArray(32)).inputStream())
        val session = FakeRootSession(listing)
        val result = env.installer.install(session, probe, image, dryRun = true)
        assertTrue(result.dryRun)
        assertNull(result.backup)
        assertFalse(session.pushed)
        assertTrue(session.commands.none { it.startsWith("dd if='/data/local/tmp/rf-recovery.img'") })
        assertTrue(session.commands.any { it.startsWith("ls -l ") })
        assertFalse(session.rebooted)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun writeBacksUpThenDdsAfterTypedPhrase() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        val probe = env.installer.probe(RecoverySource.ADB_TARGET, props, listing, "uid=0(root)", 0)
        val image = env.library.importStream("twrp.img", "ANDROID!".toByteArray().plus(ByteArray(32)).inputStream())
        val session = FakeRootSession(listing, partitionBytes = 4096)
        val pending = async {
            env.installer.install(session, probe, image, dryRun = false)
        }
        val request = env.approvals.pending.value.single()
        assertEquals("FLASH RECOVERY", request.phrase)
        assertTrue(request.lines.any { it.contains("WRITE recovery") })
        assertTrue(env.approvals.answer(request.id, true, "FLASH RECOVERY"))
        val result = pending.await()
        assertFalse(result.dryRun)
        assertTrue(result.backup != null)
        assertTrue(session.pushed)
        assertTrue(session.commands.any { it.startsWith("dd if='/dev/block/by-name/recovery'") })
        assertTrue(session.commands.any { it.startsWith("dd if='/data/local/tmp/rf-recovery.img' of='/dev/block/by-name/recovery'") })
        assertFalse(session.rebooted)
        assertEquals(4096L, result.backup!!.file.length())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deniedConfirmationWritesNothing() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        val probe = env.installer.probe(RecoverySource.THIS_PHONE, props, listing, "uid=0(root)", 0)
        val image = env.library.importStream("twrp.img", "ANDROID!".toByteArray().plus(ByteArray(32)).inputStream())
        val session = FakeRootSession(listing, partitionBytes = 64L * 1024 * 1024)
        val pending = async {
            runCatching { env.installer.install(session, probe, image, dryRun = false) }
        }
        env.approvals.answer(env.approvals.pending.value.single().id, false)
        val result = pending.await()
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not confirmed"))
        assertFalse(session.pushed)
        assertTrue(session.commands.none { it.contains("dd if='/data/local/tmp/rf-recovery.img'") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun missingExitStatusDoesNotCountAsASuccessfulBackup() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        val probe = env.installer.probe(RecoverySource.ADB_TARGET, props, listing, "uid=0(root)", 0)
        val image = env.library.importStream("twrp.img", "ANDROID!".toByteArray().plus(ByteArray(32)).inputStream())
        val session = FakeRootSession(listing, partitionBytes = 4096, backupExit = null)
        val pending = async {
            runCatching { env.installer.install(session, probe, image, dryRun = false) }
        }
        assertTrue(env.approvals.answer(env.approvals.pending.value.single().id, true, "FLASH RECOVERY"))
        val result = pending.await()
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Backup of the current recovery failed"))
        assertTrue(session.commands.none { it.startsWith("dd if='/data/local/tmp/rf-recovery.img'") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun shortBackupIsDiscardedAndNothingIsWritten() = runTest(UnconfinedTestDispatcher()) {
        val env = env()
        val probe = env.installer.probe(RecoverySource.ADB_TARGET, props, listing, "uid=0(root)", 0)
        val image = env.library.importStream("twrp.img", "ANDROID!".toByteArray().plus(ByteArray(32)).inputStream())
        val session = FakeRootSession(listing, partitionBytes = 4096, pullBytes = 16)
        val pending = async {
            runCatching { env.installer.install(session, probe, image, dryRun = false) }
        }
        assertTrue(env.approvals.answer(env.approvals.pending.value.single().id, true, "FLASH RECOVERY"))
        val result = pending.await()
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message, message.contains("16 bytes") && message.contains("nothing was written"))
        assertTrue(session.commands.none { it.startsWith("dd if='/data/local/tmp/rf-recovery.img'") })
        assertTrue(env.library.list().none { it.name.startsWith("recovery-backup-") })
    }

    @Test
    fun aMissingShellExitIsNotSuccess() {
        assertFalse(RootShellResult("partial dd", null).ok)
        assertFalse(RootShellResult("denied", 1).ok)
        assertTrue(RootShellResult("", 0).ok)
    }

    @Test
    fun writeIsRefusedWhenBlockdevSizeIsUnknown() = runBlocking {
        val env = env()
        val probe = env.installer.probe(RecoverySource.THIS_PHONE, props, listing, "uid=0(root)", 0)
        val image = env.library.importStream("twrp.img", "ANDROID!".toByteArray().plus(ByteArray(32)).inputStream())
        val session = FakeRootSession(listing, partitionBytes = null)
        val result = runCatching { env.installer.install(session, probe, image, dryRun = false) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Could not read the recovery partition size"))
        assertFalse(session.pushed)
        assertTrue(session.commands.none { it.contains("dd if='/data/local/tmp/rf-recovery.img'") })
        assertTrue(env.approvals.pending.value.isEmpty())
    }

    @Test
    fun bootLayoutNeverProducesAWriteCommand() {
        val bootListing = """
            DIR /dev/block/by-name
            boot_a
            boot_b
        """.trimIndent()
        val env = env()
        val probe = env.installer.probe(
            RecoverySource.THIS_PHONE,
            mapOf("ro.product.device" to "shiba", "ro.boot.slot_suffix" to "_a", "ro.build.ab_update" to "true"),
            bootListing,
            "uid=0(root)",
            0,
        )
        assertNull(probe.layout.writePath)
        val thrown = runCatching { RecoveryCommands.write(probe.layout) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun rebootIsASeparateCall() {
        val env = env()
        val session = FakeRootSession(listing)
        env.installer.rebootToRecovery(session)
        assertTrue(session.rebooted)
        assertFalse(session.pushed)
        assertTrue(session.commands.none { it.contains("dd ") })
    }

    private fun installer(): RecoveryInstaller {
        val dir = createTempDirectory("rf-rec").toFile()
        return RecoveryInstaller(OutputLog(), Library(File(dir, "lib")), ApprovalCenter())
    }

    private fun env(): Env {
        val dir = createTempDirectory("rf-rec").toFile()
        val approvals = ApprovalCenter()
        val library = Library(File(dir, "lib"))
        return Env(RecoveryInstaller(OutputLog(), library, approvals), library, approvals)
    }

    private data class Env(val installer: RecoveryInstaller, val library: Library, val approvals: ApprovalCenter)

    private class FakeRootSession(
        private val listing: String,
        private val partitionBytes: Long? = 64L * 1024 * 1024,
        private val backupExit: Int? = 0,
        private val pullBytes: Long? = null,
    ) : RootSession {
        val commands = mutableListOf<String>()
        var pushed = false
        var rebooted = false
        override fun su(command: String, timeoutMs: Long): RootShellResult {
            commands += command
            return when {
                command.startsWith("blockdev") ->
                    RootShellResult(partitionBytes?.toString() ?: "blockdev: cannot open", if (partitionBytes == null) 1 else 0)
                command.startsWith("ls -l") -> RootShellResult("brw------- 1 root root 259, 5 recovery", 0)
                command == RecoveryLayoutDetector.LIST_SCRIPT -> RootShellResult(listing, 0)
                command.startsWith("dd if='/dev/block/by-name/") -> RootShellResult("", backupExit)
                command.startsWith("dd if='/data/local/tmp/rf-recovery.img'") -> RootShellResult("", 0)
                command.startsWith("chmod") || command.startsWith("rm -f") -> RootShellResult("", 0)
                else -> RootShellResult("", 0)
            }
        }

        override fun push(local: File, remote: String) {
            pushed = true
        }

        override fun pull(remote: String, local: File) {
            local.parentFile?.mkdirs()
            val n = pullBytes ?: partitionBytes ?: 0L
            local.outputStream().use { out ->
                val chunk = ByteArray(8192)
                var left = n
                while (left > 0) {
                    val w = minOf(left, chunk.size.toLong()).toInt()
                    out.write(chunk, 0, w)
                    left -= w
                }
            }
        }

        override fun rebootRecovery() {
            rebooted = true
        }
    }
}
