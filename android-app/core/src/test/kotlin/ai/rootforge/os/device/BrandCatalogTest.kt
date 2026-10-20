package ai.rootforge.os.device

import ai.rootforge.os.usb.TargetMode
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandCatalogTest {
    @Test
    fun idsAliasesAndPortalIdsAreUniqueAndComplete() {
        val ids = BrandCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("need broad vendor coverage", BrandCatalog.all.size >= 20)
        val aliasHits = mutableSetOf<String>()
        for (record in BrandCatalog.all) {
            assertTrue(record.id, record.displayName.isNotBlank())
            assertTrue(record.id, UnlockPortals.byId(record.portalId) != null)
            assertEquals(record.id, record.unlockOffered, UnlockPortals.byId(record.portalId)!!.offered)
            for (token in record.tokens()) {
                assertTrue("$token already claimed", aliasHits.add(token))
            }
            record.firmware?.let { page ->
                assertTrue(page.url.startsWith("https://"))
                val host = URI(page.url).host.orEmpty().lowercase()
                assertTrue(page.allowedHosts.any { host == it || host.endsWith(".$it") })
            }
        }
    }

    @Test
    fun requestedMakersMatchAndClosedBrandsStayClosed() {
        fun id(brand: String, model: String) =
            BrandCatalog.forIdentity(DeviceIdentity(mode = TargetMode.ADB, brand = brand, model = model))?.id

        assertEquals("google", id("google", "Pixel 8"))
        assertEquals("samsung", id("samsung", "SM-S911B"))
        assertEquals("xiaomi", id("Xiaomi", "M1908C3IG"))
        assertEquals("oneplus", id("OnePlus", "CPH2449"))
        assertEquals("oppo", id("oppo", "CPH1234"))
        assertEquals("realme", id("realme", "RMX3085"))
        assertEquals("motorola", id("motorola", "motorola edge 40"))
        assertEquals("sony", id("sony", "Xperia 1 V"))
        assertEquals("nothing", id("nothing", "A063"))
        assertEquals("nothing", id("cmf", "Phone 1"))
        assertEquals("vivo", id("iqoo", "Neo"))
        assertEquals("nokia", id("hmd", "Nokia G22"))
        assertEquals("tecno", id("tecno", "Spark"))
        assertEquals("infinix", id("infinix", "Hot"))
        assertNull(id("acme", "Toaster"))
        for (closed in listOf("samsung", "asus", "lg", "huawei", "vivo", "meizu", "zte")) {
            assertFalse(closed, BrandCatalog.byId(closed)!!.unlockOffered)
        }
        assertTrue(BrandCatalog.msmBrands().map { it.id }.containsAll(listOf("oneplus", "oppo", "realme")))
    }

    @Test
    fun lgDoesNotMatchGoogleAndPixelModelTokenWorks() {
        val lg = DeviceIdentity(mode = TargetMode.ADB, brand = "lge", model = "LM-G900")
        assertEquals("lg", BrandCatalog.forIdentity(lg)?.id)
        val pixel = DeviceIdentity(mode = TargetMode.ADB, brand = null, model = "Pixel 8", codename = "shiba")
        assertEquals("google", BrandCatalog.forIdentity(pixel)?.id)
        assertNull(BrandCatalog.forIdentity(null))
    }

    @Test
    fun firmwareUrlsAreHttpsUniqueVendorHosts() {
        val pages = BrandCatalog.officialPages()
        assertTrue(pages.size >= 15)
        val urls = pages.map { it.url }
        assertEquals(urls.size, urls.toSet().size)
        for (page in pages) {
            assertTrue(page.url, page.url.startsWith("https://"))
            assertFalse(page.url, page.url.contains(' '))
        }
    }
}
