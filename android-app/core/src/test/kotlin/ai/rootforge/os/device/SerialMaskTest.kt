package ai.rootforge.os.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialMaskTest {
    @Test
    fun lastFourCharactersStayVisible() {
        assertEquals("••••TEST", SerialMask.mask("PIXELTEST"))
        assertEquals("••••PIX8", SerialMask.mask("3B051FD0H2PIX8"))
        assertEquals("••••1234", SerialMask.mask("1234"))
        assertEquals("••••abc", SerialMask.mask("abc"))
        assertEquals("unknown", SerialMask.mask(null))
        assertEquals("unknown", SerialMask.mask("  "))
    }

    @Test
    fun replaceKnownDoesNotLeaveTheFullSerial() {
        val serials = listOf("PIXELTEST", "3B051FD0H2PIX8", "R5CT70TESTSAMS")
        val raw = "connected PIXELTEST then 3B051FD0H2PIX8 serialno: R5CT70TESTSAMS"
        val masked = SerialMask.replaceKnown(raw, serials)
        for (s in serials) {
            assertFalse(masked.contains(s))
            assertTrue(masked.contains(SerialMask.mask(s)))
        }
        assertEquals("connected ••••TEST then ••••PIX8 serialno: ••••SAMS", masked)
    }

    @Test
    fun shortSerialsAreNotUsedAsAGlobalReplace() {
        val text = SerialMask.replaceKnown("product: test", listOf("test"))
        assertEquals("product: test", text)
    }
}
