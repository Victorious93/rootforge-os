package ai.rootforge.os.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootImageParserTest {
    @Test
    fun aV2HeaderIsParsed() {
        val head = ByteArray(2048)
        "ANDROID!".toByteArray().copyInto(head)
        writeInt(head, 8, 0x1000)
        writeInt(head, 16, 0x2000)
        writeInt(head, 36, 4096)
        writeInt(head, 40, 2)
        writeInt(head, 44, packOs(13, 0, 0, 2024, 6))
        "console=tty0".toByteArray().copyInto(head, 64)
        val tail = ByteArray(64)
        "AVBf".toByteArray().copyInto(tail)
        val result = BootImageParser.parse(head, tail, 8_000_000)
        val info = (result as BootImageParser.Result.Ok).info
        assertEquals(2, info.headerVersion)
        assertEquals(0x1000L, info.kernelSize)
        assertEquals(0x2000L, info.ramdiskSize)
        assertEquals(4096, info.pageSize)
        assertEquals("13.0.0", info.osVersion)
        assertEquals("2024-06", info.osPatchLevel)
        assertTrue(info.cmdline.contains("console=tty0"))
        assertTrue(info.hasAvbFooter)
    }

    @Test
    fun vendorBootIsCalledOut() {
        val head = ByteArray(2048)
        "VNDRBOOT".toByteArray().copyInto(head)
        val r = BootImageParser.parse(head, ByteArray(0), 100)
        assertTrue(r is BootImageParser.Result.Invalid)
        assertTrue((r as BootImageParser.Result.Invalid).reason.contains("vendor_boot"))
    }

    @Test
    fun garbageIsRejected() {
        val r = BootImageParser.parse(ByteArray(2048), ByteArray(0), 100)
        assertTrue((r as BootImageParser.Result.Invalid).reason.contains("ANDROID!"))
    }

    @Test
    fun osVersionZeroMeansUnspecified() {
        assertEquals(null to null, BootImageParser.decodeOsVersion(0))
    }

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun packOs(a: Int, b: Int, c: Int, year: Int, month: Int): Int {
        val v = ((a and 0x7F) shl 14) or ((b and 0x7F) shl 7) or (c and 0x7F)
        val p = (((year - 2000) and 0x7F) shl 4) or (month and 0xF)
        return (v shl 11) or p
    }
}
