package ai.rootforge.os.edl

import ai.rootforge.os.core.asLe
import ai.rootforge.os.core.leBuffer
import ai.rootforge.os.core.toHex
import ai.rootforge.os.usb.BulkTransport
import ai.rootforge.os.usb.UsbTimeoutException
import java.io.IOException

class SaharaException(message: String) : IOException(message)

data class EdlDeviceInfo(
    val serial: String?,
    val msmHwId: String?,
    val oemPkHash: String?,
    val sblVersion: String?,
    val protocolVersion: Int,
)

/**
 * Qualcomm Sahara, the first-stage protocol an SoC speaks from its Primary
 * Boot Loader in Emergency Download mode (USB 05c6:9008).
 *
 * What it can do here: report the chip identity (serial, HW id, OEM public
 * key hash) in command mode, and upload a Firehose programmer you supply.
 * What it cannot: on devices with Secure Boot (nearly all retail phones) the
 * PBL accepts only a programmer signed by the OEM, so a generic loader is
 * rejected. RootForge ships no programmers.
 */
class SaharaClient(private val transport: BulkTransport) {

    private object Cmd {
        const val HELLO = 0x01; const val HELLO_RESP = 0x02; const val READ_DATA = 0x03
        const val END_IMAGE_TX = 0x04; const val DONE = 0x05; const val DONE_RESP = 0x06
        const val RESET = 0x07; const val RESET_RESP = 0x08
        const val CMD_READY = 0x0B; const val CMD_SWITCH_MODE = 0x0C
        const val CMD_EXEC = 0x0D; const val CMD_EXEC_RESP = 0x0E; const val CMD_EXEC_DATA = 0x0F
        const val READ_DATA_64 = 0x12
    }

    object Mode { const val IMAGE_TX_PENDING = 0; const val IMAGE_TX_COMPLETE = 1; const val COMMAND = 3 }
    object Client { const val SERIAL = 1; const val MSM_HW_ID = 2; const val OEM_PK_HASH = 3; const val SBL_VERSION = 7 }

    private class Packet(val command: Int, val body: ByteArray)

    /** Handshakes in command mode, reads the identity fields, then returns the device to image-transfer mode. */
    fun readDeviceInfo(): EdlDeviceInfo {
        val hello = expect(Cmd.HELLO, 10_000)
        val version = hello.body.asLe().getInt(0)
        sendHelloResponse(Mode.COMMAND)
        expect(Cmd.CMD_READY, 10_000)

        val serial = execRead(Client.SERIAL)?.let { if (it.size >= 4) "%08x".format(it.asLe().getInt(0)) else it.toHex() }
        val hw = execRead(Client.MSM_HW_ID)?.toHex()
        val pk = execRead(Client.OEM_PK_HASH)?.toHex()
        val sbl = execRead(Client.SBL_VERSION)?.let { if (it.size >= 4) "0x%x".format(it.asLe().getInt(0)) else it.toHex() }

        sendSwitchMode(Mode.IMAGE_TX_PENDING)
        return EdlDeviceInfo(serial, hw, pk, sbl, version)
    }

    /**
     * Uploads [programmer] (a Firehose .mbn/.elf) and returns when the PBL
     * reports the image transferred. The programmer then starts and speaks
     * Firehose on the same endpoints.
     */
    fun uploadProgrammer(programmer: ByteArray, progress: (Long, Long) -> Unit = { _, _ -> }) {
        // After readDeviceInfo() the device re-sends HELLO; on a fresh connection it is already waiting.
        val hello = expect(Cmd.HELLO, 10_000)
        sendHelloResponse(Mode.IMAGE_TX_PENDING)
        var done = false
        while (!done) {
            val p = readPacket(30_000)
            when (p.command) {
                Cmd.READ_DATA -> {
                    val b = p.body.asLe()
                    sendRange(programmer, b.getInt(4).toLong() and 0xFFFFFFFFL, b.getInt(8).toLong() and 0xFFFFFFFFL, progress)
                }
                Cmd.READ_DATA_64 -> {
                    val b = p.body.asLe()
                    sendRange(programmer, b.getLong(8), b.getLong(16), progress)
                }
                Cmd.END_IMAGE_TX -> {
                    val status = p.body.asLe().getInt(4)
                    if (status != 0) throw SaharaException("Device rejected the programmer (status 0x${status.toString(16)}). On a Secure Boot device only the OEM-signed programmer is accepted.")
                    send(Cmd.DONE, ByteArray(0))
                }
                Cmd.DONE_RESP -> done = true
                else -> throw SaharaException("Unexpected Sahara packet 0x${p.command.toString(16)} during upload (hello version ${hello.body.asLe().getInt(0)})")
            }
        }
    }

    fun reset() {
        send(Cmd.RESET, ByteArray(0))
        runCatching { readPacket(3000) }
    }

    private fun sendRange(image: ByteArray, offset: Long, length: Long, progress: (Long, Long) -> Unit) {
        if (offset < 0 || length < 0 || offset + length > image.size) {
            throw SaharaException("Device requested bytes $offset+$length outside the ${image.size}-byte programmer")
        }
        transport.write(image, offset.toInt(), length.toInt(), 10_000)
        progress(offset + length, image.size.toLong())
    }

    private fun execRead(clientCommand: Int): ByteArray? {
        send(Cmd.CMD_EXEC, leBuffer(4).putInt(clientCommand).array())
        val resp = readPacket(10_000)
        if (resp.command != Cmd.CMD_EXEC_RESP) return null
        val length = resp.body.asLe().getInt(4)
        if (length <= 0) return null
        // Identity fields are a handful of bytes; the length is the device's word and sizes a buffer.
        if (length > MAX_PACKET) throw SaharaException("Implausible Sahara command response length $length")
        send(Cmd.CMD_EXEC_DATA, leBuffer(4).putInt(clientCommand).array())
        return readBytes(length, 10_000)
    }

    private fun sendHelloResponse(mode: Int) {
        val body = leBuffer(40).apply {
            putInt(2) // version
            putInt(1) // minimum compatible version
            putInt(0) // status OK
            putInt(mode)
        }.array()
        send(Cmd.HELLO_RESP, body)
    }

    private fun sendSwitchMode(mode: Int) = send(Cmd.CMD_SWITCH_MODE, leBuffer(4).putInt(mode).array())

    private fun send(command: Int, body: ByteArray) {
        val packet = leBuffer(8 + body.size).apply { putInt(command); putInt(8 + body.size); put(body) }.array()
        transport.write(packet, timeoutMs = 5000)
    }

    private fun expect(command: Int, timeoutMs: Int): Packet {
        val p = readPacket(timeoutMs)
        if (p.command != command) throw SaharaException("Expected Sahara packet 0x${command.toString(16)}, got 0x${p.command.toString(16)}")
        return p
    }

    private fun readPacket(timeoutMs: Int): Packet {
        val head = readBytes(8, timeoutMs)
        val b = head.asLe()
        val cmd = b.getInt(0)
        val length = b.getInt(4)
        if (length < 8 || length > MAX_PACKET) throw SaharaException("Implausible Sahara packet length $length (not a Sahara device?)")
        return Packet(cmd, if (length > 8) readBytes(length - 8, timeoutMs) else ByteArray(0))
    }

    private fun readBytes(count: Int, timeoutMs: Int): ByteArray {
        val out = ByteArray(count)
        var got = 0
        while (got < count) {
            val n = try {
                transport.read(out, got, count - got, timeoutMs)
            } catch (e: UsbTimeoutException) {
                throw SaharaException("Timed out waiting for the EDL device")
            }
            got += n
        }
        return out
    }

    private companion object {
        const val MAX_PACKET = 64 * 1024
    }
}
