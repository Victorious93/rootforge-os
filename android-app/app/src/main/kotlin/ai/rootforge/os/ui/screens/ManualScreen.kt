package ai.rootforge.os.ui.screens

import ai.rootforge.os.guide.GuideLink
import ai.rootforge.os.manual.ManualLibrary
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.NoticeCard
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Offline how-to manual (bundled [docs/MANUAL.md]). Reading only; nothing here
 * touches the target. The GitHub button opens the same file in the browser.
 */
@Composable
fun ManualScreen(
    state: AppUiState,
    actions: AppActions,
    library: ManualLibrary = ManualLibrary.default,
    onOpenLinks: () -> Unit = {},
) {
    var contentsOpen by rememberSaveable { mutableStateOf(false) }
    var jumpTo by rememberSaveable { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val headings = library.headings.filter { it.level == 2 }

    LaunchedEffect(jumpTo, library.blocks.size) {
        val anchor = jumpTo ?: return@LaunchedEffect
        val index = library.blocks.indexOfFirst { block ->
            block is ai.rootforge.os.guide.Block.Heading && block.anchor == anchor
        }
        if (index >= 0) {
            // Header + notice + contents + divider sit in front of the blocks.
            list.animateScrollToItem(index + 3)
        }
        jumpTo = null
    }

    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ManualLibrary.TITLE, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "The same pages as docs/MANUAL.md, stored in the app so they work without a network." +
                        if (state.appVersion.isNotBlank()) " This build is ${state.appVersion}." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionButton(
                    "Open on GitHub",
                    Icons.AutoMirrored.Filled.OpenInNew,
                    ActionKind.Outline,
                ) { actions.openUrl(ManualLibrary.GITHUB_URL) }
                ActionButton(
                    "Links directory",
                    Icons.AutoMirrored.Filled.OpenInNew,
                    ActionKind.Outline,
                ) { onOpenLinks() }
            }
        }
        item {
            NoticeCard(
                "This is a how-to, not a remote-control panel. Flashing, unlock and module install still happen on their own screens and still need a typed confirmation.",
            )
        }
        item {
            if (headings.isNotEmpty()) {
                TextButton(onClick = { contentsOpen = !contentsOpen }) {
                    Text("Contents (${headings.size})")
                    Icon(if (contentsOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                }
                if (contentsOpen) {
                    headings.forEach { h ->
                        Text(
                            h.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    contentsOpen = false
                                    jumpTo = h.anchor
                                }
                                .padding(vertical = 6.dp),
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
        items(library.blocks) { block ->
            MarkdownBlock(block) { target ->
                when (val link = GuideLink.parse(target)) {
                    is GuideLink.Anchor -> jumpTo = link.anchor
                    is GuideLink.External -> actions.openUrl(link.url)
                    is GuideLink.Page, null -> Unit
                }
            }
        }
        item { Box(Modifier.height(24.dp)) }
    }
}
