package ai.rootforge.os.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class AdbProtocolTest {
    @Test
    fun headerRoundTrip() {
        val payload = "host::".toByteArray()
        val encoded = AdbProtocol.encodeHeader(AdbProtocol.A_CNXN, AdbProtocol.VERSION, 4096, payload)
        assertEquals(24, encoded.size)
        val h = AdbProtocol.decodeHeader(encoded)
        assertEquals(AdbProtocol.A_CNXN, h.command)
        assertEquals(AdbProtocol.VERSION, h.arg0)
        assertEquals(4096, h.arg1)
        assertEquals(payload.size, h.length)
    }

    @Test
    fun checksumIsBytewiseSum() {
        assertEquals(0, AdbProtocol.checksum(ByteArray(0)))
        assertEquals(1 + 2 + 255, AdbProtocol.checksum(byteArrayOf(1, 2, 0xFF.toByte())))
    }

    @Test(expected = IOException::class)
    fun badMagicIsRejected() {
        val encoded = AdbProtocol.encodeHeader(AdbProtocol.A_OKAY, 1, 2, ByteArray(0))
        encoded[20] = 0
        AdbProtocol.decodeHeader(encoded)
    }

    @Test(expected = IOException::class)
    fun oversizedPayloadIsRejected() {
        val encoded = AdbProtocol.encodeHeader(AdbProtocol.A_WRTE, 1, 2, ByteArray(0))
        encoded[12] = 0xFF.toByte()
        encoded[13] = 0xFF.toByte()
        encoded[14] = 0x10
        encoded[15] = 0
        AdbProtocol.decodeHeader(encoded)
    }

    @Test
    fun commandNamesAreStable() {
        assertEquals("CNXN", AdbProtocol.commandName(AdbProtocol.A_CNXN))
        assertEquals("AUTH", AdbProtocol.commandName(AdbProtocol.A_AUTH))
        assertEquals("WRTE", AdbProtocol.commandName(AdbProtocol.A_WRTE))
    }

    @Test
    fun generatedKeySignsATwentyByteToken() {
        val key = SoftwareAdbKey.generate()
        val token = ByteArray(20) { it.toByte() }
        val sig = key.signToken(token)
        assertEquals(256, sig.size)
        val payload = key.publicKeyPayload()
        assertEquals(0, payload.last().toInt())
        assert(payload.decodeToString().contains("rootforge@android"))
        assert(key.fingerprint().matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){15}")))
    }
}
