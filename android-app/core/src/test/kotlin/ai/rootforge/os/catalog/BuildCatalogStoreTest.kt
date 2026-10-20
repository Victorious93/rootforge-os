package ai.rootforge.os.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory

class BuildCatalogStoreTest {
    private val dir = createTempDirectory("catalog-store").toFile()

    private fun fakeFetch(catalog: String = CATALOG, freshness: String? = FRESHNESS): (String) -> String = { url ->
        when (url) {
            BuildCatalogFetcher.CATALOG_URL -> catalog
            BuildCatalogFetcher.FRESHNESS_URL -> freshness ?: throw IOException("HTTP 404 from raw.githubusercontent.com")
            else -> error("unexpected $url")
        }
    }

    private fun file(name: String, text: String) = File(createTempDirectory("pick").toFile(), name).apply { writeText(text) }

    @Test
    fun startsEmpty() {
        assertNull(BuildCatalogStore(dir, fakeFetch()).loaded.value)
    }

    @Test
    fun downloadStoresBothFilesAndSurvivesARestart() {
        val store = BuildCatalogStore(dir, fakeFetch())
        val msg = store.download().getOrThrow()
        assertEquals("Downloaded the Custom RR catalog (2 entries) and freshness feed.", msg)
        assertEquals(listOf("alpha"), store.loaded.value?.catalog?.roms?.map { it.id })
        assertEquals("active", store.loaded.value?.freshness?.entries?.get("alpha")?.status)

        val reopened = BuildCatalogStore(dir, fakeFetch())
        assertEquals("Downloaded from Custom RR", reopened.loaded.value?.source)
        assertEquals(listOf("alpha"), reopened.loaded.value?.catalog?.roms?.map { it.id })
    }

    @Test
    fun aMissingFreshnessFeedStillStoresTheCatalog() {
        val store = BuildCatalogStore(dir, fakeFetch(freshness = null))
        val msg = store.download().getOrThrow()
        assertTrue(msg, msg.contains("freshness feed failed: HTTP 404"))
        assertNull(store.loaded.value?.freshness)
        assertEquals(listOf("alpha"), store.loaded.value?.catalog?.roms?.map { it.id })
    }

    @Test
    fun aBadDownloadKeepsTheLastGoodCatalog() {
        BuildCatalogStore(dir, fakeFetch()).download().getOrThrow()
        for (bad in listOf("<html>rate limited</html>", "{}", """{"roms": []}""")) {
            val store = BuildCatalogStore(dir, fakeFetch(catalog = bad))
            assertTrue(bad, store.download().isFailure)
            assertEquals(bad, listOf("alpha"), store.loaded.value?.catalog?.roms?.map { it.id })
        }
    }

    @Test
    fun importDetectsCatalogAndFreshnessFiles() {
        val store = BuildCatalogStore(dir, fakeFetch())
        assertEquals(
            "Imported freshness feed from freshness.json; import or download a catalog to use it.",
            store.import(file("freshness.json", FRESHNESS)).getOrThrow(),
        )
        assertNull(store.loaded.value)
        assertEquals("Imported build catalog from my-catalog.json.", store.import(file("my-catalog.json", CATALOG)).getOrThrow())
        assertEquals("Imported my-catalog.json", store.loaded.value?.source)
        assertEquals("active", store.loaded.value?.freshness?.entries?.get("alpha")?.status)
    }

    @Test
    fun importRejectsOtherFiles() {
        val store = BuildCatalogStore(dir, fakeFetch())
        store.import(file("catalog.json", CATALOG)).getOrThrow()
        for (text in listOf("not json", "[1, 2]", """{"foo": 1}""", """{"roms": [{"name": "no id"}]}""")) {
            val r = store.import(file("x.json", text))
            assertTrue(text, r.isFailure)
            assertTrue(text, r.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(listOf("alpha"), store.loaded.value?.catalog?.roms?.map { it.id })
    }

    @Test
    fun aCorruptStoredCatalogLoadsAsNone() {
        BuildCatalogStore(dir, fakeFetch()).download().getOrThrow()
        File(dir, "catalog.json").writeText("{ truncated")
        assertNull(BuildCatalogStore(dir, fakeFetch()).loaded.value)
    }

    @Test
    fun clearRemovesEverything() {
        val store = BuildCatalogStore(dir, fakeFetch())
        store.download().getOrThrow()
        store.clear()
        assertNull(store.loaded.value)
        assertNull(BuildCatalogStore(dir, fakeFetch()).loaded.value)
    }

    @Test
    fun fetchRefusesPlainHttp() {
        val r = runCatching { BuildCatalogFetcher.fetch("http://example.org/catalog.json") { error("must not open") } }
        assertTrue(r.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun fetchStopsAtTheSizeCap() {
        val big = ByteArray(1024) { 'a'.code.toByte() }
        val r = runCatching { BuildCatalogFetcher.fetch("https://example.org/c.json", maxBytes = 1000) { ByteArrayInputStream(big) } }
        assertTrue(r.exceptionOrNull() is IOException)
        assertEquals(
            "a".repeat(1000),
            BuildCatalogFetcher.fetch("https://example.org/c.json", maxBytes = 1000) { ByteArrayInputStream(big, 0, 1000) },
        )
    }

    private companion object {
        val CATALOG = """
            {"roms": [{"id": "alpha", "name": "Alpha ROM", "devices": [{"brand": "Google", "model": "Pixel 6", "codename": "oriole"}]}],
             "recoveries": [{"id": "rec", "name": "Recovery", "devices": []}]}
        """.trimIndent()
        val FRESHNESS = """{"entries": {"alpha": {"status": "active", "lastBuild": "2026-01-01"}}}"""
    }
}
