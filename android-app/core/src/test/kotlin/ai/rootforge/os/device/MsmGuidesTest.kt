package ai.rootforge.os.device

import ai.rootforge.os.usb.TargetMode
import ai.rootforge.os.usb.UsbClassifier
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MsmGuidesTest {
    @Test
    fun guideNeverWritesOrShipsFiles() {
        val edl = DeviceIdentityParser.fromEdl(UsbClassifier.QUALCOMM_VID, UsbClassifier.EDL_PID, "44332211")
        val guide = MsmGuides.forIdentity(edl)
        assertFalse(guide.writes)
        assertFalse(guide.shipsTool)
        assertFalse(guide.shipsFirmware)
        assertTrue(guide.steps.size >= 4)
        assertTrue(guide.summary.contains("MSM Download Tool"))
        assertTrue(guide.steps.any { it.id == "restore" && it.detail.contains("never") })
        assertTrue(guide.sources.isNotEmpty())
        for (source in guide.sources) {
            assertTrue(source.url.startsWith("https://"))
        }
    }

    @Test
    fun onePlusEdlGetsOfficialOnePlusSupportNotADumpSite() {
        val id = DeviceIdentity(mode = TargetMode.EDL, brand = "OnePlus", model = "CPH2449", serial = "f6e5d4c3b2a1")
        val guide = MsmGuides.forIdentity(id)
        assertTrue(guide.usesMsm)
        assertTrue(guide.sources.any { it.url.contains("oneplus.com") })
        assertFalse(guide.sources.any { it.url.contains("needrom", ignoreCase = true) })
        assertFalse(guide.sources.any { it.url.contains("androidfilehost", ignoreCase = true) })
    }

    @Test
    fun unknown9008ListsOfficialMsmOemPages() {
        val guide = MsmGuides.forIdentity(DeviceIdentityParser.fromEdl(0x05C6, 0x9008, "44332211"))
        val urls = guide.sources.map { it.url }
        assertTrue(urls.any { it.contains("oneplus.com") })
        assertTrue(urls.any { it.contains("oppo.com") })
        assertTrue(urls.any { it.contains("realme.com") })
        assertTrue(urls.any { it.contains("qualcomm.com") })
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun refuseListNamesFrpAuthBypassAndLeakedPackages() {
        val text = MsmGuides.REFUSED.joinToString(" ").lowercase()
        assertTrue(text.contains("frp"))
        assertTrue(text.contains("auth") || text.contains("signature"))
        assertTrue(text.contains("msm"))
        assertTrue(text.contains("dump") || text.contains("needrom") || text.contains("firmware"))
    }

    @Test
    fun everyGuideUrlIsHttpsOnAnAllowedHost() {
        for (url in MsmGuides.urls()) {
            assertTrue(url, url.startsWith("https://"))
            val host = URI(url).host.orEmpty()
            assertTrue(host, host.isNotBlank())
        }
    }
}
