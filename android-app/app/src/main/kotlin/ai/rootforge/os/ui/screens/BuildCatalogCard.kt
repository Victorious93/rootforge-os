package ai.rootforge.os.ui.screens

import ai.rootforge.os.catalog.BuildKind
import ai.rootforge.os.catalog.BuildLookup
import ai.rootforge.os.catalog.BuildMatch
import ai.rootforge.os.catalog.LoadedCatalog
import ai.rootforge.os.core.TargetInfo
import ai.rootforge.os.fastboot.TargetIdentity
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.ForgeCard
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.ui.StatusPill
import ai.rootforge.os.ui.theme.ForgeColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Community builds that list the connected target, from the Custom RR catalog.
 * Informational only: nothing on this card can start or allow a flash.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuildCatalogCard(catalog: LoadedCatalog?, info: TargetInfo?, busy: Boolean, actions: AppActions) {
    ForgeCard("Builds for this device", "ROMs, recoveries and root tools that Custom RR lists for this codename", Icons.Filled.Storefront) {
        if (catalog == null) {
            Text(
                "No catalog yet. Download it from Custom RR on GitHub, or import a catalog.json / freshness.json file.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val target = info?.let { TargetIdentity.fromTargetInfo(it) }
            val matches = remember(catalog, target) { target?.let { BuildLookup.forTarget(it, catalog.catalog, catalog.freshness) }.orEmpty() }
            when {
                target == null || target.names().isEmpty() ->
                    Text("Connect a target to see which builds list its codename.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                matches.isEmpty() ->
                    Text(
                        "No catalog entry lists ${target.names().joinToString(" / ")}. The catalog matches exact codenames only.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else -> matches.forEach { BuildMatchRow(it, actions) }
            }
            Text(
                listOfNotNull(catalog.source, catalog.catalog.generatedAt?.let { "catalog generated $it" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(if (catalog == null) "Download" else "Update", Icons.Filled.CloudDownload, enabled = !busy) { actions.downloadBuildCatalog() }
            ActionButton("Import file", Icons.Filled.FileOpen, ActionKind.Outline, enabled = !busy) { actions.importBuildCatalog() }
            if (catalog != null) ActionButton("Remove", kind = ActionKind.Outline, enabled = !busy) { actions.clearBuildCatalog() }
        }
        NoticeCard("Information only. A listing here does not allow a flash: every image still needs a matching manifest. Download contacts raw.githubusercontent.com only when you tap it.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuildMatchRow(match: BuildMatch, actions: AppActions) {
    val entry = match.entry
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(
                        match.kind.label,
                        "unofficial".takeIf { entry.unofficial },
                        match.freshness?.lastBuild?.takeIf { it.isNotBlank() }?.let { "last build $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            match.freshness?.status?.takeIf { it.isNotBlank() }?.let { StatusPill(it, freshnessColor(it)) }
        }
        if (entry.warning.isNotBlank()) NoticeCard(entry.warning)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            entry.safeDownloadUrl()?.let { url -> ActionButton("Download page", Icons.Filled.OpenInBrowser, ActionKind.Outline) { actions.openUrl(url) } }
            entry.safeForumUrl()?.let { url -> ActionButton("Forum", kind = ActionKind.Outline) { actions.openUrl(url) } }
        }
    }
}

private val BuildKind.label: String
    get() = when (this) {
        BuildKind.ROM -> "ROM"
        BuildKind.RECOVERY -> "Recovery"
        BuildKind.ROOT -> "Root"
    }

@Composable
private fun freshnessColor(status: String) = when (status.lowercase()) {
    "active" -> ForgeColors.Ok
    "monthly" -> ForgeColors.Steel
    "stale" -> ForgeColors.Warn
    "abandoned", "discontinued", "archived" -> ForgeColors.Danger
    else -> MaterialTheme.colorScheme.outline
}
