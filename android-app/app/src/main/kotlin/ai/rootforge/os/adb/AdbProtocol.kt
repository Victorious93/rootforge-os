package ai.rootforge.os.adb

import ai.rootforge.os.core.asLe
import ai.rootforge.os.core.leBuffer
import java.io.IOException

/** Wire format of the ADB transport (system/core/adb/protocol.txt): a 24-byte header plus payload. */
object AdbProtocol {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_AUTH = 0x48545541

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    const val VERSION = 0x01000001
    const val MAX_PAYLOAD = 256 * 1024
    const val HEADER_SIZE = 24

    fun commandName(cmd: Int): String = when (cmd) {
        A_SYNC -> "SYNC"; A_CNXN -> "CNXN"; A_OPEN -> "OPEN"; A_OKAY -> "OKAY"
        A_CLSE -> "CLSE"; A_WRTE -> "WRTE"; A_AUTH -> "AUTH"
        else -> "0x%08x".format(cmd)
    }

    fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }

    fun encodeHeader(command: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray =
        leBuffer(HEADER_SIZE).apply {
            putInt(command); putInt(arg0); putInt(arg1)
            putInt(payload.size); putInt(checksum(payload)); putInt(command xor -1)
        }.array()

    /** Parses a header into (command, arg0, arg1, payload length), validating the magic. */
    fun decodeHeader(bytes: ByteArray): AdbHeader {
        val b = bytes.asLe()
        val cmd = b.int
        val arg0 = b.int
        val arg1 = b.int
        val len = b.int
        b.int // data checksum: not enforced, newer adbd leaves it zero
        val magic = b.int
        if (magic != (cmd xor -1)) throw IOException("Corrupt ADB header (bad magic for ${commandName(cmd)})")
        if (len < 0 || len > MAX_PAYLOAD) throw IOException("Implausible ADB payload length $len")
        return AdbHeader(cmd, arg0, arg1, len)
    }
}

data class AdbHeader(val command: Int, val arg0: Int, val arg1: Int, val length: Int)

class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = ByteArray(0)) {
    override fun toString() = "${AdbProtocol.commandName(command)}($arg0,$arg1,${payload.size}B)"
}

class AdbException(message: String) : IOException(message)
