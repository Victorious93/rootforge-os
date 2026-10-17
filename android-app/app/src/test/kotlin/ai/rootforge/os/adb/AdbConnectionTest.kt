package ai.rootforge.os.adb

import ai.rootforge.os.Pipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempFile

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

    @Test
    fun sideloadServesTheEndOfThePackageBeforeTheStartAndRereadsABlock() {
        // Recovery reads the zip central directory (the end) before the
        // payload, then reads the package again to install it. A host that
        // only walks forward aborts that and the update never finishes.
        val block = AdbConnection.SIDELOAD_BLOCK
        val payload = ByteArray(block * 2 + 50) { (it % 251).toByte() }
        val file = createTempFile("ota", ".zip").toFile()
        try {
            file.writeBytes(payload)
            val pipe = Pipe()
            val adbd = FakeAdbd(pipe.device)
            adbd.sideloadPlan = listOf(2, 0, 1, 0)
            adbd.start()
            AdbConnection(pipe.host, SoftwareAdbKey.generate()).use { conn ->
                conn.connect(promptTimeoutMs = 5_000)
                conn.sideload(file)
            }
            val chunks = adbd.sideloadChunks
            assertEquals(4, chunks.size)
            assertTrue(chunks[0].contentEquals(payload.copyOfRange(2 * block, payload.size)))
            assertTrue(chunks[1].contentEquals(payload.copyOfRange(0, block)))
            assertTrue(chunks[2].contentEquals(payload.copyOfRange(block, 2 * block)))
            assertTrue(chunks[3].contentEquals(payload.copyOfRange(0, block)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun sideloadFailFailIsAnErrorNotABlockNumber() {
        val file = createTempFile("ota", ".zip").toFile()
        try {
            file.writeBytes(ByteArray(32) { 1 })
            val pipe = Pipe()
            val adbd = FakeAdbd(pipe.device)
            adbd.sideloadPlan = emptyList()
            adbd.sideloadTerminal = "FAILFAIL"
            adbd.start()
            AdbConnection(pipe.host, SoftwareAdbKey.generate()).use { conn ->
                conn.connect(promptTimeoutMs = 5_000)
                try {
                    conn.sideload(file)
                    fail("FAILFAIL must not be treated as a successful sideload")
                } catch (e: AdbException) {
                    assertTrue(e.message!!.contains("failed"))
                }
            }
        } finally {
            file.delete()
        }
    }
}
