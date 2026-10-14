package ai.rootforge.os.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class RedactionTest {
    @Test
    fun tokensAreStrippedBeforeTheyReachTheLog() {
        assertEquals("password=[REDACTED]", Redaction.redact("password=supersecret"))
        assertEquals("Authorization: Bearer [REDACTED]", Redaction.redact("Authorization: Bearer abcdef"))
        assertTrue(Redaction.redact("token: ghp_abcdefghijklmnopqrstuvwxyz").contains("[REDACTED]"))
        val log = OutputLog()
        log.out("api_key=sk-abcdefghijklmnopqrstuvwxyz")
        assertFalse(log.asText().contains("sk-abcdefghijklmnopqrstuvwxyz"))
    }
}

class LibrarySanitizeTest {
    @Test
    fun pathComponentsCannotEscapeTheLibrary() {
        assertEquals("boot.img", Library.sanitize("../boot.img"))
        assertEquals("twrp.img", Library.sanitize("/tmp/twrp.img"))
        assertEquals("evil_id_injected.img", Library.sanitize("evil\nid=injected.img"))
        assertEquals("file.img", Library.sanitize(".file.img"))
    }

    @Test
    fun getRejectsANameThatWouldBeSanitized() {
        val dir = createTempDirectory("rflib").toFile()
        val lib = Library(dir)
        File(dir, "ok.img").writeBytes(byteArrayOf(1))
        lib.refresh()
        assertEquals("ok.img", lib.get("ok.img")?.name)
        assertNull(lib.get("../ok.img"))
        assertNull(lib.get("missing.img"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalCenterTest {
    @Test
    fun aWrongPhraseDoesNotApprove() = runTest(UnconfinedTestDispatcher()) {
        val a = ApprovalCenter()
        val pending = async { a.request("Flash boot", listOf("plan"), phrase = "FLASH boot", timeoutMs = 5_000) }
        val id = a.pending.value.single().id
        assertFalse(a.answer(id, true, "flash boot"))
        assertTrue(a.answer(id, true, "FLASH boot"))
        assertTrue(pending.await())
    }

    @Test
    fun aTimeoutIsADenial() = runTest {
        val a = ApprovalCenter()
        assertFalse(a.request("x", emptyList(), timeoutMs = 1))
        assertTrue(a.pending.value.isEmpty())
    }

    @Test
    fun cancelIsADenial() = runTest(UnconfinedTestDispatcher()) {
        val a = ApprovalCenter()
        val pending = async { a.request("Unlock", listOf("wipes data"), phrase = "UNLOCK") }
        a.answer(a.pending.value.single().id, false)
        assertFalse(pending.await())
    }
}

class TerminalCommandTest {
    @Test
    fun flashingIsRefusedAndRoutedToTheGui() {
        val r = TerminalCommand.parse("fastboot flash boot boot.img") as TerminalCommand.Refused
        assertTrue(r.reason.contains("Fastboot"))
    }

    @Test
    fun oemIsNotForwarded() {
        assertTrue(TerminalCommand.parse("fastboot oem unlock") is TerminalCommand.Refused)
    }

    @Test
    fun shellAndRebootAreParsed() {
        assertEquals(TerminalCommand.AdbShell("getprop ro.product.model"), TerminalCommand.parse("adb shell getprop ro.product.model"))
        assertEquals(TerminalCommand.AdbReboot("bootloader"), TerminalCommand.parse("adb reboot bootloader"))
        assertEquals(TerminalCommand.FastbootUnlock, TerminalCommand.parse("fastboot flashing unlock"))
        assertEquals(TerminalCommand.Help, TerminalCommand.parse("help"))
    }

    @Test
    fun emptyInputIsIgnored() {
        assertNull(TerminalCommand.parse("   "))
    }
}
