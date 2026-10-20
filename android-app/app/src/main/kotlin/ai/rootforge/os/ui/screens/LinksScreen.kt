package ai.rootforge.os.ui.screens

import ai.rootforge.os.links.DirectoryLink
import ai.rootforge.os.links.DirectoryLinks
import ai.rootforge.os.links.LinkSection
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.ForgeCard
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.ui.ScreenColumn
import ai.rootforge.os.ui.SectionLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * In-app directory of official https pages. Opens the system browser only;
 * nothing is downloaded or bundled.
 */
@Composable
fun LinksScreen(actions: AppActions, onOpenGlossary: () -> Unit = {}) {
    ScreenColumn {
        NoticeCard(
            "These are official project pages. Tapping a button opens the system browser. " +
                "RootForge does not download, install or vet anything from this list.",
        )
        ActionButton("Glossary", Icons.AutoMirrored.Filled.ListAlt, ActionKind.Outline) { onOpenGlossary() }
        LinkSection.entries.forEach { section ->
            val links = DirectoryLinks.all.filter { it.section == section }
            if (links.isNotEmpty()) {
                SectionLabel(section.label)
                links.forEach { LinkCard(it, actions) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkCard(link: DirectoryLink, actions: AppActions) {
    ForgeCard(link.name, link.section.label, Icons.Filled.Link) {
        Text(link.description, style = MaterialTheme.typography.bodyMedium)
        Text(link.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Open in browser", Icons.AutoMirrored.Filled.OpenInNew, ActionKind.Outline) { actions.openUrl(link.url) }
            link.extras.forEach { extra ->
                ActionButton(extra.label, Icons.AutoMirrored.Filled.OpenInNew, ActionKind.Outline) { actions.openUrl(extra.url) }
            }
        }
    }
}
