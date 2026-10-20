package ai.rootforge.os.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory

class BytesTest {
    @Test
    fun readPrefixReturnsTheRequestedHead() {
        val data = ByteArray(64) { it.toByte() }
        val got = ByteArrayInputStream(data).readPrefix(8)
        assertEquals(8, got.size)
        assertEquals(data.copyOf(8).toList(), got.toList())
    }

    @Test
    fun readPrefixOnAShortStreamReturnsWhatIsThere() {
        val data = "ANDROID!".toByteArray()
        val got = ByteArrayInputStream(data).readPrefix(4096)
        assertEquals(data.toList(), got.toList())
    }

    @Test
    fun fileReadPrefixDoesNotRequireReadNBytes() {
        val dir = createTempDirectory("rf-bytes").toFile()
        val file = File(dir, "twrp.img")
        file.writeBytes("ANDROID!".toByteArray() + ByteArray(32))
        val got = file.readPrefix(8)
        assertEquals("ANDROID!", String(got, Charsets.ISO_8859_1))
        assertTrue(file.length() > 8)
    }
}
