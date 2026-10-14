package ai.rootforge.os.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbClassifierTest {
    private val adb = UsbIfaceInfo(0, 0xFF, 0x42, 0x01)
    private val fastboot = UsbIfaceInfo(1, 0xFF, 0x42, 0x03)
    private val vendor = UsbIfaceInfo(0, 0xFF, 0x00, 0x00)
    private val hid = UsbIfaceInfo(0, 0x03, 0x00, 0x00)

    @Test
    fun adbInterfaceWins() {
        val c = UsbClassifier.classify(0x18D1, 0x4EE7, listOf(hid, adb))
        assertEquals(TargetMode.ADB, c.mode)
        assertEquals(0, c.interfaceId)
    }

    @Test
    fun fastbootInterface() {
        val c = UsbClassifier.classify(0x18D1, 0x4EE0, listOf(fastboot))
        assertEquals(TargetMode.FASTBOOT, c.mode)
        assertEquals(1, c.interfaceId)
    }

    @Test
    fun edlIsIdentifiedByVidPidEvenWithoutSubclass() {
        val c = UsbClassifier.classify(0x05C6, 0x9008, listOf(vendor))
        assertEquals(TargetMode.EDL, c.mode)
        assertEquals(0, c.interfaceId)
    }

    @Test
    fun edlWinsOverAConflictingInterface() {
        val c = UsbClassifier.classify(UsbClassifier.QUALCOMM_VID, UsbClassifier.EDL_PID, listOf(adb))
        assertEquals(TargetMode.EDL, c.mode)
    }

    @Test
    fun unknownWhenNothingMatches() {
        val c = UsbClassifier.classify(0x1234, 0x5678, listOf(hid))
        assertEquals(TargetMode.UNKNOWN, c.mode)
        assertNull(c.interfaceId)
    }
}
