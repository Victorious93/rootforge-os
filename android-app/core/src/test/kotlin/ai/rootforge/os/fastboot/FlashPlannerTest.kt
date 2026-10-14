package ai.rootforge.os.fastboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashPlannerTest {
    private val bootMagic = "ANDROID!".toByteArray() + ByteArray(8)

    @Test
    fun aValidBootImageOnAnUnlockedDeviceIsAllowed() {
        val plan = FlashPlanner.plan("boot", "a", "boot.img", 8 * 1024 * 1024, bootMagic, 256L * 1024 * 1024, true)
        assertTrue(plan.allowed)
        assertEquals(Risk.NORMAL, plan.risk)
        assertEquals("FLASH boot", plan.confirmationPhrase)
    }

    @Test
    fun aNonBootImageOnBootIsRefused() {
        val plan = FlashPlanner.plan("boot", "a", "random.bin", 1024, "NOTABOOT".toByteArray(), null, true)
        assertFalse(plan.allowed)
        assert(plan.blockers.any { it.contains("ANDROID!") })
    }

    @Test
    fun aLockedBootloaderBlocksTheWrite() {
        val plan = FlashPlanner.plan("boot", "a", "boot.img", 1024, bootMagic, null, false)
        assertFalse(plan.allowed)
        assert(plan.blockers.any { it.contains("locked") })
    }

    @Test
    fun xblIsSevereAndStillNeedsAPlausibleName() {
        val plan = FlashPlanner.plan("xbl", null, "xbl.img", 1024, ByteArray(8), null, true)
        assertTrue(plan.allowed)
        assertEquals(Risk.SEVERE, plan.risk)
        assert(plan.lines.any { it.contains("brick") })
    }

    @Test
    fun userdataIsElevated() {
        val plan = FlashPlanner.plan("userdata", null, "userdata.img", 1024, ByteArray(8), null, true)
        assertEquals(Risk.ELEVATED, plan.risk)
        assert(plan.lines.any { it.contains("erases") })
    }

    @Test
    fun anOversizedRawImageIsRefusedWhenMaxDownloadIsKnown() {
        val plan = FlashPlanner.plan("system", null, "system.img", 200, ByteArray(8), 100, true)
        assertFalse(plan.allowed)
        assert(plan.blockers.any { it.contains("max-download-size") })
    }

    @Test
    fun aSparseImageMayExceedMaxDownload() {
        val sparse = byteArrayOf(0x3A, 0xFF.toByte(), 0x26, 0xED.toByte())
        val plan = FlashPlanner.plan("system", null, "system.img", 200, sparse, 100, true)
        assertTrue(plan.allowed)
    }

    @Test
    fun aPathLikePartitionNameIsRejected() {
        val plan = FlashPlanner.plan("../boot", null, "boot.img", 1024, bootMagic, null, true)
        assertFalse(plan.allowed)
    }

    @Test
    fun parseVarLineStripsTheBootloaderPrefix() {
        assertEquals("unlocked" to "yes", FlashPlanner.parseVarLine("(bootloader) unlocked: yes"))
        assertEquals(256L * 1024 * 1024, FlashPlanner.parseNumber("0x10000000"))
    }
}
