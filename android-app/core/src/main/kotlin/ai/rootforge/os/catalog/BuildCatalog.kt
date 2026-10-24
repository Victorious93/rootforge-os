package ai.rootforge.os.catalog

import ai.rootforge.os.fastboot.TargetIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Read-only index of community ROMs, recoveries and root solutions, in the
 * `catalog.json` + `freshness.json` format that Custom RR publishes
 * (https://github.com/monsiu/Custom-RR). RootForge parses that data; it does
 * not bundle or copy it.
 *
 * Nothing here feeds [ai.rootforge.os.fastboot.DeviceMatchGate] or authorizes
 * a flash. The catalog carries no image files or digests, only links to each
 * project's download page. A matching TWRP / PitchBlack RECOVERY row may open
 * Custom Recovery via [CatalogRecoveryBridge]; that path still uses the
 * installer host allow-list, dry-run, checksums and typed FLASH RECOVERY.
 */
@Serializable
data class CatalogDevice(
    val brand: String = "",
    val model: String = "",
    /** One codename, or several joined by `/`, `,` or `&` for a combined build page. */
    val codename: String = "",
    val models: List<String> = emptyList(),
) {
    fun codenames(): Set<String> =
        codename.split('/', ',', '&', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    /** Model numbers (e.g. `M1908C3IG`) this row covers, lower-cased. */
    fun modelNumbers(): Set<String> = models.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}

@Serializable
data class CatalogEntry(
    val id: String,
    val name: String,
    val shortTagline: String = "",
    val downloadUrl: String = "",
    val forumUrl: String = "",
    val warning: String = "",
    val unofficial: Boolean = false,
    val devices: List<CatalogDevice> = emptyList(),
) {
    /** [downloadUrl] if it is https, else null. The catalog is remote data. */
    fun safeDownloadUrl(): String? = downloadUrl.trim().takeIf { it.startsWith("https://") }

    fun safeForumUrl(): String? = forumUrl.trim().takeIf { it.startsWith("https://") }
}

@Serializable
data class BuildCatalog(
    val roms: List<CatalogEntry> = emptyList(),
    val recoveries: List<CatalogEntry> = emptyList(),
    val roots: List<CatalogEntry> = emptyList(),
    @SerialName("_generatedAt") val generatedAt: String? = null,
)

@Serializable
data class Freshness(
    /** e.g. `active`, `monthly`, `abandoned`; free text from the feed. */
    val status: String = "",
    val lastBuild: String = "",
    val version: String = "",
    val source: String = "",
)

@Serializable
data class FreshnessFeed(
    val entries: Map<String, Freshness> = emptyMap(),
    @SerialName("_generatedAt") val generatedAt: String? = null,
)

enum class BuildKind { ROM, RECOVERY, ROOT }

data class BuildMatch(
    val kind: BuildKind,
    val entry: CatalogEntry,
    /** The catalog row that matched the target. */
    val device: CatalogDevice,
    /** Null when the freshness feed has no row for [entry]. */
    val freshness: Freshness?,
    /** Set when no codename matched and the target's model number did, e.g. a Redmi 8 under `mi439`. */
    val viaModelNumber: String? = null,
)

object BuildCatalogParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Throws [IllegalArgumentException] on malformed input. */
    fun parseCatalog(text: String): BuildCatalog = json.decodeFromString(BuildCatalog.serializer(), text)

    /** Throws [IllegalArgumentException] on malformed input. */
    fun parseFreshness(text: String): FreshnessFeed = json.decodeFromString(FreshnessFeed.serializer(), text)
}

object BuildLookup {
    /**
     * Catalog entries that list the target's product or codename, ROMs first,
     * then recoveries, then root solutions; official before unofficial within
     * each kind, otherwise catalog order. Matching is exact and
     * case-insensitive against each catalog codename (combined pages are
     * split on `/`, `,`, `&` and spaces). Stock names such as OnePlus's
     * `OnePlus6T` also look up their catalog codename (`fajita`) through
     * [CodenameAliases]. When an entry has no codename match,
     * [modelNumber] (`ro.product.model`) is tried against the row's `models`
     * list. An unknown target yields an empty list: unknown is not a wildcard.
     */
    fun forTarget(
        target: TargetIdentity,
        catalog: BuildCatalog,
        freshness: FreshnessFeed? = null,
        modelNumber: String? = null,
    ): List<BuildMatch> {
        val names = CodenameAliases.expand(target.names())
        val model = TargetIdentity.clean(modelNumber)?.lowercase()
        if (names.isEmpty() && model == null) return emptyList()

        fun matches(kind: BuildKind, entries: List<CatalogEntry>): List<BuildMatch> =
            entries.mapNotNull { entry ->
                val fresh = freshness?.entries?.get(entry.id)
                entry.devices.firstOrNull { d -> d.codenames().any { it in names } }
                    ?.let { return@mapNotNull BuildMatch(kind, entry, it, fresh) }
                if (model == null) return@mapNotNull null
                entry.devices.firstOrNull { d -> model in d.modelNumbers() }
                    ?.let { BuildMatch(kind, entry, it, fresh, viaModelNumber = modelNumber?.trim()) }
            }.sortedBy { it.entry.unofficial }

        return matches(BuildKind.ROM, catalog.roms) +
            matches(BuildKind.RECOVERY, catalog.recoveries) +
            matches(BuildKind.ROOT, catalog.roots)
    }
}
