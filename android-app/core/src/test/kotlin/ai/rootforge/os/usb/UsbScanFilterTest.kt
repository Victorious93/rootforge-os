package ai.rootforge.os.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbScanFilterTest {
    private val adb = UsbIfaceInfo(0, 0xFF, 0x42, 0x01)
    private val fastboot = UsbIfaceInfo(1, 0xFF, 0x42, 0x03)
    private val hid = UsbIfaceInfo(0, 0x03, 0x00, 0x00)
    private val vendor = UsbIfaceInfo(0, 0xFF, 0x00, 0x00)

    @Test
    fun adbAndFastbootAndEdlAreFlashTargets() {
        assertTrue(UsbScanFilter.isFlashTarget(0x18D1, 0x4EE7, listOf(hid, adb)))
        assertTrue(UsbScanFilter.isFlashTarget(0x18D1, 0x4EE0, listOf(fastboot)))
        assertTrue(UsbScanFilter.isFlashTarget(UsbClassifier.QUALCOMM_VID, UsbClassifier.EDL_PID, listOf(vendor)))
    }

    @Test
    fun hidAndUnknownGadgetsAreFilteredOut() {
        assertFalse(UsbScanFilter.isFlashTarget(0x046D, 0xC52B, listOf(hid)))
        assertFalse(UsbScanFilter.isFlashTarget(0x1234, 0x5678, emptyList()))
        assertFalse(UsbScanFilter.isFlashTarget(TargetMode.UNKNOWN))
        assertTrue(UsbScanFilter.isFlashTarget(TargetMode.ADB))
    }

    @Test
    fun partitionKeepsFlashTargetsAndListsTheRestAsIgnored() {
        val pixel = target(0x18D1, 0x4EE7, TargetMode.ADB, "Pixel 8")
        val mouse = target(0x046D, 0xC52B, TargetMode.UNKNOWN, "USB Receiver")
        val scan = UsbScanFilter.partition(listOf(mouse, pixel))
        assertEquals(listOf(pixel), scan.flashTargets)
        assertEquals(listOf(mouse), scan.ignored)
        val lines = UsbScanFilter.describe(scan)
        assertTrue(lines.any { it.contains("18d1:4ee7") && it.contains("ADB") })
        assertTrue(lines.any { it.startsWith("Ignored 046d:c52b") })
    }

    @Test
    fun emptyScanSaysSo() {
        val scan = UsbScanFilter.partition(listOf(target(0x1234, 0x5678, TargetMode.UNKNOWN, null)))
        assertTrue(scan.isEmpty)
        assertEquals("No ADB, fastboot or EDL device attached.", UsbScanFilter.describe(scan).first())
    }

    @Test
    fun aDeviceNeedingPermissionIsStillAFlashTarget() {
        val locked = target(0x18D1, 0x4EE7, TargetMode.ADB, "Pixel 8", hasPermission = false)
        val scan = UsbScanFilter.partition(listOf(locked))
        assertEquals(listOf(locked), scan.flashTargets)
        assertTrue(UsbScanFilter.describe(scan).single().contains("needs USB permission"))
    }

    private fun target(
        vid: Int,
        pid: Int,
        mode: TargetMode,
        product: String?,
        hasPermission: Boolean = true,
    ): AttachedTarget = AttachedTarget(
        deviceName = "/dev/bus/usb/001/${pid and 0xFF}",
        vendorId = vid,
        productId = pid,
        mode = mode,
        interfaceId = if (mode == TargetMode.UNKNOWN) null else 0,
        hasPermission = hasPermission,
        serial = null,
        product = product,
        manufacturer = null,
    )
}
