package ai.rootforge.os.glossary

import ai.rootforge.os.manual.ManualLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTest {
    @Test
    fun everyEntryIsNonEmptyAndUnique() {
        assertTrue("glossary is empty", Glossary.all.isNotEmpty())
        val terms = Glossary.all.map { it.term }
        assertEquals(terms.size, terms.toSet().size)
        val lower = terms.map { it.lowercase() }
        assertEquals(lower.size, lower.toSet().size)
        for (entry in Glossary.all) {
            assertTrue(entry.term, entry.term.isNotBlank())
            assertTrue(entry.term, entry.line.isNotBlank())
            assertTrue("${entry.term} line is too short", entry.line.length > 20)
            assertTrue("${entry.term} has a newline", !entry.term.contains('\n') && !entry.line.contains('\n'))
        }
    }

    @Test
    fun requiredTermsArePresent() {
        val terms = Glossary.all.map { it.term.lowercase() }.toSet()
        for (needed in REQUIRED) {
            assertTrue("missing $needed\n$terms", terms.any { needed.lowercase() == it || needed.lowercase() in it })
        }
    }

    @Test
    fun bundledManualContainsEveryTermAndLine() {
        val raw = ManualLibrary.default.raw
        assertTrue(raw.contains("## Glossary"))
        for (entry in Glossary.all) {
            assertTrue("manual missing term ${entry.term}", raw.contains("**${entry.term}.**"))
            assertTrue("manual missing line for ${entry.term}", raw.contains(entry.line))
        }
    }

    private companion object {
        val REQUIRED = listOf(
            "Root", "su", "Magisk", "KernelSU", "KernelSU Next", "APatch", "Module",
            "Zygisk", "Zygisk Next", "ReZygisk", "Xposed", "LSPosed", "Vector",
            "Shizuku", "Sui", "Dhizuku", "ADB", "fastboot", "EDL", "Serial", "SoC", "Slot", "USB mode",
            "Download mode", "Odin", "Knox", "Mi Unlock", "Unlock portal", "ESP32 helper", "Raspberry Pi helper", "Bootloader",
            "Recovery", "TWRP", "PitchBlack", "Boot image", "ROM", "Flash",
            "Dry run", "SHA-256 / checksum", "Bootloop",
        )
    }
}
