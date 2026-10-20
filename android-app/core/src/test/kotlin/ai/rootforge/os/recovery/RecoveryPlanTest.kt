package ai.rootforge.os.recovery

import ai.rootforge.os.core.FileDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPlanTest {
    private val magic = "ANDROID!".toByteArray() + ByteArray(8)
    private val zipHead = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(8)
    private val layout = RecoveryLayout(
        slotScheme = SlotScheme.A_ONLY,
        kind = RecoveryKind.DEDICATED,
        currentSlot = null,
        byNameDir = "/dev/block/by-name",
        partitionNames = setOf("recovery"),
        writePartition = "recovery",
        writePath = "/dev/block/by-name/recovery",
        allowedFlows = setOf(InstallFlow.FLASH_IMAGE, InstallFlow.INSTALLER_ZIP, InstallFlow.TEMPORARY_BOOT),
        notes = emptyList(),
    )
    private val ramdisk = layout.copy(
        kind = RecoveryKind.IN_BOOT,
        writePartition = null,
        writePath = null,
        allowedFlows = setOf(InstallFlow.INSTALLER_ZIP, InstallFlow.TEMPORARY_BOOT),
        notes = listOf("Recovery lives in the boot ramdisk"),
        partitionNames = setOf("boot_a", "boot_b"),
    )

    @Test
    fun dryRunHasNoPhraseAndIsAllowed() {
        val plan = RecoveryPlanner.plan(
            layout, rooted = true, "twrp.img", 1024, magic,
            sha256 = "ab".repeat(32), dryRun = true, partitionSize = 64L * 1024 * 1024,
        )
        assertTrue(plan.allowed)
        assertNull(plan.confirmationPhrase)
        assertTrue(plan.lines.any { it.contains("dry-run") })
    }

    @Test
    fun writeRequiresFlashRecoveryPhrase() {
        val plan = RecoveryPlanner.plan(
            layout, rooted = true, "twrp.img", 1024, magic,
            sha256 = "ab".repeat(32), dryRun = false, partitionSize = 64L * 1024 * 1024,
        )
        assertTrue(plan.allowed)
        assertEquals("FLASH RECOVERY", plan.confirmationPhrase)
    }

    @Test
    fun unrootedDeviceIsBlocked() {
        val plan = RecoveryPlanner.plan(
            layout, rooted = false, "twrp.img", 1024, magic,
            sha256 = "ab".repeat(32), dryRun = false,
        )
        assertFalse(plan.allowed)
        assertTrue(plan.blockers.any { it.contains("su") })
    }

    @Test
    fun zipIsNeverWrittenWithDd() {
        val plan = RecoveryPlanner.plan(
            layout, rooted = true, "pbrp.zip", 2048, zipHead,
            sha256 = "cd".repeat(32), dryRun = false,
        )
        assertFalse(plan.allowed)
        assertTrue(plan.blockers.any { it.contains("zip") })
        assertNull(plan.confirmationPhrase)
    }

    @Test
    fun imageOnRamdiskDeviceIsBlockedFromWriting() {
        val plan = RecoveryPlanner.plan(
            ramdisk, rooted = true, "twrp.img", 1024, magic,
            sha256 = "ab".repeat(32), dryRun = false,
        )
        assertFalse(plan.allowed)
        assertTrue(plan.blockers.any { it.contains("will not write boot") || it.contains("No dedicated recovery") })
    }

    @Test
    fun publishedMd5MismatchBlocks() {
        val actual = FileDigest.md5Hex("ANDROID!".toByteArray())
        val plan = RecoveryPlanner.plan(
            layout, rooted = true, "twrp.img", 1024, magic,
            sha256 = "ab".repeat(32), md5 = actual, publishedMd5 = "00".repeat(16), dryRun = true,
        )
        assertFalse(plan.allowed)
        assertEquals(FileDigest.MD5_MISMATCH, plan.blockers.single { it == FileDigest.MD5_MISMATCH })
    }

    @Test
    fun imageLargerThanPartitionIsBlocked() {
        val plan = RecoveryPlanner.plan(
            layout, rooted = true, "twrp.img", 90L * 1024 * 1024, magic,
            sha256 = "ab".repeat(32), dryRun = false, partitionSize = 64L * 1024 * 1024,
        )
        assertFalse(plan.allowed)
        assertTrue(plan.blockers.any { it.contains("larger than the recovery partition") })
    }

    @Test
    fun writeIsBlockedWhenPartitionSizeIsUnknown() {
        val plan = RecoveryPlanner.plan(
            layout, rooted = true, "twrp.img", 90L * 1024 * 1024, magic,
            sha256 = "ab".repeat(32), dryRun = false, partitionSize = null,
        )
        assertFalse(plan.allowed)
        assertTrue(plan.blockers.any { it.contains("Could not read the recovery partition size") })
    }

    @Test
    fun payloadDetectsBootMagicAndZip() {
        assertEquals(RecoveryPayload.IMAGE, RecoveryPlanner.payloadOf("twrp.img", magic))
        assertEquals(RecoveryPayload.ZIP, RecoveryPlanner.payloadOf("pbrp.zip", zipHead))
        assertEquals(RecoveryPayload.OTHER, RecoveryPlanner.payloadOf("notes.txt", "hello".toByteArray()))
    }
}
