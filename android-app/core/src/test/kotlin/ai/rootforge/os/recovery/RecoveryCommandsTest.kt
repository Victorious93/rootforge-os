package ai.rootforge.os.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCommandsTest {
    private val dedicated = RecoveryLayout(
        slotScheme = SlotScheme.A_ONLY,
        kind = RecoveryKind.DEDICATED,
        currentSlot = null,
        byNameDir = "/dev/block/by-name",
        partitionNames = setOf("recovery", "boot"),
        writePartition = "recovery",
        writePath = "/dev/block/by-name/recovery",
        allowedFlows = setOf(InstallFlow.FLASH_IMAGE, InstallFlow.INSTALLER_ZIP, InstallFlow.TEMPORARY_BOOT),
        notes = emptyList(),
    )

    @Test
    fun backupAndWriteTargetOnlyRecovery() {
        val backup = RecoveryCommands.backup(dedicated)
        val write = RecoveryCommands.write(dedicated)
        assertEquals(
            "dd if='/dev/block/by-name/recovery' of='/data/local/tmp/rf-recovery-backup.img' bs=4096 status=none",
            backup,
        )
        assertEquals(
            "dd if='/data/local/tmp/rf-recovery.img' of='/dev/block/by-name/recovery' bs=4096 conv=fsync status=none",
            write,
        )
        assertFalse(backup.contains("boot"))
        assertFalse(write.contains("boot"))
        assertTrue(RecoveryCommands.suShell(write).startsWith("su -c "))
        assertEquals(listOf("su", "-c", write), RecoveryCommands.suArgv(write))
    }

    @Test
    fun abWriteUsesRecoveryB() {
        val layout = dedicated.copy(
            slotScheme = SlotScheme.A_B,
            currentSlot = "b",
            writePartition = "recovery_b",
            writePath = "/dev/block/by-name/recovery_b",
            partitionNames = setOf("recovery_a", "recovery_b", "boot_a", "boot_b"),
        )
        val write = RecoveryCommands.write(layout)
        assertTrue(write.contains("/dev/block/by-name/recovery_b"))
        assertFalse(write.contains("boot"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun bootPathIsRejected() {
        RecoveryCommands.requireWritePath(
            dedicated.copy(writePartition = "boot", writePath = "/dev/block/by-name/boot"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun vendorBootPathIsRejected() {
        RecoveryCommands.requireWritePath(
            dedicated.copy(writePartition = "vendor_boot", writePath = "/dev/block/by-name/vendor_boot"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingWritePathIsRejected() {
        RecoveryCommands.requireWritePath(dedicated.copy(writePath = null, writePartition = null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathOutsideByNameIsRejected() {
        RecoveryCommands.requireWritePath(
            dedicated.copy(byNameDir = "/dev/block", writePath = "/dev/block/recovery", writePartition = "recovery"),
        )
    }

    @Test
    fun quotesHideMetacharactersInPaths() {
        val quoted = RecoveryCommands.shSingleQuote("/tmp/it's")
        assertEquals("'/tmp/it'\\''s'", quoted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun newlineInQuoteIsRejected() {
        RecoveryCommands.shSingleQuote("a\nb")
    }

    @Test
    fun getpropDumpIsAReadOnlyCommand() {
        assertEquals("getprop", RecoveryCommands.getpropDump())
        assertFalse(RecoveryCommands.getpropDump().contains("dd"))
        assertFalse(RecoveryCommands.getpropDump().contains("reboot"))
    }

    @Test
    fun blockdevSizeParsesTheFirstNumber() {
        assertEquals(67_108_864L, RecoveryCommands.parseBlockdevSize("67108864\n"))
        assertEquals(null, RecoveryCommands.parseBlockdevSize("not a number"))
    }
}
