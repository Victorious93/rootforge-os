package ai.rootforge.os.root

import ai.rootforge.os.core.TerminalApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootManagersTest {
    @Test
    fun packageNamesAreUniqueAndValid() {
        val names = RootManager.entries.map { it.packageName }
        assertEquals(names.size, names.toSet().size)
        names.forEach { assertTrue("$it", TerminalApp.isValidPackage(it)) }
    }

    @Test
    fun kitsuneUsesTheOwnerSuppliedPackageAndCarriesAWarning() {
        assertEquals("io.github.huskydg.magisk", RootManager.KITSUNE.packageName)
        assertNull("no trusted download source is known", RootManager.KITSUNE.homepage)
        assertTrue(RootManager.KITSUNE.caveat!!.contains("[UNSUPPORTED]"))
    }

    @Test
    fun supportedManagersHaveNoCaveatAndKeepTheirHomepage() {
        for (m in listOf(RootManager.MAGISK, RootManager.KERNELSU, RootManager.APATCH)) {
            assertNull(m.caveat)
            assertTrue(m.homepage!!.startsWith("https://"))
        }
        assertEquals(RootManager.MAGISK, RootManager.entries.first())
    }

    @Test
    fun everyManagerHasStepsAndAPatchedPrefix() {
        for (m in RootManager.entries) {
            assertTrue(m.patchSteps.isNotEmpty())
            assertNotNull(m.patchedPrefix)
            assertTrue(m.moduleInstallCommand.contains(" "))
        }
    }
}
