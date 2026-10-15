package ai.rootforge.os.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.io.path.createTempFile

class FileDigestTest {
    @Test
    fun knownVector() {
        // SHA-256("abc") from FIPS 180-2.
        val hex = FileDigest.sha256Hex("abc".toByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
        assertEquals(hex, FileDigest.sha256Hex(ByteArrayInputStream("abc".toByteArray())))
        val f = createTempFile("digest", ".bin").toFile()
        f.writeText("abc")
        assertEquals(hex, FileDigest.sha256Hex(f))
    }

    @Test
    fun verifyIsCaseInsensitiveAndRejectsJunk() {
        val actual = FileDigest.sha256Hex("abc".toByteArray())
        assertTrue(FileDigest.verify(actual.uppercase(), actual).ok)
        assertFalse(FileDigest.verify("", actual).ok)
        assertEquals(FileDigest.EMPTY_EXPECTED, FileDigest.verify("   ", actual).message)
        assertEquals(FileDigest.BAD_EXPECTED, FileDigest.verify("deadbeef", actual).message)
        val bad = FileDigest.verify("00".repeat(32), actual)
        assertFalse(bad.ok)
        assertEquals(FileDigest.MISMATCH, bad.message)
    }
}
