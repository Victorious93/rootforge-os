package ai.rootforge.os.links

import ai.rootforge.os.device.BoardHelpers
import ai.rootforge.os.device.UnlockPortals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryLinksTest {
    @Test
    fun idsAreUniqueAndEveryEntryIsComplete() {
        val ids = DirectoryLinks.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (link in DirectoryLinks.all) {
            assertTrue(link.id, link.name.isNotBlank() && link.description.isNotBlank())
            if (link.url != null) {
                assertTrue(link.id, link.url.isNotBlank())
            } else {
                assertEquals(link.id, LinkSection.BOOTLOADER_UNLOCK_PORTALS, link.section)
            }
            val extraLabels = link.extras.map { it.label }
            assertEquals(link.id, extraLabels.size, extraLabels.toSet().size)
            for (extra in link.extras) {
                assertTrue("${link.id} extra", extra.label.isNotBlank() && extra.url.isNotBlank())
            }
        }
    }

    @Test
    fun everyUrlIsHttpsWithoutCredentialsOrWhitespace() {
        val urls = DirectoryLinks.urls()
        assertTrue("directory is empty", urls.isNotEmpty())
        assertEquals(urls.size, urls.toSet().size)
        for (url in urls) {
            assertTrue(url, url.startsWith("https://"))
            assertTrue("$url must not carry credentials", !url.substringAfter("https://").substringBefore('/').contains('@'))
            assertTrue("$url has whitespace", url.none { it.isWhitespace() })
            assertTrue("$url is not http", !url.startsWith("http://"))
        }
    }

    @Test
    fun allSectionsArePresentWithNamedProjectsAndUnlockPortals() {
        val bySection = DirectoryLinks.all.groupBy { it.section }
        assertTrue(bySection.keys.containsAll(LinkSection.entries.toSet()))
        val portals = bySection.getValue(LinkSection.BOOTLOADER_UNLOCK_PORTALS)
        assertEquals(UnlockPortals.all.size, portals.size)
        val portalUrls = portals.mapNotNull { it.url }
        assertEquals(portalUrls.size, portalUrls.toSet().size)
        for (url in portalUrls) {
            assertTrue(url, url.startsWith("https://"))
        }
        assertTrue(portals.any { it.name.contains("Motorola") && it.url != null })
        assertTrue(portals.any { it.name.contains("Sony") && it.url != null })
        assertTrue(portals.any { it.name.contains("Xiaomi") && it.url != null })
        assertTrue(portals.any { it.name.contains("HTC") && it.url != null })
        assertTrue(portals.any { it.name.contains("OnePlus") && it.url != null })
        assertTrue(portals.any { it.id == "unlock-asus" && it.url == null })
        assertTrue(portals.any { it.id == "unlock-lg" && it.url == null })
        assertTrue(portals.any { it.id == "unlock-huawei" && it.url == null })
        val helpers = bySection.getValue(LinkSection.ESP32_PI_HELPERS)
        assertEquals(BoardHelpers.all.size, helpers.size)
        val helperUrls = helpers.flatMap { listOfNotNull(it.url) + it.extras.map { extra -> extra.url } }
        assertEquals(helperUrls.size, helperUrls.toSet().size)
        for (url in helperUrls) {
            assertTrue(url, url.startsWith("https://"))
        }
        assertTrue(helpers.any { it.id == "helper-raspberry-pi" && it.url != null })
        assertTrue(helpers.any { it.id == "helper-esp32-usb-serial" && it.url != null })
        assertTrue(helpers.any { it.description.contains("FRP") || it.description.contains("key-injection") || it.description.contains("Not a lock-pick") })
        val firmware = bySection.getValue(LinkSection.OFFICIAL_FIRMWARE)
        assertTrue("official firmware section", firmware.size >= 10)
        assertTrue(firmware.all { it.url != null && it.url!!.startsWith("https://") })
        assertTrue(firmware.any { it.url!!.contains("developers.google.com") })
        assertTrue(firmware.any { it.url!!.contains("oneplus.com") || it.name.contains("OnePlus") })
        assertTrue(firmware.any { it.description.contains("MSM") })
        val names = DirectoryLinks.all.map { it.name }.toSet()
        assertTrue(names.contains("Magisk"))
        assertTrue(names.contains("KernelSU"))
        assertTrue(names.contains("APatch"))
        assertTrue(names.contains("MMRL"))
        assertTrue(names.contains("Magisk-Modules-Alt-Repo"))
        assertTrue(names.contains("Zygisk Next"))
        assertTrue("Xposed missing from Links", names.any { "Xposed" in it })
        assertTrue(names.contains("LSPosed"))
        assertTrue("Vector missing from Links", names.any { "Vector" in it })
        assertTrue(names.contains("Sui"))
        assertTrue(names.contains("Shizuku"))
        assertTrue(names.contains("App Ops"))
        assertTrue(names.contains("Ice Box"))
        assertTrue(names.contains("Hail"))
        assertTrue(names.contains("SystemUI Tuner"))
        assertTrue(names.contains("Canta"))
        assertTrue(names.contains("Inure"))
        assertTrue(names.contains("aShell"))
    }
}
