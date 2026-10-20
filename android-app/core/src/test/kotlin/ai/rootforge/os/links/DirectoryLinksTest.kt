package ai.rootforge.os.links

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
            assertTrue(link.id, link.url.isNotBlank())
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
    fun allThreeSectionsArePresentWithNamedProjects() {
        val bySection = DirectoryLinks.all.groupBy { it.section }
        assertTrue(bySection.keys.containsAll(LinkSection.entries.toSet()))
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
