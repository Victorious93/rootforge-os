package ai.rootforge.os.catalog

import ai.rootforge.os.fastboot.TargetIdentity
import ai.rootforge.os.recovery.RecoveryHosts
import ai.rootforge.os.recovery.RecoveryProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRecoveryBridgeTest {
    private val catalog = BuildCatalogParser.parseCatalog(CATALOG)
    private val freshness = BuildCatalogParser.parseFreshness(FRESHNESS)

    @Test
    fun twrpRowMapsToTheInstaller() {
        val match = match("oriole").single { it.entry.id == "twrp" }
        val prefill = CatalogRecoveryBridge.prefill(match)!!
        assertEquals(RecoveryProject.TWRP, prefill.project)
        assertEquals("https://twrp.me/Devices/", prefill.pageUrl)
        assertEquals(listOf("oriole"), prefill.catalogCodenames)
        assertTrue(RecoveryHosts.allowed(prefill.pageUrl, prefill.project))
    }

    @Test
    fun pitchblackRowMapsUsingTheCatalogHost() {
        val match = match("oriole").single { it.entry.id == "pitchblack" }
        val prefill = CatalogRecoveryBridge.prefill(match)!!
        assertEquals(RecoveryProject.PITCHBLACK, prefill.project)
        assertEquals("https://pitchblack.tech/", prefill.pageUrl)
        assertTrue(RecoveryHosts.allowed("https://pitchblack.tech/", RecoveryProject.PITCHBLACK))
        assertTrue(RecoveryHosts.allowed("https://www.pitchblack.tech/x.img", RecoveryProject.PITCHBLACK))
        assertTrue(RecoveryHosts.allowed("https://pitchblackrecovery.com/olive", RecoveryProject.PITCHBLACK))
    }

    @Test
    fun orangefoxShrpAndRedwolfStayLinkOnly() {
        val byId = match("oriole").associateBy { it.entry.id }
        assertNull(CatalogRecoveryBridge.prefill(byId.getValue("orangefox")))
        assertNull(CatalogRecoveryBridge.prefill(byId.getValue("shrp")))
        assertNull(CatalogRecoveryBridge.prefill(byId.getValue("redwolf")))
        assertFalse(RecoveryHosts.allowed("https://orangefox.download/", RecoveryProject.TWRP))
        assertFalse(RecoveryHosts.allowed("https://orangefox.download/", RecoveryProject.PITCHBLACK))
        assertFalse(RecoveryHosts.allowed("https://shrp.github.io/", RecoveryProject.PITCHBLACK))
        assertFalse(RecoveryHosts.allowed("https://forum.xda-developers.com/c/redwolf-recovery.10018/", RecoveryProject.TWRP))
    }

    @Test
    fun romsAndRootSolutionsNeverMap() {
        val rows = match("oriole")
        assertTrue(rows.any { it.kind == BuildKind.ROM })
        assertTrue(rows.any { it.kind == BuildKind.ROOT })
        rows.filter { it.kind != BuildKind.RECOVERY }.forEach {
            assertNull(CatalogRecoveryBridge.prefill(it))
        }
    }

    @Test
    fun httpAndOffHostPagesAreRefused() {
        assertNull(CatalogRecoveryBridge.projectOf(catalog.recoveries.single { it.id == "orangefox" }))
        val httpTw = CatalogEntry("twrp", "TWRP", downloadUrl = "http://twrp.me/Devices/")
        assertEquals(RecoveryProject.TWRP, CatalogRecoveryBridge.projectOf(httpTw))
        assertNull(
            CatalogRecoveryBridge.prefill(
                BuildMatch(BuildKind.RECOVERY, httpTw, CatalogDevice(codename = "oriole"), freshness = null),
            ),
        )
        val evil = CatalogEntry("twrp", "TWRP", downloadUrl = "https://evil.example/twrp")
        assertEquals(RecoveryProject.TWRP, CatalogRecoveryBridge.projectOf(evil))
        assertNull(
            CatalogRecoveryBridge.prefill(
                BuildMatch(BuildKind.RECOVERY, evil, CatalogDevice(codename = "oriole"), freshness = null),
            ),
        )
    }

    @Test
    fun lookupUsesTheProbedPhoneNotTheCatalogRow() {
        assertEquals(listOf("olive"), CatalogRecoveryBridge.lookupNames(listOf("OLIVE", " olive ", "")))
        assertTrue(CatalogRecoveryBridge.lookupNames(emptyList()).isEmpty())
        assertTrue(CatalogRecoveryBridge.lookupNames(listOf("  ")).isEmpty())
        // A catalog listing for mi439 must not become the fetch key for a phone that reports olive.
        val prefill = CatalogRecoveryPrefill(
            catalogId = "twrp",
            catalogName = "TWRP",
            project = RecoveryProject.TWRP,
            pageUrl = "https://twrp.me/Devices/",
            catalogCodenames = listOf("mi439"),
            viaModelNumber = "M1908C3IG",
        )
        assertEquals(listOf("olive"), CatalogRecoveryBridge.lookupNames(listOf("olive")))
        assertEquals("twrp", prefill.catalogId)
        assertEquals(listOf("mi439"), prefill.catalogCodenames)
    }

    @Test
    fun encodeRoundTripsAndDecodeRejectsOffHost() {
        val original = CatalogRecoveryPrefill(
            catalogId = "pitchblack",
            catalogName = "PitchBlack Recovery",
            project = RecoveryProject.PITCHBLACK,
            pageUrl = "https://pitchblack.tech/",
            catalogCodenames = listOf("oriole"),
        )
        assertEquals(original, CatalogRecoveryPrefill.decode(original.encode()))
        assertNull(CatalogRecoveryPrefill.decode("TWRP|twrp|TWRP|https://evil.example/x|oriole|"))
        assertNull(CatalogRecoveryPrefill.decode("TWRP|twrp|TWRP|http://twrp.me/Devices/|oriole|"))
        assertNull(CatalogRecoveryPrefill.decode("not-a-project|twrp|TWRP|https://twrp.me/Devices/|oriole|"))
    }

    private fun match(codename: String) =
        BuildLookup.forTarget(TargetIdentity(codename = codename), catalog, freshness)

    private companion object {
        // Synthetic fixture in the Custom RR catalog shape. Not Custom RR data.
        // Hosts match the published 2026-09-12 catalog: twrp.me, orangefox.download,
        // forum.xda-developers.com, pitchblack.tech, shrp.github.io.
        val CATALOG = """
            {
              "roms": [
                {"id": "alpha", "name": "Alpha ROM", "downloadUrl": "https://example.org/alpha",
                 "devices": [{"brand": "Google", "model": "Pixel 6", "codename": "oriole"}]}
              ],
              "recoveries": [
                {"id": "twrp", "name": "TWRP", "downloadUrl": "https://twrp.me/Devices/",
                 "devices": [{"brand": "Google", "model": "Pixel 6", "codename": "oriole"}]},
                {"id": "orangefox", "name": "OrangeFox Recovery", "downloadUrl": "https://orangefox.download/",
                 "devices": [{"brand": "Google", "model": "Pixel 6", "codename": "oriole"}]},
                {"id": "redwolf", "name": "RedWolf Recovery",
                 "downloadUrl": "https://forum.xda-developers.com/c/redwolf-recovery.10018/",
                 "devices": [{"brand": "Google", "model": "Pixel 6", "codename": "oriole"}]},
                {"id": "pitchblack", "name": "PitchBlack Recovery", "downloadUrl": "https://pitchblack.tech/",
                 "devices": [{"brand": "Google", "model": "Pixel 6", "codename": "oriole"}]},
                {"id": "shrp", "name": "SHRP", "downloadUrl": "https://shrp.github.io/",
                 "devices": [{"brand": "Google", "model": "Pixel 6", "codename": "oriole"}]}
              ],
              "roots": [
                {"id": "rootkit", "name": "Root Kit",
                 "devices": [{"brand": "Google", "model": "Pixel 6", "codename": "oriole"}]}
              ]
            }
        """.trimIndent()

        val FRESHNESS = """
            {"entries": {"twrp": {"status": "active"}, "pitchblack": {"status": "active"}}}
        """.trimIndent()
    }
}
