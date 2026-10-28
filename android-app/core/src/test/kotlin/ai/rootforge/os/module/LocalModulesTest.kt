package ai.rootforge.os.module

import ai.rootforge.os.root.RootManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModulesTest {
    @Test
    fun installCommandsMatchTheUserFacingBinaries() {
        assertEquals("magisk --install-module /data/local/tmp/rf-module.zip", LocalModules.installInner(RootManager.MAGISK))
        assertEquals("magisk --install-module /data/local/tmp/rf-module.zip", LocalModules.installInner(RootManager.KITSUNE))
        assertEquals("/data/adb/ksud module install /data/local/tmp/rf-module.zip", LocalModules.installInner(RootManager.KERNELSU))
        assertEquals("/data/adb/apd module install /data/local/tmp/rf-module.zip", LocalModules.installInner(RootManager.APATCH))
        assertEquals("magisk --install-module", RootManager.MAGISK.moduleInstallCommand)
        assertEquals("ksud module install", RootManager.KERNELSU.moduleInstallCommand)
        assertEquals("apd module install", RootManager.APATCH.moduleInstallCommand)
    }

    @Test
    fun copyAndInstallQuotesSpacesAndNeverReboots() {
        val script = LocalModules.copyAndInstallScript("/data/data/ai.rootforge.os.debug/files/library/my module.zip", RootManager.MAGISK)
        assertTrue(script.contains("'/data/data/ai.rootforge.os.debug/files/library/my module.zip'"))
        assertTrue(script.contains("cp "))
        assertTrue(script.contains("magisk --install-module /data/local/tmp/rf-module.zip"))
        assertTrue(script.contains("rm -f /data/local/tmp/rf-module.zip"))
        assertFalse("install must not reboot", Regex("""\breboot\b""").containsMatchIn(script))
        assertEquals("reboot", LocalModules.rebootInner())
        assertEquals(listOf("su", "-c", script), LocalModules.suArgv(script))
        assertTrue(LocalModules.suShell(script).startsWith("su -c "))
    }

    @Test
    fun apostropheInPathIsShellSafe() {
        val script = LocalModules.copyAndInstallScript("/sdcard/it's.zip", RootManager.KERNELSU)
        assertTrue(script.contains("'/sdcard/it'\\''s.zip'"))
        assertTrue(script.contains("/data/adb/ksud module install"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun relativePathIsRefused() {
        LocalModules.copyAndInstallScript("module.zip", RootManager.MAGISK)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parentDirIsRefused() {
        LocalModules.copyAndInstallScript("/data/../etc/module.zip", RootManager.MAGISK)
    }

    @Test
    fun dryRunPlanShowsSha256AndTheCommandAndNoReboot() {
        val plan = LocalModules.plan(
            localPath = "/data/local/tmp/demo.zip",
            zipName = "demo.zip",
            zipSize = 2048,
            sha256 = "ab".repeat(32),
            manager = RootManager.APATCH,
        )
        assertEquals(RootManager.APATCH, plan.manager)
        assertEquals(LocalModules.STAGING, plan.staging)
        assertTrue(plan.dryRunLines.any { it.contains("SHA-256: " + "ab".repeat(32)) })
        assertTrue(plan.dryRunLines.any { it.contains("Would run: su -c ") })
        assertTrue(plan.dryRunLines.any { it.contains("will not reboot") })
        assertTrue(plan.approvalLines.any { it.contains("SHA-256: " + "ab".repeat(32)) })
        assertTrue(plan.approvalLines.any { it.contains("THIS phone") })
        assertTrue(plan.displayCommand.startsWith("su -c "))
        assertTrue(plan.installLine.contains("apd module install"))
        assertFalse(Regex("""\breboot\b""").containsMatchIn(plan.script))
        assertFalse(plan.dryRunLines.any { it.lowercase().contains("would reboot") })
    }

    @Test
    fun parseListReadsModulePropAndFlags() {
        val dump = """
            ---
            dir:busybox
            disabled:no
            remove:no
            update:no
            id=busybox
            name=Busybox
            version=v1.36.1
            versionCode=13601
            author=osm0sis
            description=Busybox binary

            ---
            dir:hidden
            disabled:yes
            remove:yes
            update:yes
            id=hidden
            name=Hide
            version=1
            versionCode=1
            author=x
            description=off
        """.trimIndent()
        val list = LocalModules.parseList(dump)
        assertEquals(2, list.size)
        assertEquals("busybox", list[0].id)
        assertEquals("Busybox", list[0].name)
        assertEquals("v1.36.1", list[0].version)
        assertEquals("13601", list[0].versionCode)
        assertEquals("osm0sis", list[0].author)
        assertTrue(list[0].enabled)
        assertFalse(list[0].removePending)
        assertEquals("hidden", list[1].id)
        assertFalse(list[1].enabled)
        assertTrue(list[1].removePending)
        assertTrue(list[1].updatePending)
    }

    @Test
    fun parseListUsesDirWhenIdMissingAndIgnoresComments() {
        val dump = "---\ndir:fallback\ndisabled:no\n# comment\nname=Only Name\ndescription=equals=ok\n"
        val list = LocalModules.parseList(dump)
        assertEquals(1, list.size)
        assertEquals("fallback", list[0].id)
        assertEquals("Only Name", list[0].name)
        assertEquals("equals=ok", list[0].description)
    }

    @Test
    fun parseListEmptyAndCrlf() {
        assertTrue(LocalModules.parseList("").isEmpty())
        val crlf = "---\r\ndir:x\r\nid=x\r\nname=X\r\n"
        assertEquals("X", LocalModules.parseList(crlf).single().name)
    }

    @Test
    fun markersFromAdbLs() {
        val names = LocalModules.markersFromAdbLs("magisk\nksud\napd\nmodules\nls: cannot open\n")
        assertEquals(listOf("magisk", "ksud", "apd", "modules"), names)
    }

    @Test
    fun parseRootedNeedsUidZero() {
        assertTrue(LocalModules.parseRooted("uid=0(root) gid=0(root)", 0))
        assertFalse(LocalModules.parseRooted("uid=2000(shell)", 0))
        assertFalse(LocalModules.parseRooted("uid=0(root)", 1))
        assertFalse(LocalModules.parseRooted("su: not found", null))
        assertFalse(LocalModules.parseRooted("uid=0(root) gid=0(root)", null))
    }

    @Test
    fun listScriptIsASingleLineAndDoesNotReboot() {
        assertFalse(LocalModules.LIST_SCRIPT.contains('\n'))
        assertTrue(LocalModules.LIST_SCRIPT.contains("/data/adb/modules"))
        assertTrue(LocalModules.LIST_SCRIPT.contains("module.prop"))
        assertFalse(Regex("""\breboot\b""").containsMatchIn(LocalModules.LIST_SCRIPT))
        assertEquals("ls -1 /data/adb", LocalModules.MARKERS_SCRIPT)
    }
}
