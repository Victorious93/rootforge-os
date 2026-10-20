package ai.rootforge.os.device

import ai.rootforge.os.usb.TargetMode
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockPortalsTest {
    @Test
    fun idsAreUniqueAndEveryEntryIsComplete() {
        val ids = UnlockPortals.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val names = UnlockPortals.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
        for (portal in UnlockPortals.all) {
            assertTrue(portal.id, portal.brands.isNotEmpty())
            assertTrue(portal.id, portal.note.length > 40)
            assertTrue(portal.id, portal.unlockData.length > 40)
            if (portal.url != null) {
                assertTrue(portal.id, portal.offered)
                assertTrue(portal.id, portal.allowedHosts.isNotEmpty())
            } else {
                assertTrue(portal.id, portal.allowedHosts.isEmpty())
            }
            if (!portal.offered) {
                assertNull(portal.id, portal.url)
            }
        }
    }

    @Test
    fun everyPortalUrlIsHttpsUniqueAndOnAVendorHost() {
        val urls = UnlockPortals.urls()
        assertTrue("expected live official portals", urls.size >= 5)
        assertEquals(urls.size, urls.toSet().size)
        for (portal in UnlockPortals.all) {
            val url = portal.url ?: continue
            assertTrue(url, url.startsWith("https://"))
            assertFalse(url, url.contains(' '))
            assertFalse("$url must not carry credentials", url.substringAfter("https://").substringBefore('/').contains('@'))
            val host = URI(url).host.orEmpty().lowercase()
            assertTrue("$url host $host", portal.allowedHosts.any { host == it || host.endsWith(".$it") })
        }
    }

    @Test
    fun requestedMakersArePresentAndClosedProgrammesHaveNoUrl() {
        val byId = UnlockPortals.all.associateBy { it.id }
        assertEquals("https://en-us.support.motorola.com/app/standalone/bootloader/unlock-your-device-a", byId.getValue("motorola").url)
        assertEquals("oem get_unlock_data", byId.getValue("motorola").unlockDataCommand)
        assertEquals("https://developer.sony.com/open-source/aosp-on-xperia-open-devices/get-started/unlock-bootloader", byId.getValue("sony").url)
        assertEquals("https://en.miui.com/unlock/", byId.getValue("xiaomi").url)
        assertEquals("https://www.htcdev.com/bootloader", byId.getValue("htc").url)
        assertEquals("https://www.oneplus.com/us/unlock_token", byId.getValue("oneplus").url)
        assertEquals("https://www.fairphone.com/bootloader-unlocking-code-for-fairphone", byId.getValue("fairphone").url)
        assertTrue(byId.getValue("google").offered)
        assertNull(byId.getValue("google").url)
        assertFalse(byId.getValue("samsung").offered)
        assertNull(byId.getValue("samsung").url)
        assertTrue(byId.getValue("nothing").offered)
        assertNull(byId.getValue("nothing").url)
        for (closed in listOf("asus", "lg", "huawei")) {
            assertFalse(closed, byId.getValue(closed).offered)
            assertNull(closed, byId.getValue(closed).url)
            assertTrue(closed, byId.getValue(closed).note.lowercase().contains("no") || byId.getValue(closed).note.lowercase().contains("stop"))
        }
    }

    @Test
    fun matchingUsesWholeWordsSoLgDoesNotHitGoogle() {
        val pixel = DeviceIdentity(mode = TargetMode.ADB, brand = "google", model = "Pixel 8", codename = "shiba")
        assertEquals("google", UnlockPortals.forIdentity(pixel)?.id)
        val moto = DeviceIdentity(mode = TargetMode.FASTBOOT, brand = "motorola", model = "motorola edge 40", codename = "rtwo")
        assertEquals("motorola", UnlockPortals.forIdentity(moto)?.id)
        val sony = DeviceIdentity(mode = TargetMode.ADB, brand = "sony", model = "Xperia 1 V", codename = "pdx234")
        assertEquals("sony", UnlockPortals.forIdentity(sony)?.id)
        val lg = DeviceIdentity(mode = TargetMode.ADB, brand = "lge", model = "LM-G900", codename = "betalm")
        assertEquals("lg", UnlockPortals.forIdentity(lg)?.id)
        assertFalse(UnlockPortals.forIdentity(lg)!!.offered)
        val honor = DeviceIdentity(mode = TargetMode.ADB, brand = "honor", model = "Honor 8", codename = "frd")
        assertEquals("huawei", UnlockPortals.forIdentity(honor)?.id)
        assertNull(UnlockPortals.forIdentity(null))
        val edl = DeviceIdentityParser.fromEdl(0x05C6, 0x9008, "44332211")
        assertNull(UnlockPortals.forIdentity(edl))
    }

    @Test
    fun copyNeverSendsDeviceDataAutomatically() {
        for (portal in UnlockPortals.all) {
            val text = (portal.note + " " + portal.unlockData).lowercase()
            assertTrue(
                portal.id,
                text.contains("never") || text.contains("does not") || text.contains("will not") || text.contains("no official"),
            )
        }
    }
}
