package ai.rootforge.os.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardDiagramsTest {
    @Test
    fun everyGuideKindHasAShortDiagram() {
        for (kind in RootGuideKind.entries) {
            val steps = WizardDiagrams.forGuide(kind)
            assertTrue(kind.name, steps.size in 3..5)
            for (step in steps) {
                assertTrue(step.label, step.label.length <= 18)
                assertTrue(step.caption, step.caption.isNotBlank())
            }
        }
    }

    @Test
    fun unlockAndMsmDiagramsStayTiny() {
        assertEquals(4, WizardDiagrams.forUnlock(offered = true).size)
        assertEquals(3, WizardDiagrams.forUnlock(offered = false).size)
        val msm = WizardDiagrams.forMsm()
        assertEquals(4, msm.size)
        assertTrue(msm.any { it.label.contains("9008") || it.caption.contains("Identify") })
        assertTrue(msm.any { it.label.lowercase().contains("no write") || it.caption.lowercase().contains("bundled") })
    }
}
