package ai.rootforge.os.device

import ai.rootforge.os.usb.TargetMode
import ai.rootforge.os.usb.UsbClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootGuidesTest {
    @Test
    fun tableHasEveryRequestedBrandAndGeneric() {
        val kinds = RootGuides.all.map { it.kind }.toSet()
        assertEquals(RootGuideKind.entries.toSet(), kinds)
        assertEquals(RootGuides.all.size, RootGuides.all.map { it.id }.toSet().size)
        for (guide in RootGuides.all) {
            assertTrue(guide.id, guide.title.isNotBlank() && guide.summary.isNotBlank())
            assertTrue(guide.id, guide.steps.size >= 3)
            assertFalse(guide.id, guide.odinFlashing)
            for (step in guide.steps) {
                assertFalse(step.id, step.autoReboot)
                assertTrue(step.id, step.erases.isNotBlank())
                assertTrue(step.id, step.detail.length > 40)
                if (step.write) {
                    assertNotNull(step.confirmPhrase)
                    assertTrue(step.id, step.dryRunFirst)
                }
                step.url?.let { url ->
                    assertTrue(url, url.startsWith("https://"))
                    assertFalse(url, url.contains(' '))
                }
            }
        }
    }

    @Test
    fun pixelUsesFastbootUnlockWithWipeWarning() {
        val id = DeviceIdentity(mode = TargetMode.ADB, brand = "google", model = "Pixel 8", codename = "shiba")
        val guide = RootGuides.forIdentity(id)
        assertEquals(RootGuideKind.PIXEL, guide.kind)
        val unlock = guide.steps.first { it.id == "unlock" }
        assertEquals("UNLOCK", unlock.confirmPhrase)
        assertTrue(unlock.erases.lowercase().contains("all user data") || unlock.erases.lowercase().contains("factory reset"))
        assertTrue(unlock.dryRunFirst)
        assertEquals(RootGuideAction.OPEN_FASTBOOT, unlock.action)
        assertFalse(guide.steps.any { it.autoReboot })
    }

    @Test
    fun samsungExplainsOdinAndKnoxAndDoesNotImplementOdin() {
        val id = DeviceIdentity(mode = TargetMode.ADB, brand = "samsung", model = "SM-S911B", codename = "dm1q")
        val guide = RootGuides.forIdentity(id)
        assertEquals(RootGuideKind.SAMSUNG, guide.kind)
        assertFalse(guide.odinFlashing)
        assertNotNull(guide.knoxWarning)
        assertTrue(guide.knoxWarning!!.contains("Knox"))
        assertTrue(guide.steps.any { it.id == "download" && it.detail.contains("Odin") })
        assertTrue(guide.steps.any { it.id == "odin-link" && it.action == RootGuideAction.OPEN_URL })
        assertFalse(guide.steps.any { it.detail.contains("flash tar", ignoreCase = true) && it.write })
    }

    @Test
    fun xiaomiMentionsTheWaitPeriod() {
        val id = DeviceIdentity(mode = TargetMode.ADB, brand = "Xiaomi", model = "M1908C3IG", codename = "olive")
        val guide = RootGuides.forIdentity(id)
        assertEquals(RootGuideKind.XIAOMI, guide.kind)
        val wait = guide.steps.first { it.id == "wait" }
        assertTrue(wait.detail.contains("7 to 30 days") || wait.detail.contains("wait"))
        assertTrue(wait.erases.lowercase().contains("nothing"))
        assertFalse(wait.write)
        assertEquals(RootGuideAction.OPEN_URL, wait.action)
        assertEquals(UnlockPortals.XIAOMI.url, wait.url)
        assertTrue(wait.url!!.startsWith("https://"))
        assertTrue(guide.steps.any { it.id == "unlock-data" && !it.write })
    }

    @Test
    fun onePlusMatchesBrand() {
        val id = DeviceIdentity(mode = TargetMode.ADB, brand = "OnePlus", model = "CPH2449", codename = "OP594DL1")
        val guide = RootGuides.forIdentity(id)
        assertEquals(RootGuideKind.ONEPLUS, guide.kind)
        assertEquals("UNLOCK", guide.steps.first { it.write }.confirmPhrase)
        val portal = guide.steps.first { it.id == "portal" }
        assertEquals(RootGuideAction.OPEN_URL, portal.action)
        assertEquals(UnlockPortals.ONEPLUS.url, portal.url)
        assertTrue(guide.steps.any { it.id == "unlock-data" && it.detail.contains("serial") })
    }

    @Test
    fun motorolaPointsAtTheOfficialUnlockSite() {
        val id = DeviceIdentity(mode = TargetMode.FASTBOOT, brand = "motorola", model = "motorola edge 40", codename = "rtwo")
        val guide = RootGuides.forIdentity(id)
        assertEquals(RootGuideKind.MOTOROLA, guide.kind)
        val code = guide.steps.first { it.id == "code" }
        assertEquals(RootGuideAction.OPEN_URL, code.action)
        assertEquals(UnlockPortals.MOTOROLA.url, code.url)
        assertTrue(code.url!!.contains("motorola"))
        assertTrue(code.url!!.startsWith("https://"))
        assertFalse(code.write)
        val data = guide.steps.first { it.id == "unlock-data" }
        assertTrue(data.detail.contains("oem get_unlock_data"))
        assertTrue(data.dryRunFirst)
        assertEquals(RootGuideAction.OPEN_FASTBOOT, data.action)
        assertFalse(data.write)
    }

    @Test
    fun unknownBrandUsesGenericFastboot() {
        val id = DeviceIdentity(mode = TargetMode.ADB, brand = "acme", model = "Toaster", codename = "bread")
        assertEquals(RootGuideKind.GENERIC, RootGuides.forIdentity(id).kind)
        assertEquals(RootGuideKind.GENERIC, RootGuides.forIdentity(null).kind)
        assertEquals(RootGuideKind.FAIRPHONE, RootGuides.forIdentity(DeviceIdentity(mode = TargetMode.ADB, brand = "fairphone", model = "FP5")).kind)
        assertEquals(RootGuideKind.OPPO, RootGuides.forIdentity(DeviceIdentity(mode = TargetMode.ADB, brand = "oppo", model = "CPH")).kind)
        assertEquals(RootGuideKind.VIVO, RootGuides.forIdentity(DeviceIdentity(mode = TargetMode.ADB, brand = "iqoo", model = "Neo")).kind)
        assertEquals(RootGuideKind.TRANSSION, RootGuides.forIdentity(DeviceIdentity(mode = TargetMode.ADB, brand = "infinix", model = "Hot")).kind)
        assertFalse(RootGuides.forIdentity(DeviceIdentity(mode = TargetMode.ADB, brand = "asus", model = "Zenfone")).steps.any { it.autoReboot })
    }

    @Test
    fun edlDoesNotPickABrandWizard() {
        val edl = DeviceIdentityParser.fromEdl(UsbClassifier.QUALCOMM_VID, UsbClassifier.EDL_PID, "44332211")
        assertEquals(RootGuideKind.GENERIC, RootGuides.forIdentity(edl).kind)
    }

    @Test
    fun desktopWindowIsTwoColumnsPhoneIsOne() {
        assertEquals(1, RootGuides.columnsForWidthDp(412))
        assertEquals(2, RootGuides.columnsForWidthDp(1100))
        assertEquals(1, RootGuides.columnsForWidthDp(RootGuides.WIDE_LAYOUT_DP - 1))
        assertEquals(2, RootGuides.columnsForWidthDp(RootGuides.WIDE_LAYOUT_DP))
    }
}
