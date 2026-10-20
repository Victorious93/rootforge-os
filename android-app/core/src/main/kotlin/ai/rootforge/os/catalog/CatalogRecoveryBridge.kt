package ai.rootforge.os.catalog

import ai.rootforge.os.recovery.RecoveryHosts
import ai.rootforge.os.recovery.RecoveryProject
import java.net.URI

/**
 * How a Custom RR **RECOVERY** catalog row opens the Custom Recovery
 * installer. The catalog still has no image files and no digests: it
 * only names a project and a download page. Official TWRP / PitchBlack
 * files are looked up from the **probed** phone, not from the catalog
 * row's device list. ROMs, root solutions, OrangeFox, SHRP, RedWolf
 * and anything whose page is not https on the installer allow-list
 * stay link-only.
 */
data class CatalogRecoveryPrefill(
    val catalogId: String,
    val catalogName: String,
    val project: RecoveryProject,
    val pageUrl: String,
    val catalogCodenames: List<String>,
    val viaModelNumber: String? = null,
) {
    fun encode(): String = listOf(
        project.name,
        catalogId.replace('|', '/'),
        catalogName.replace('|', '/'),
        pageUrl,
        catalogCodenames.joinToString(","),
        viaModelNumber.orEmpty(),
    ).joinToString("|")

    companion object {
        fun decode(raw: String): CatalogRecoveryPrefill? {
            val p = raw.split('|')
            if (p.size != 6) return null
            val project = runCatching { RecoveryProject.valueOf(p[0]) }.getOrNull() ?: return null
            if (p[1].isBlank() || p[3].isBlank()) return null
            if (!RecoveryHosts.allowed(p[3], project)) return null
            return CatalogRecoveryPrefill(
                catalogId = p[1],
                catalogName = p[2],
                project = project,
                pageUrl = p[3],
                catalogCodenames = p[4].split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                viaModelNumber = p[5].ifBlank { null },
            )
        }
    }
}

object CatalogRecoveryBridge {
    /**
     * Null for ROMs, root solutions, and recoveries the installer does
     * not write (OrangeFox, SHRP, RedWolf, unknown ids, http, off-host).
     */
    fun prefill(match: BuildMatch): CatalogRecoveryPrefill? {
        if (match.kind != BuildKind.RECOVERY) return null
        val project = projectOf(match.entry) ?: return null
        val page = match.entry.safeDownloadUrl() ?: return null
        if (!RecoveryHosts.allowed(page, project)) return null
        return CatalogRecoveryPrefill(
            catalogId = match.entry.id,
            catalogName = match.entry.name,
            project = project,
            pageUrl = page,
            catalogCodenames = match.device.codenames().toList().sorted(),
            viaModelNumber = match.viaModelNumber,
        )
    }

    fun projectOf(entry: CatalogEntry): RecoveryProject? {
        val id = entry.id.trim().lowercase()
        val name = entry.name.trim().lowercase()
        val host = runCatching { URI(entry.downloadUrl.trim()).host?.lowercase() }.getOrNull().orEmpty()
        return when {
            id == "twrp" || name == "twrp" || host == "twrp.me" || host.endsWith(".twrp.me") ->
                RecoveryProject.TWRP
            id == "pitchblack" || id == "pbrp" || "pitchblack" in name || "pitchblack" in host ->
                RecoveryProject.PITCHBLACK
            else -> null
        }
    }

    /**
     * Official lookup always uses the probed phone. Catalog device names
     * are not a fetch key: a listing is not authority over which image
     * to download. Empty probed names yield an empty list (unknown is
     * not a wildcard).
     */
    fun lookupNames(probed: List<String>): List<String> =
        probed.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
}
