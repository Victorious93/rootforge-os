package ai.rootforge.os.ask

import ai.rootforge.os.guide.GuideLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskEngineTest {
    private val engine = AskEngine()

    private fun top(q: String): String? = engine.ask(q).topics.firstOrNull()?.id

    @Test
    fun topicsReferenceRealGuidePagesAndAreUnique() {
        val library = GuideLibrary.default
        val ids = AskTopics.all.map { it.id }
        assertEquals("duplicate topic ids", ids.size, ids.toSet().size)
        for (t in AskTopics.all) {
            assertTrue("${t.id} has no keywords", t.keywords.isNotEmpty())
            assertTrue("${t.id} keywords must be lowercase", t.keywords.all { it == it.lowercase() })
            for (g in t.guide) assertTrue("${t.id} links to missing guide page $g", library.entry(g) != null)
        }
    }

    @Test
    fun commonQuestionsReachTheRightTopic() {
        assertEquals("adb-no-device", top("adb devices shows nothing"))
        assertEquals("adb-unauthorized", top("my phone says unauthorized"))
        assertEquals("fastboot-no-device", top("fastboot devices is empty"))
        assertEquals("unlock-bootloader", top("How do I unlock the bootloader?"))
        assertEquals("root-how", top("how do I root my phone"))
        assertEquals("bootloop", top("phone stuck in a bootloop after flashing"))
        assertEquals("check-root", top("am I rooted?"))
        assertEquals("wireless-adb", top("adb over wifi without cable"))
        assertEquals("integrity", top("banking app detects root, play integrity fails"))
        assertEquals("esp32-pi-helpers", top("can I use a raspberry pi as an unlock helper"))
        assertEquals("frp-screen-lock-refused", top("how do I bypass FRP"))
        assertEquals("msm-edl-guide", top("phone stuck in EDL 9008 need MSM download tool"))
    }

    @Test
    fun keywordMustStartAWord() {
        // "rotate" contains "ota"; it must not select the OTA topic.
        assertTrue(engine.ask("rotate the screen please").topics.none { it.id == "sideload-ota" })
    }

    @Test
    fun answersAlsoSearchTheGuide() {
        val r = engine.ask("how do I install magisk")
        assertTrue(r.guideHits.isNotEmpty())
        assertTrue(r.guideHits.size <= 4)
    }

    @Test
    fun nonsenseAndTinyInputReturnNothingInsteadOfAGuess() {
        assertTrue(engine.ask("").isEmpty)
        assertTrue(engine.ask("hi").isEmpty)
        assertTrue(engine.ask("qzxv wplk jjrr").isEmpty)
    }

    @Test
    fun keywordsDropFillerButKeepCommands() {
        assertEquals(listOf("adb", "devices", "shows", "nothing"), AskEngine.keywords("What does adb devices shows nothing"))
        assertTrue("su" in AskEngine.keywords("run su on the phone"))
    }
}
