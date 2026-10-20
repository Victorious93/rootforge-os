package ai.rootforge.os.ui.screens

import ai.rootforge.os.guide.Block
import ai.rootforge.os.guide.CalloutKind
import ai.rootforge.os.guide.GuideDocument
import ai.rootforge.os.guide.GuideLibrary
import ai.rootforge.os.guide.GuideLink
import ai.rootforge.os.guide.Span
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.ui.SectionLabel
import ai.rootforge.os.ui.theme.ForgeColors
import ai.rootforge.os.ui.theme.MonoStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp

/**
 * The Root Guide: offline rooting documentation bundled with the app (see `ai.rootforge.os.guide`).
 * Reading only; nothing here touches the target or needs a connection.
 */
@Composable
fun GuideScreen(
    state: AppUiState,
    actions: AppActions,
    library: GuideLibrary = GuideLibrary.default,
    initialPage: String? = null,
) {
    // Visited pages as "id" or "id#anchor"; the last one is on screen and an empty list shows the index.
    var stack by rememberSaveable { mutableStateOf(listOfNotNull(initialPage)) }
    val current = stack.lastOrNull()
    val doc = current?.substringBefore('#')?.let(library::document)

    LaunchedEffect(current, doc == null) {
        if (current != null && doc == null) stack = stack.dropLast(1)
    }

    if (current == null || doc == null) {
        GuideIndex(library, actions, onOpen = { id, to -> stack = stack + (if (to != null) "$id#$to" else id) })
    } else {
        GuidePage(
            doc = doc,
            anchor = current.substringAfter('#', "").ifEmpty { null },
            onBack = { stack = stack.dropLast(1) },
            onLink = { target ->
                when (val link = GuideLink.parse(target)) {
                    is GuideLink.Page -> stack = stack + (if (link.anchor != null) "${link.id}#${link.anchor}" else link.id)
                    is GuideLink.Anchor -> stack = stack.dropLast(1) + "${doc.entry.id}#${link.anchor}"
                    is GuideLink.External -> actions.openUrl(link.url)
                    null -> Unit
                }
            },
        )
    }
}

@Composable
private fun GuideIndex(library: GuideLibrary, actions: AppActions, onOpen: (String, String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val manifest = library.manifest
    val hits = remember(query) { library.search(query) }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            NoticeCard(
                "Reference only. Unlocking a bootloader erases the phone, and rooting can void warranty, break banking " +
                    "and DRM apps, or leave a device unbootable. Back up first and read the guide for your exact model.",
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search the guide") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
            )
        }
        if (query.trim().length >= 2) {
            item { SectionLabel(if (hits.isEmpty()) "No matches" else "${hits.size} matching sections") }
            items(hits) { hit ->
                GuideCard(hit.entry.title + " › " + hit.heading, hit.snippet, hit.entry.origin == "rootforge") {
                    onOpen(hit.entry.id, hit.anchor)
                }
            }
        } else {
            manifest.sections.forEach { section ->
                item(key = "s-" + section.title) { SectionLabel(section.title) }
                items(section.entries, key = { it.id }) { e ->
                    GuideCard(e.title, e.summary, e.origin == "rootforge") { onOpen(e.id, null) }
                }
            }
            item {
                val src = manifest.source
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Credits")
                    Text(
                        "Guide pages adapted from ${src.name} (${src.license} licence), commit ${src.commit.take(10)}. " +
                            "The page marked RootForge was written for this app. The full app and module catalogue is on the website.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ActionButton("Open ${src.name} website", Icons.Filled.OpenInNew, ActionKind.Outline) { actions.openUrl(src.website) }
                }
            }
        }
    }
}

@Composable
private fun GuideCard(title: String, subtitle: String, rootForge: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                if (rootForge) Text("RootForge", style = MaterialTheme.typography.labelSmall, color = ForgeColors.Ember)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GuidePage(doc: GuideDocument, anchor: String?, onBack: () -> Unit, onLink: (String) -> Unit) {
    val listState: LazyListState = rememberLazyListState()
    // Item 0 is the header; block n is item n + 1.
    val anchorIndex = remember(doc, anchor) { anchor?.let { a -> doc.blocks.indexOfFirst { containsAnchor(it, a) } } ?: -1 }
    LaunchedEffect(doc.entry.id, anchor, anchorIndex) {
        if (anchorIndex >= 0) listState.scrollToItem(anchorIndex + 1) else if (anchor == null) listState.scrollToItem(0)
    }
    var contentsOpen by remember(doc) { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxWidth(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    Text("  Back")
                }
                val h2 = doc.headings.filter { it.level == 2 }
                if (h2.isNotEmpty()) {
                    TextButton(onClick = { contentsOpen = !contentsOpen }) {
                        Text("Contents (${h2.size})")
                        Icon(if (contentsOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                    }
                    if (contentsOpen) {
                        h2.forEach { h ->
                            Text(
                                h.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable { contentsOpen = false; onLink("#" + h.anchor) }.padding(vertical = 6.dp),
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        items(doc.blocks.size) { index -> MarkdownBlock(doc.blocks[index], onLink) }
        item { Box(Modifier.height(24.dp)) }
    }
}

private fun containsAnchor(block: Block, anchor: String): Boolean = when (block) {
    is Block.Heading -> block.anchor == anchor
    is Block.Callout -> block.blocks.any { containsAnchor(it, anchor) }
    is Block.Quote -> block.blocks.any { containsAnchor(it, anchor) }
    is Block.ListBlock -> block.items.any { item -> item.any { containsAnchor(it, anchor) } }
    else -> false
}

@Composable
internal fun MarkdownBlock(block: Block, onLink: (String) -> Unit) {
    when (block) {
        is Block.Heading -> Text(
            rich(block.spans, onLink),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            color = if (block.level <= 2) ForgeColors.Ember else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = if (block.level <= 2) 10.dp else 4.dp),
        )
        is Block.Paragraph -> Text(rich(block.spans, onLink), style = MaterialTheme.typography.bodyMedium)
        is Block.CodeBlock -> CodeView(block.code)
        is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEachIndexed { i, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (block.ordered) "${block.start + i}." else "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(if (block.ordered) 28.dp else 14.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.forEach { MarkdownBlock(it, onLink) }
                    }
                }
            }
        }
        is Block.Quote -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(3.dp).height(20.dp).background(MaterialTheme.colorScheme.outline))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { block.blocks.forEach { MarkdownBlock(it, onLink) } }
        }
        is Block.Callout -> CalloutView(block, onLink)
        is Block.Table -> TableView(block, onLink)
        Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun CodeView(code: String) {
    SelectionContainer {
        Text(
            code,
            style = MonoStyle,
            modifier = Modifier
                .fillMaxWidth()
                .background(ForgeColors.Terminal, RoundedCornerShape(10.dp))
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
            color = Color(0xFFE6EDF3),
            softWrap = false,
        )
    }
}

@Composable
private fun CalloutView(callout: Block.Callout, onLink: (String) -> Unit) {
    val color = when (callout.kind) {
        CalloutKind.TIP -> ForgeColors.Ok
        CalloutKind.INFO -> ForgeColors.Steel
        CalloutKind.NOTE, CalloutKind.DETAILS -> MaterialTheme.colorScheme.outline
        CalloutKind.WARNING -> ForgeColors.Warn
        CalloutKind.DANGER -> ForgeColors.Danger
    }
    val collapsible = callout.kind == CalloutKind.DETAILS
    var open by rememberSaveable { mutableStateOf(!collapsible) }
    val title = callout.title.ifBlank { callout.kind.label }
    Column(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().then(if (collapsible) Modifier.clickable { open = !open } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (collapsible) Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = color)
        }
        if (open) callout.blocks.forEach { MarkdownBlock(it, onLink) }
    }
}

@Composable
private fun TableView(table: Block.Table, onLink: (String) -> Unit) {
    // Columns share the width when each gets at least 120dp; wider tables scroll sideways.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columnWidth = maxOf(120.dp, (maxWidth - 2.dp) / table.header.size)
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState()),
        ) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                table.header.forEach { cell ->
                    Text(rich(cell, onLink), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(columnWidth).padding(8.dp))
                }
            }
            table.rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row {
                    row.forEach { cell -> Text(rich(cell, onLink), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(columnWidth).padding(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun rich(spans: List<Span>, onLink: (String) -> Unit): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    return buildAnnotatedString {
        for (s in spans) {
            val style = SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                fontFamily = if (s.code) FontFamily.Monospace else null,
                background = if (s.code) codeBackground else Color.Unspecified,
            )
            val link = s.link?.takeIf { GuideLink.parse(it) != null }
            if (link == null) {
                pushStyle(style); append(s.text); pop()
            } else {
                withLink(
                    LinkAnnotation.Clickable(
                        tag = link,
                        styles = TextLinkStyles(style.merge(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
                    ) { onLink(link) },
                ) { append(s.text) }
            }
        }
    }
}
