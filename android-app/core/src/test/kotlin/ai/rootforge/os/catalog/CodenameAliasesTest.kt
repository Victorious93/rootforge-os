package ai.rootforge.os.catalog

import ai.rootforge.os.fastboot.TargetIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodenameAliasesTest {
    private val catalog = BuildCatalogParser.parseCatalog(
        """
        {"roms": [
          {"id": "a", "name": "A", "devices": [{"brand": "OnePlus", "model": "6T", "codename": "fajita"}]},
          {"id": "b", "name": "B", "devices": [{"brand": "OnePlus", "model": "8", "codename": "instantnoodle"}]},
          {"id": "c", "name": "C", "devices": [{"brand": "OnePlus", "model": "8", "codename": "oneplus8"}]},
          {"id": "d", "name": "D", "devices": [{"brand": "OnePlus", "model": "6", "codename": "enchilada"}]}
        ]}
        """.trimIndent(),
    )

    private fun ids(t: TargetIdentity) = BuildLookup.forTarget(t, catalog).map { it.entry.id }

    @Test
    fun aStockOnePlusNameFindsTheCatalogCodename() {
        assertEquals(listOf("a"), ids(TargetIdentity(codename = "OnePlus6T")))
        assertEquals(listOf("a"), ids(TargetIdentity(product = "OnePlus6T")))
        assertEquals(listOf("d"), ids(TargetIdentity(codename = "OnePlus6")))
    }

    @Test
    fun anAliasAddsToTheOwnNameInsteadOfReplacingIt() {
        // Catalog rows exist under both the stock-style and the codename form.
        assertEquals(listOf("b", "c"), ids(TargetIdentity(codename = "OnePlus8")))
    }

    @Test
    fun anAliasIsNotAPrefixOrFuzzyMatch() {
        assertTrue(ids(TargetIdentity(codename = "OnePlus6TX")).isEmpty())
        assertTrue(ids(TargetIdentity(codename = "OnePlus")).isEmpty())
        assertTrue(ids(TargetIdentity(codename = "oneplus9")).isEmpty())
    }

    @Test
    fun expandKeepsTheOriginalNamesAndIgnoresCase() {
        assertEquals(setOf("oneplus6t", "fajita"), CodenameAliases.expand(setOf("oneplus6t")))
        assertEquals(setOf("fajita"), CodenameAliases.forName(" OnePlus6T "))
        assertEquals(setOf("pixel"), CodenameAliases.expand(setOf("pixel")))
    }

    @Test
    fun theTableIsWellFormed() {
        val keys = listOf("oneplus6t", "oneplus9pro", "op595dl1", "nord")
        for (k in keys) assertTrue(k, CodenameAliases.forName(k).isNotEmpty())
        // A codename is never mapped to itself, and every target is lower-case, non-blank, no separators.
        for (k in keys) for (t in CodenameAliases.forName(k)) {
            assertTrue(t, t == t.lowercase() && t.isNotBlank() && t != k && t.none { it in "/,& " })
        }
    }
}
