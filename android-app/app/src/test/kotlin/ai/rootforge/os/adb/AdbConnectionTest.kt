package ai.rootforge.os.adb

import ai.rootforge.os.Pipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AdbConnectionTest {
    @Test
    fun handshakeThenShellPushAndPull() {
        val pipe = Pipe()
        val key = SoftwareAdbKey.generate()
        val adbd = FakeAdbd(pipe.device) { cmd ->
            when {
                cmd.contains("getprop") -> "[ro.product.model]: [Pixel Test]\n" to 0
                else -> "ok\n" to 0
            }
        }
        adbd.files["/sdcard/a.txt"] = "hello".toByteArray()
        adbd.start()
        AdbConnection(pipe.host, key).use { conn ->
            conn.connect(promptTimeoutMs = 5_000)
            assertTrue(conn.connected)
            assertTrue(conn.banner.contains("Fake"))
            val shell = conn.shell("id")
            assertEquals(0, shell.exitCode)
            assertTrue(shell.output.contains("ok"))
            val props = conn.getprop()
            assertEquals("Pixel Test", props["ro.product.model"])
            val payload = "from-host".toByteArray()
            conn.push(ByteArrayInputStream(payload), payload.size.toLong(), "/sdcard/from-host.txt")
            assertTrue(adbd.files["/sdcard/from-host.txt"]!!.contentEquals(payload))
            val out = ByteArrayOutputStream()
            conn.pull("/sdcard/a.txt", out)
            assertEquals("hello", out.toString())
        }
    }
}
