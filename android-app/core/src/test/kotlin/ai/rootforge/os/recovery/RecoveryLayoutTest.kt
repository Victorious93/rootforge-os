package ai.rootforge.os.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryLayoutTest {
    private val aOnlyProps = mapOf(
        "ro.product.device" to "olive",
        "ro.build.product" to "olive",
        "ro.product.name" to "olive",
        "ro.product.model" to "Redmi 8",
        "ro.build.ab_update" to "false",
    )

    private val abProps = mapOf(
        "ro.product.device" to "shiba",
        "ro.build.product" to "shiba",
        "ro.boot.slot_suffix" to "_a",
        "ro.build.ab_update" to "true",
    )

    @Test
    fun identityReadsDeviceAndBuildProduct() {
        val id = RecoveryIdentity.fromProps(aOnlyProps)
        assertEquals("olive", id.device)
        assertEquals("olive", id.product)
        assertEquals(listOf("olive"), id.lookupNames())
        assertNull(id.slot)
        assertEquals(false, id.abUpdate)
        assertNull(id.serial)
    }

    @Test
    fun identityPinsSerialAndRefusesADifferentPhone() {
        val probed = RecoveryIdentity.fromProps(
            aOnlyProps + mapOf("ro.serialno" to "OLIVE1234"),
        )
        val same = RecoveryIdentity.fromProps(
            aOnlyProps + mapOf("ro.serialno" to "OLIVE1234"),
        )
        val otherSerial = RecoveryIdentity.fromProps(
            aOnlyProps + mapOf("ro.serialno" to "OTHER9999"),
        )
        val missingSerial = RecoveryIdentity.fromProps(aOnlyProps)
        assertEquals("OLIVE1234", probed.serial)
        assertTrue(probed.samePhone(same))
        assertFalse(probed.samePhone(otherSerial))
        assertFalse(probed.samePhone(missingSerial))
        assertFalse(missingSerial.samePhone(probed))
    }

    @Test
    fun blankSerialnoDoesNotHideBootSerial() {
        val blank = RecoveryIdentity.fromProps(
            aOnlyProps + mapOf("ro.serialno" to "", "ro.boot.serialno" to "BOOT9999"),
        )
        val unknown = RecoveryIdentity.fromProps(
            aOnlyProps + mapOf("ro.serialno" to "unknown", "ro.boot.serialno" to "BOOT9999"),
        )
        assertEquals("BOOT9999", blank.serial)
        assertEquals("BOOT9999", unknown.serial)
        assertTrue(blank.samePhone(unknown))
    }

    @Test
    fun getpropDumpIsParsed() {
        val dump = """
            [ro.product.device]: [olive]
            [ro.build.product]: [olive]
            [ro.boot.slot_suffix]: [_b]
            [ro.serialno]: [ABC123XYZ]
        """.trimIndent()
        val id = RecoveryIdentity.fromProps(RecoveryIdentity.parseGetprop(dump))
        assertEquals("olive", id.device)
        assertEquals("olive", id.product)
        assertEquals("b", id.slot)
        assertEquals("ABC123XYZ", id.serial)
    }

    @Test
    fun aOnlyDedicatedRecoveryIsWritable() {
        val listing = """
            DIR /dev/block/by-name
            boot
            recovery
            system
        """.trimIndent()
        val layout = RecoveryLayoutDetector.detect(RecoveryIdentity.fromProps(aOnlyProps), listing)
        assertEquals(SlotScheme.A_ONLY, layout.slotScheme)
        assertEquals(RecoveryKind.DEDICATED, layout.kind)
        assertEquals("recovery", layout.writePartition)
        assertEquals("/dev/block/by-name/recovery", layout.writePath)
        assertTrue(layout.canWriteImage)
        assertTrue(InstallFlow.FLASH_IMAGE in layout.allowedFlows)
    }

    @Test
    fun abDedicatedRecoveryUsesTheCurrentSlot() {
        val listing = """
            DIR /dev/block/by-name
            boot_a
            boot_b
            recovery_a
            recovery_b
        """.trimIndent()
        val layout = RecoveryLayoutDetector.detect(RecoveryIdentity.fromProps(abProps), listing)
        assertEquals(SlotScheme.A_B, layout.slotScheme)
        assertEquals("recovery_a", layout.writePartition)
        assertEquals("/dev/block/by-name/recovery_a", layout.writePath)
        assertTrue(layout.canWriteImage)
    }

    @Test
    fun abWithoutKnownSlotDoesNotGuess() {
        val listing = """
            DIR /dev/block/by-name
            recovery_a
            recovery_b
        """.trimIndent()
        val props = abProps - "ro.boot.slot_suffix"
        val layout = RecoveryLayoutDetector.detect(RecoveryIdentity.fromProps(props), listing)
        assertEquals(RecoveryKind.DEDICATED, layout.kind)
        assertNull(layout.writePath)
        assertFalse(layout.canWriteImage)
        assertTrue(layout.notes.any { it.contains("will not guess") })
    }

    @Test
    fun vendorBootIsNotWritten() {
        val listing = """
            DIR /dev/block/by-name
            boot_a
            boot_b
            vendor_boot_a
            vendor_boot_b
        """.trimIndent()
        val layout = RecoveryLayoutDetector.detect(RecoveryIdentity.fromProps(abProps), listing)
        assertEquals(RecoveryKind.IN_VENDOR_BOOT, layout.kind)
        assertNull(layout.writePath)
        assertFalse(InstallFlow.FLASH_IMAGE in layout.allowedFlows)
        assertTrue(InstallFlow.INSTALLER_ZIP in layout.allowedFlows)
        assertTrue(InstallFlow.TEMPORARY_BOOT in layout.allowedFlows)
        assertTrue(layout.notes.any { it.contains("will not write boot") })
    }

    @Test
    fun recoveryInBootIsNotWritten() {
        val listing = """
            DIR /dev/block/by-name
            boot_a
            boot_b
            vbmeta_a
        """.trimIndent()
        val layout = RecoveryLayoutDetector.detect(RecoveryIdentity.fromProps(abProps), listing)
        assertEquals(RecoveryKind.IN_BOOT, layout.kind)
        assertNull(layout.writePath)
        assertFalse(layout.canWriteImage)
    }

    @Test
    fun emptyListingIsUnknownAndNotWritable() {
        val layout = RecoveryLayoutDetector.detect(RecoveryIdentity.fromProps(aOnlyProps), "")
        assertEquals(RecoveryKind.UNKNOWN, layout.kind)
        assertNull(layout.writePath)
        assertTrue(InstallFlow.TEMPORARY_BOOT in layout.allowedFlows)
    }

    @Test
    fun lsErrorsAreIgnoredAndBootdeviceIsUsed() {
        val listing = """
            DIR /dev/block/by-name
            ls: cannot access '/dev/block/by-name': No such file or directory
            DIR /dev/block/bootdevice/by-name
            recovery
            boot
        """.trimIndent()
        val layout = RecoveryLayoutDetector.detect(RecoveryIdentity.fromProps(aOnlyProps), listing)
        assertEquals("/dev/block/bootdevice/by-name/recovery", layout.writePath)
    }

    @Test
    fun parseListingsDropsPathEscapeNames() {
        val listing = """
            DIR /dev/block/by-name
            recovery
            ../boot
            recovery;reboot
        """.trimIndent()
        val names = RecoveryLayoutDetector.parseListings(listing)[RecoveryLayoutDetector.BY_NAME]!!
        assertEquals(setOf("recovery"), names)
    }
}
