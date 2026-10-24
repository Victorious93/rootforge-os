package ai.rootforge.os.edl

import ai.rootforge.os.Pipe
import ai.rootforge.os.core.asLe
import ai.rootforge.os.core.leBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

class SaharaFirehoseTest {
    @Test
    fun helloThenCommandModeReadsIdentity() {
        val pipe = Pipe()
        val client = SaharaClient(pipe.host)
        thread(isDaemon = true) {
            // HELLO: command=1, length=48, version=2, then padding
            pipe.device.send(sahara(0x01, ByteArray(40).also { it[0] = 2 }))
            val helloResp = pipe.device.nextPacket()
            assertEquals(0x02, helloResp.asLe().getInt(0))
            // CMD_READY
            pipe.device.send(sahara(0x0B, ByteArray(0)))
            // four exec reads: SERIAL, MSM_HW_ID, OEM_PK_HASH, SBL_VERSION
            repeat(4) {
                pipe.device.nextPacket() // CMD_EXEC
                pipe.device.send(sahara(0x0E, leBuffer(8).putInt(0).putInt(4).array()))
                pipe.device.nextPacket() // CMD_EXEC_DATA
                pipe.device.send(byteArrayOf(0x11, 0x22, 0x33, 0x44))
            }
            pipe.device.nextPacket() // switch mode
        }
        val info = client.readDeviceInfo()
        assertEquals(2, info.protocolVersion)
        assertEquals("44332211", info.serial)
    }

    /** The response length is the device's word; it sizes a buffer, so an absurd one must be refused, not allocated. */
    @Test
    fun anAbsurdIdentityLengthIsRefusedInsteadOfAllocated() {
        val pipe = Pipe()
        val client = SaharaClient(pipe.host)
        thread(isDaemon = true) {
            pipe.device.send(sahara(0x01, ByteArray(40).also { it[0] = 2 }))
            pipe.device.nextPacket() // HELLO_RESP
            pipe.device.send(sahara(0x0B, ByteArray(0))) // CMD_READY
            pipe.device.nextPacket() // CMD_EXEC
            pipe.device.send(sahara(0x0E, leBuffer(8).putInt(0).putInt(300_000_000).array()))
            pipe.device.nextPacket() // CMD_EXEC_DATA; nothing follows
        }
        try {
            client.readDeviceInfo()
            fail("a 300 MB identity answer must be refused")
        } catch (e: SaharaException) {
            assertTrue(e.message, e.message!!.contains("Implausible"))
        }
    }

    @Test
    fun firehoseConfigureAcks() {
        val pipe = Pipe()
        val client = FirehoseClient(pipe.host)
        thread(isDaemon = true) {
            val xml = pipe.device.nextPacket().decodeToString()
            assertTrue(xml.contains("<configure"))
            pipe.device.send("""<?xml version="1.0"?><data><log value="ok"/><response value="ACK" /></data>""".toByteArray())
        }
        client.configure("ufs")
        assertTrue(client.maxPayload > 0)
    }

    @Test
    fun firehoseProgramWritesPaddedSectors() {
        val pipe = Pipe()
        val client = FirehoseClient(pipe.host)
        val data = byteArrayOf(1, 2, 3)
        thread(isDaemon = true) {
            pipe.device.nextPacket()
            pipe.device.send("""<data><response value="ACK" /></data>""".toByteArray())
            val raw = pipe.device.nextPacket()
            assertEquals(512, raw.size)
            assertEquals(1.toByte(), raw[0])
            assertEquals(0.toByte(), raw[3])
            pipe.device.send("""<data><response value="ACK" /></data>""".toByteArray())
        }
        client.programRaw(0, 0, 512, ByteArrayInputStream(data), data.size.toLong())
    }

    private fun sahara(command: Int, body: ByteArray): ByteArray =
        leBuffer(8 + body.size).putInt(command).putInt(8 + body.size).put(body).array()
}
