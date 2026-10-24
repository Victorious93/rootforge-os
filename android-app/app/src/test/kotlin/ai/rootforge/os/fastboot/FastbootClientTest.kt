package ai.rootforge.os.fastboot

import ai.rootforge.os.Pipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.concurrent.thread

class FastbootClientTest {
    @Test
    fun getvarReadsAnOkayReply() {
        val pipe = Pipe()
        val client = FastbootClient(pipe.host)
        val device = thread(isDaemon = true, name = "fake-fastboot") {
            val cmd = pipe.device.nextPacket().decodeToString()
            assertEquals("getvar:product", cmd)
            pipe.device.send("OKAYblueline".toByteArray())
        }
        assertEquals("blueline", client.getvar("product"))
        device.join()
    }

    @Test
    fun getvarAllCollectsInfoLines() {
        val pipe = Pipe()
        val client = FastbootClient(pipe.host)
        thread(isDaemon = true) {
            pipe.device.nextPacket()
            pipe.device.send("INFO(bootloader) unlocked: yes".toByteArray())
            pipe.device.send("INFO(bootloader) current-slot: a".toByteArray())
            pipe.device.send("OKAY".toByteArray())
        }
        val vars = client.getvarAll()
        assertEquals("yes", vars["unlocked"])
        assertEquals("a", vars["current-slot"])
    }

    /** On the wire the INFO text is just `name: value`; the `(bootloader) ` prefix is added by the host CLI. */
    @Test
    fun getvarAllKeepsArgumentBearingNamesIntact() {
        val pipe = Pipe()
        val client = FastbootClient(pipe.host)
        thread(isDaemon = true) {
            pipe.device.nextPacket()
            pipe.device.send("INFOhas-slot:boot: yes".toByteArray())
            pipe.device.send("INFOpartition-size:boot_a: 0x4000000".toByteArray())
            pipe.device.send("INFOpartition-size:boot_b: 0x4000000".toByteArray())
            pipe.device.send("INFOcurrent-slot: a".toByteArray())
            pipe.device.send("OKAY".toByteArray())
        }
        val vars = client.getvarAll()
        assertEquals("yes", vars["has-slot:boot"])
        assertEquals("0x4000000", vars["partition-size:boot_a"])
        assertEquals("0x4000000", vars["partition-size:boot_b"])
        assertEquals("a", vars["current-slot"])
    }

    @Test
    fun aFailReplyBecomesAnException() {
        val pipe = Pipe()
        val client = FastbootClient(pipe.host)
        thread(isDaemon = true) {
            pipe.device.nextPacket()
            pipe.device.send("FAILnot unlocked".toByteArray())
        }
        try {
            client.run("flashing unlock")
            throw AssertionError("expected FastbootException")
        } catch (e: FastbootException) {
            assertTrue(e.message!!.contains("not unlocked"))
        }
    }

    @Test
    fun downloadThenFlashSendsTheBytes() {
        val pipe = Pipe()
        val client = FastbootClient(pipe.host)
        val image = ByteArray(32) { it.toByte() }
        thread(isDaemon = true) {
            assertEquals("download:00000020", pipe.device.nextPacket().decodeToString())
            pipe.device.send("DATA00000020".toByteArray())
            val body = pipe.device.nextPacket()
            assertTrue(body.contentEquals(image))
            pipe.device.send("OKAY".toByteArray())
            assertEquals("flash:boot", pipe.device.nextPacket().decodeToString())
            pipe.device.send("OKAY".toByteArray())
        }
        client.flash("boot", ByteArrayInputStream(image), image.size.toLong())
    }
}
