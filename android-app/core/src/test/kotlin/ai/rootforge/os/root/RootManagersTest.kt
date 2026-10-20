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
            assertTrue(m.moduleBinary.isNotBlank())
        }
        assertEquals("magisk", RootManager.MAGISK.moduleBinary)
        assertEquals("/data/adb/ksud", RootManager.KERNELSU.moduleBinary)
        assertEquals("/data/adb/apd", RootManager.APATCH.moduleBinary)
        assertEquals("magisk", RootManager.KITSUNE.moduleBinary)
    }

    @Test
    fun detectUsesPackagesWhenNoMarkers() {
        val none = RootManager.detect(emptyList())
        assertNull(none.preferred)
        assertTrue(none.installed.isEmpty())

        val magisk = RootManager.detect(listOf("com.topjohnwu.magisk"))
        assertEquals(listOf(RootManager.MAGISK), magisk.installed)
        assertEquals(RootManager.MAGISK, magisk.preferred)

        val ksu = RootManager.detect(listOf("me.weishu.kernelsu"))
        assertEquals(RootManager.KERNELSU, ksu.preferred)

        val kitsune = RootManager.detect(listOf("io.github.huskydg.magisk"))
        assertEquals(RootManager.KITSUNE, kitsune.preferred)

        val both = RootManager.detect(listOf("com.topjohnwu.magisk", "me.weishu.kernelsu"))
        assertEquals(RootManager.MAGISK, both.preferred)
        assertEquals(2, both.installed.size)
    }

    @Test
    fun detectPrefersTheSuProviderFromAdbMarkers() {
        val ksu = RootManager.detect(
            listOf("com.topjohnwu.magisk", "me.weishu.kernelsu"),
            markers = listOf("ksud", "modules"),
        )
        assertEquals(RootManager.KERNELSU, ksu.preferred)

        val apatch = RootManager.detect(
            listOf("com.topjohnwu.magisk", "me.bmax.apatch"),
            markers = listOf("apd"),
        )
        assertEquals(RootManager.APATCH, apatch.preferred)

        val magiskMarker = RootManager.detect(listOf("com.topjohnwu.magisk"), markers = listOf("magisk"))
        assertEquals(RootManager.MAGISK, magiskMarker.preferred)

        val kitsuneOnly = RootManager.detect(listOf("io.github.huskydg.magisk"), markers = listOf("magisk"))
        assertEquals(RootManager.KITSUNE, kitsuneOnly.preferred)

        val leftover = RootManager.detect(emptyList(), markers = listOf("ksud"))
        assertEquals(RootManager.KERNELSU, leftover.preferred)
        assertTrue(leftover.installed.isEmpty())
    }
}
