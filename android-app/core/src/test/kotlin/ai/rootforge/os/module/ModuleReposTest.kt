package ai.rootforge.os.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleReposTest {
    @Test
    fun idsAreUniqueAndEveryEntryIsComplete() {
        val ids = ModuleRepos.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (r in ModuleRepos.all) {
            assertTrue(r.name.isNotBlank() && r.description.isNotBlank())
            assertTrue(r.supports.isNotEmpty())
        }
    }

    @Test
    fun onlyHttpsLinksWithoutCredentialsOrWhitespace() {
        for (r in ModuleRepos.all) {
            assertTrue("${r.id}: ${r.url}", r.url.startsWith("https://"))
            assertTrue("${r.id} must not carry credentials", !r.url.substringAfter("https://").substringBefore('/').contains('@'))
            assertTrue("${r.id} has whitespace", r.url.none { it.isWhitespace() })
        }
    }

    @Test
    fun checkedTextNeverClaimsWhatWasNotReachable() {
        // Entries whose site was not reachable must say so rather than claim a check.
        val unchecked = ModuleRepos.all.filter { it.checked.isBlank() }
        assertTrue(unchecked.all { it.note.contains("could not be reached") })
        for (r in ModuleRepos.all.filter { it.checked.isNotBlank() }) assertTrue(r.checked.startsWith("2026-10-19"))
    }

    @Test
    fun bothKindsArePresent() {
        assertTrue(ModuleRepos.all.any { it.kind == RepoKind.REPOSITORY })
        assertTrue(ModuleRepos.all.any { it.kind == RepoKind.CLIENT })
        assertTrue(ModuleRepos.all.any { RepoSupport.LSPOSED in it.supports })
    }
}
