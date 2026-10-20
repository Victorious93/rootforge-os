package ai.rootforge.os.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedCommandsTest {
    @Test
    fun listPackagesIsArgvNotAShellString() {
        assertEquals(listOf("pm", "list", "packages"), PrivilegedCommands.listPackages(false))
        assertEquals(listOf("pm", "list", "packages", "-3"), PrivilegedCommands.listPackages(true))
    }

    @Test
    fun packageInfoRejectsInjection() {
        assertEquals(listOf("dumpsys", "package", "com.termux"), PrivilegedCommands.packageInfo("com.termux"))
        for (bad in listOf("", "termux", "com.termux; reboot", "com.termux && wipe", "com.termux\nreboot", "../x", "a")) {
            assertNull(bad, PrivilegedCommands.packageInfo(bad))
        }
    }

    @Test
    fun settingsGetAllowsOnlySystemSecureGlobal() {
        assertEquals(listOf("settings", "get", "secure", "android_id"), PrivilegedCommands.settingsGet("secure", "android_id"))
        assertEquals(listOf("settings", "get", "global", "adb_enabled"), PrivilegedCommands.settingsGet("global", "adb_enabled"))
        assertEquals(listOf("settings", "get", "system", "screen_brightness"), PrivilegedCommands.settingsGet("system", "screen_brightness"))
        assertNull(PrivilegedCommands.settingsGet("system;reboot", "x"))
        assertNull(PrivilegedCommands.settingsGet("secure", "android_id; reboot"))
        assertNull(PrivilegedCommands.settingsGet("global", ""))
        assertNull(PrivilegedCommands.settingsGet("vendor", "foo"))
        assertTrue(PrivilegedCommands.isSettingsNamespace("secure"))
        assertFalse(PrivilegedCommands.isSettingsNamespace("system;id"))
    }

    @Test
    fun getpropRejectsEmptyAndMetacharacters() {
        assertEquals(listOf("getprop", "ro.product.model"), PrivilegedCommands.getprop("ro.product.model"))
        assertNull(PrivilegedCommands.getprop(""))
        assertNull(PrivilegedCommands.getprop("ro.product.model; reboot"))
        assertNull(PrivilegedCommands.getprop("\$(reboot)"))
        assertNull(PrivilegedCommands.getprop("ro product model"))
    }

    @Test
    fun blockDeviceCommandsAreReadOnlyListings() {
        val cmds = PrivilegedCommands.blockDeviceCommands()
        assertTrue(cmds.isNotEmpty())
        cmds.forEach { argv ->
            assertTrue(argv.toString(), argv.first() == "cat" || argv.first() == "ls")
            assertFalse(argv.toString(), argv.contains("dd") || argv.contains("flash") || argv.contains("wipe"))
            assertFalse(argv.toString(), argv.any { it.contains(";") || it.contains("|") || it.contains("$") })
        }
    }

    @Test
    fun deviceInfoKeysAreGetpropReadable() {
        assertTrue(PrivilegedCommands.DEVICE_INFO_KEYS.isNotEmpty())
        PrivilegedCommands.DEVICE_INFO_KEYS.forEach { key ->
            assertEquals(listOf("getprop", key), PrivilegedCommands.getprop(key))
        }
    }
}
