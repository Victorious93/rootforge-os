package ai.rootforge.os.ui

import ai.rootforge.os.core.ApprovalRequest
import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.LogLevel
import ai.rootforge.os.core.LogLine
import ai.rootforge.os.core.Progress
import ai.rootforge.os.core.humanSize
import ai.rootforge.os.ui.theme.ForgeColors
import ai.rootforge.os.ui.theme.MonoStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun ForgeCard(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

enum class ActionKind { Primary, Tonal, Outline, Danger }

@Composable
fun ActionButton(
    text: String,
    icon: ImageVector? = null,
    kind: ActionKind = ActionKind.Tonal,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    when (kind) {
        ActionKind.Primary -> Button(onClick, modifier, enabled) { content() }
        ActionKind.Tonal -> FilledTonalButton(onClick, modifier, enabled) { content() }
        ActionKind.Outline -> OutlinedButton(onClick, modifier, enabled) { content() }
        ActionKind.Danger -> Button(
            onClick, modifier, enabled,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
        ) { content() }
    }
}

@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun NoticeCard(text: String, modifier: Modifier = Modifier, danger: Boolean = false) {
    val color = if (danger) ForgeColors.Danger else ForgeColors.Warn
    Row(
        modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Warning, null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String = "Nothing available",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(emptyText) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (options.isEmpty()) DropdownMenuItem(text = { Text(emptyText) }, onClick = { open = false }, enabled = false)
            options.forEach { o ->
                DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}

/** Picks one of the imported files, or imports a new one through the system picker. */
@Composable
fun FilePickerField(
    label: String,
    files: List<LibraryFile>,
    selected: LibraryFile?,
    actions: AppActions,
    onSelect: (LibraryFile?) -> Unit,
    extensions: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val visible = if (extensions.isEmpty()) files else files.filter { f -> extensions.any { f.name.endsWith(it, ignoreCase = true) } }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DropdownField(
            label = label,
            options = visible,
            selected = selected?.takeIf { s -> visible.any { it.name == s.name } },
            optionLabel = { "${it.name}  (${humanSize(it.size)})" },
            onSelect = { onSelect(it) },
            modifier = Modifier.weight(1f),
            emptyText = "Import a file first",
        )
        FilledTonalButton(onClick = { actions.importFile { onSelect(it) } }, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Icon(Icons.Filled.UploadFile, null, Modifier.size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
            Text("Import")
        }
    }
}

@Composable
fun ProgressBanner(progress: Progress?, busy: String?, modifier: Modifier = Modifier) {
    if (progress == null && busy == null) return
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(progress?.label ?: busy.orEmpty(), style = MaterialTheme.typography.labelMedium)
            if (progress != null && progress.total > 0) {
                Text("${humanSize(progress.done)} / ${humanSize(progress.total)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (progress != null && progress.total > 0) LinearProgressIndicator(progress = { progress.fraction }, Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
fun TerminalPanel(
    lines: List<LogLine>,
    expanded: Boolean,
    onToggle: () -> Unit,
    actions: AppActions,
    modifier: Modifier = Modifier,
    fullHeight: Boolean = false,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, expanded) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Column(modifier.fillMaxWidth().background(ForgeColors.Terminal)) {
        if (!fullHeight) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("OUTPUT", style = MaterialTheme.typography.labelSmall, color = ForgeColors.Ember, fontWeight = FontWeight.Bold)
                Text(
                    lines.lastOrNull()?.text.orEmpty(),
                    Modifier.weight(1f).padding(horizontal = 10.dp),
                    style = MonoStyle, color = Color(0xFF8A96A3), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (expanded) {
                    IconButton(onClick = { actions.shareLog() }, Modifier.size(28.dp)) { Icon(Icons.Filled.Share, "Share log", Modifier.size(16.dp)) }
                    IconButton(onClick = { actions.clearLog() }, Modifier.size(28.dp)) { Icon(Icons.Filled.DeleteSweep, "Clear log", Modifier.size(16.dp)) }
                }
                Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, null, Modifier.size(20.dp))
            }
        }
        if (expanded || fullHeight) {
            LazyColumn(
                state = listState,
                modifier = (if (fullHeight) Modifier.weight(1f) else Modifier.height(190.dp)).fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                items(lines, key = { it.id }) { line ->
                    Text(
                        text = when (line.level) {
                            LogLevel.COMMAND -> "$ ${line.text}"
                            else -> line.text
                        },
                        style = MonoStyle,
                        color = when (line.level) {
                            LogLevel.COMMAND -> ForgeColors.Ember
                            LogLevel.OUTPUT -> Color(0xFFD5DBE2)
                            LogLevel.INFO -> ForgeColors.Steel
                            LogLevel.WARN -> ForgeColors.Warn
                            LogLevel.ERROR -> ForgeColors.Danger
                        },
                    )
                }
            }
        }
    }
}

/** The confirmation every destructive action goes through. A phrase, when present, must be typed exactly. */
@Composable
fun ApprovalDialog(request: ApprovalRequest, actions: AppActions) {
    var typed by rememberSaveable(request.id) { mutableStateOf("") }
    val phrase = request.phrase
    val ready = phrase == null || typed == phrase
    AlertDialog(
        onDismissRequest = { actions.answerApproval(request.id, false, "") },
        icon = { Icon(Icons.Filled.Warning, null, tint = if (request.destructive) ForgeColors.Danger else ForgeColors.Warn) },
        title = { Text(request.title) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.source != "RootForge OS") {
                    Text("Requested by ${request.source}", style = MaterialTheme.typography.labelMedium, color = ForgeColors.Steel)
                }
                request.lines.forEach { Text(it, style = if (it.contains("  ")) MonoStyle else MaterialTheme.typography.bodySmall) }
                if (phrase != null) {
                    Text("To continue, type exactly:", style = MaterialTheme.typography.labelMedium)
                    Text(phrase, style = MonoStyle, color = ForgeColors.Ember, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { actions.answerApproval(request.id, true, typed) },
                enabled = ready,
                colors = if (request.destructive) ButtonDefaults.buttonColors(containerColor = ForgeColors.Danger, contentColor = Color(0xFF2B0000)) else ButtonDefaults.buttonColors(),
            ) { Text(if (request.destructive) "Proceed" else "Allow") }
        },
        dismissButton = { TextButton(onClick = { actions.answerApproval(request.id, false, "") }) { Text("Cancel") } },
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}
