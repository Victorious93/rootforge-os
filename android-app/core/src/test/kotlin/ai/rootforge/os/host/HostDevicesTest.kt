package ai.rootforge.os.host

import ai.rootforge.os.usb.TargetMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostDevicesTest {
    @Test
    fun adbDevicesMinusL() {
        val text = """
            List of devices attached
            PIXELTEST      device usb:1-1 product:shiba model:Pixel_8 device:shiba transport_id:1
            OFF1           offline
            UNAUTH         unauthorized usb:1-2
        """.trimIndent()
        val list = HostDevices.parseAdb(text)
        assertEquals(3, list.size)
        assertEquals("PIXELTEST", list[0].serial)
        assertEquals(TargetMode.ADB, list[0].mode)
        assertEquals("Pixel 8", list[0].product)
        assertTrue(list[0].hasPermission)
        assertFalse(list[1].hasPermission)
        assertFalse(list[2].hasPermission)
    }

    @Test
    fun fastbootDevices() {
        val list = HostDevices.parseFastboot("ABC123\tfastboot\nXYZ\tfastbootd\n")
        assertEquals(2, list.size)
        assertEquals(TargetMode.FASTBOOT, list[0].mode)
        assertEquals("ABC123", list[0].serial)
    }

    @Test
    fun lsusbEdl() {
        val list = HostDevices.parseLsusbEdl("Bus 001 Device 004: ID 05c6:9008 Qualcomm, Inc. Gobi Wireless Modem (QDL mode)")
        assertEquals(1, list.size)
        assertEquals(TargetMode.EDL, list[0].mode)
        assertEquals("/dev/bus/usb/001/004", list[0].deviceName)
    }

    @Test
    fun mergePrefersFastbootWhenSerialOverlaps() {
        val adb = HostDevices.parseAdb("S1 device\n")
        val fb = HostDevices.parseFastboot("S1\tfastboot\n")
        val merged = HostDevices.merge(adb, fb, emptyList())
        assertEquals(1, merged.size)
        assertEquals(TargetMode.FASTBOOT, merged.single().mode)
    }
}
