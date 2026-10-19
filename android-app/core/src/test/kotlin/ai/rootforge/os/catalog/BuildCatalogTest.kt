package ai.rootforge.os.catalog

import ai.rootforge.os.fastboot.TargetIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BuildCatalogTest {
    private val catalog = BuildCatalogParser.parseCatalog(CATALOG)
    private val freshness = BuildCatalogParser.parseFreshness(FRESHNESS)

    @Test
    fun parsesTheCatalogAndIgnoresUnknownKeys() {
        assertEquals("2026-01-01T00:00:00Z", catalog.generatedAt)
        assertEquals(listOf("alpha", "beta", "unoff"), catalog.roms.map { it.id })
        assertEquals(listOf("rec"), catalog.recoveries.map { it.id })
        assertEquals(listOf("rootkit"), catalog.roots.map { it.id })
        assertEquals("2026-01-02T00:00:00Z", freshness.generatedAt)
    }

    @Test
    fun matchesTheCodenameCaseInsensitively() {
        val m = BuildLookup.forTarget(TargetIdentity(codename = "ORIOLE"), catalog, freshness)
        assertEquals(listOf("alpha", "unoff", "rec", "rootkit"), m.map { it.entry.id })
        assertEquals("Pixel 6", m.first().device.model)
    }

    @Test
    fun ordersRomsBeforeRecoveriesAndOfficialBeforeUnofficial() {
        val m = BuildLookup.forTarget(TargetIdentity(codename = "oriole"), catalog, freshness)
        assertEquals(
            listOf(BuildKind.ROM, BuildKind.ROM, BuildKind.RECOVERY, BuildKind.ROOT),
            m.map { it.kind },
        )
        assertTrue(m[1].entry.unofficial)
    }

    @Test
    fun matchesOneCodenameOfACombinedPage() {
        val m = BuildLookup.forTarget(TargetIdentity(codename = "rmx2151"), catalog, freshness)
        assertEquals(listOf("rec"), m.map { it.entry.id })
        assertEquals(setOf("rmx2001", "rmx2151"), m.single().device.codenames())
    }

    @Test
    fun aCodenamePrefixIsNotAMatch() {
        assertTrue(BuildLookup.forTarget(TargetIdentity(codename = "orio"), catalog, freshness).isEmpty())
        assertTrue(BuildLookup.forTarget(TargetIdentity(codename = "rmx"), catalog, freshness).isEmpty())
    }

    @Test
    fun theProductNameAlsoMatches() {
        val m = BuildLookup.forTarget(TargetIdentity(product = "shiba", codename = "unknown-board"), catalog, freshness)
        assertEquals(listOf("beta"), m.map { it.entry.id })
    }

    @Test
    fun anUnknownTargetMatchesNothing() {
        assertTrue(BuildLookup.forTarget(TargetIdentity(), catalog, freshness).isEmpty())
        val cleaned = TargetIdentity.fromAdbProps(mapOf("ro.product.device" to "unknown"))
        assertTrue(BuildLookup.forTarget(cleaned, catalog, freshness).isEmpty())
    }

    @Test
    fun freshnessIsAttachedByIdAndMayBeMissing() {
        val m = BuildLookup.forTarget(TargetIdentity(codename = "oriole"), catalog, freshness).associateBy { it.entry.id }
        assertEquals("active", m.getValue("alpha").freshness?.status)
        assertEquals("2026-01-01", m.getValue("alpha").freshness?.lastBuild)
        assertNull(m.getValue("unoff").freshness)
        assertNull(BuildLookup.forTarget(TargetIdentity(codename = "oriole"), catalog).first().freshness)
    }

    @Test
    fun onlyHttpsLinksAreExposed() {
        val byId = catalog.roms.associateBy { it.id }
        assertEquals("https://example.org/alpha", byId.getValue("alpha").safeDownloadUrl())
        assertNull(byId.getValue("beta").safeDownloadUrl())
        assertNull(byId.getValue("unoff").safeDownloadUrl())
        assertEquals("https://example.org/forum", byId.getValue("alpha").safeForumUrl())
    }

    @Test
    fun malformedInputIsRejected() {
        for (bad in listOf("", "not json", """{"roms": [{"name": "no id"}]}""", """{"roms": {}}""")) {
            try {
                BuildCatalogParser.parseCatalog(bad)
                fail("expected a parse failure for: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private companion object {
        // Synthetic fixture in the Custom RR catalog shape. Not Custom RR data.
        val CATALOG = """
            {
              "_generated": "test",
              "_generatedAt": "2026-01-01T00:00:00Z",
              "devices": [{"name": "Google", "imageAsset": "x.png"}],
              "roms": [
                {"id": "alpha", "name": "Alpha ROM", "headerAsset": "a.png",
                 "downloadUrl": "https://example.org/alpha", "forumUrl": "https://example.org/forum",
                 "links": [{"label": "web", "url": "https://example.org"}],
                 "devices": [{"brand": "Google", "model": "Pixel 6", "codename": "oriole", "models": ["GB7N6"]}]},
                {"id": "beta", "name": "Beta ROM", "downloadUrl": "http://example.org/beta",
                 "devices": [{"brand": "Google", "model": "Pixel 8a", "codename": "shiba"}]},
                {"id": "unoff", "name": "Unofficial ROM", "unofficial": true, "downloadUrl": "javascript:alert(1)",
                 "devices": [{"brand": "Google", "model": "Pixel 6", "codename": "Oriole"}]}
              ],
              "recoveries": [
                {"id": "rec", "name": "Some Recovery", "downloadUrl": "https://example.org/rec",
                 "devices": [
                   {"brand": "Google", "model": "Pixel 6", "codename": "oriole"},
                   {"brand": "realme", "model": "realme 6 / 7", "codename": "rmx2001/rmx2151"}
                 ]}
              ],
              "roots": [
                {"id": "rootkit", "name": "Root Kit",
                 "devices": [{"brand": "Google", "model": "Pixel 6", "codename": "oriole"}]}
              ]
            }
        """.trimIndent()

        val FRESHNESS = """
            {
              "_generatedAt": "2026-01-02T00:00:00Z",
              "entries": {
                "alpha": {"status": "active", "lastBuild": "2026-01-01", "daysAgo": 0, "version": "1.0", "origin": "network"},
                "rec": {"status": "abandoned", "lastBuild": "2024-01-01"}
              }
            }
        """.trimIndent()
    }
}
