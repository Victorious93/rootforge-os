package ai.rootforge.os.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempFile

class PersistentCommandLogTest {
    @Test
    fun linesSurviveARelaunchAndStayRedacted() {
        val file = createTempFile("command-log", ".jsonl").toFile()
        val first = OutputLog(persistFile = file, clock = { 1_000L })
        first.command("adb shell getprop ro.product.model")
        first.out("Pixel 8")
        first.info("token=supersecret")
        assertFalse(file.readText().contains("supersecret"))
        assertTrue(file.readText().contains("[REDACTED]"))

        val second = OutputLog(persistFile = file)
        val text = second.asText()
        assertTrue(text.contains("adb shell getprop ro.product.model"))
        assertTrue(text.contains("Pixel 8"))
        assertTrue(text.contains("token=[REDACTED]"))
        assertFalse(text.contains("supersecret"))
        assertEquals(3, second.lines.value.size)
    }

    @Test
    fun overflowRewritesTheFileToTheBound() {
        val file = createTempFile("command-log", ".jsonl").toFile()
        val log = OutputLog(maxLines = 3, persistFile = file, clock = { 2_000L })
        repeat(5) { log.out("line-$it") }
        assertEquals(listOf("line-2", "line-3", "line-4"), log.lines.value.map { it.text })

        val reloaded = OutputLog(maxLines = 3, persistFile = file)
        assertEquals(listOf("line-2", "line-3", "line-4"), reloaded.lines.value.map { it.text })
    }

    @Test
    fun clearEmptiesTheFile() {
        val file = createTempFile("command-log", ".jsonl").toFile()
        val log = OutputLog(persistFile = file)
        log.info("keep me")
        log.clear()
        assertEquals("", log.asText())
        assertEquals("", file.readText())
        assertTrue(OutputLog(persistFile = file).lines.value.isEmpty())
    }

    @Test
    fun aCorruptLineIsSkipped() {
        val file = createTempFile("command-log", ".jsonl").toFile()
        file.writeText("not-json\n{\"id\":1,\"level\":\"INFO\",\"text\":\"ok\",\"timeMs\":1}\n")
        val log = OutputLog(persistFile = file)
        assertEquals(listOf("ok"), log.lines.value.map { it.text })
    }
}
