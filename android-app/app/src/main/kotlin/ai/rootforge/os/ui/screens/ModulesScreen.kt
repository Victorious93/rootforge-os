package ai.rootforge.os.ui.screens

import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.humanSize
import ai.rootforge.os.module.BuiltModule
import ai.rootforge.os.module.ModuleBuilder
import ai.rootforge.os.module.ModuleRepo
import ai.rootforge.os.module.ModuleRepos
import ai.rootforge.os.module.RepoKind
import ai.rootforge.os.module.ModuleSpec
import ai.rootforge.os.module.ModuleTarget
import ai.rootforge.os.root.RootManager
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.DropdownField
import ai.rootforge.os.ui.FilePickerField
import ai.rootforge.os.ui.ForgeCard
import ai.rootforge.os.ui.KeyValueRow
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.ui.ScreenColumn
import ai.rootforge.os.ui.SectionLabel
import ai.rootforge.os.ui.theme.MonoStyle
import ai.rootforge.os.usb.TargetMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun ModulesScreen(state: AppUiState, actions: AppActions, startTab: Int = 0) {
    var id by rememberSaveable { mutableStateOf("my_module") }
    var name by rememberSaveable { mutableStateOf("My Module") }
    var version by rememberSaveable { mutableStateOf("v1.0") }
    var versionCode by rememberSaveable { mutableStateOf("1") }
    var author by rememberSaveable { mutableStateOf("RootForge") }
    var description by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf(ModuleTarget.MAGISK) }
    var customize by rememberSaveable { mutableStateOf(true) }
    var postFs by rememberSaveable { mutableStateOf(false) }
    var service by rememberSaveable { mutableStateOf(true) }
    var sysProp by rememberSaveable { mutableStateOf(false) }
    var sepolicy by rememberSaveable { mutableStateOf(false) }
    var uninstall by rememberSaveable { mutableStateOf(false) }
    var webroot by rememberSaveable { mutableStateOf(false) }
    var bootCompleted by rememberSaveable { mutableStateOf(false) }
    var action by rememberSaveable { mutableStateOf(false) }
    var bodies by rememberSaveable { mutableStateOf<Map<String, String>>(LinkedHashMap()) }
    var editing by rememberSaveable { mutableStateOf("service.sh") }
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var built by remember { mutableStateOf<BuiltModule?>(null) }
    var installer by rememberSaveable { mutableStateOf(RootManager.MAGISK) }

    val base = ModuleSpec(
        id = id, name = name, version = version, versionCode = versionCode.toIntOrNull() ?: 0, author = author,
        description = description, target = target, customizeSh = customize, postFsDataSh = postFs, serviceSh = service,
        systemProp = sysProp, sepolicyRule = sepolicy, uninstallSh = uninstall, webroot = webroot,
        actionSh = action, bootCompletedSh = bootCompleted,
    )
    // Text typed for a file that was later switched off is kept in the editor but not built.
    val spec = base.copy(bodies = bodies.filterKeys { ModuleBuilder.enabled(base, it) })
    val problems = ModuleBuilder.validate(spec)
    val zips = state.library.filter { it.name.endsWith(".zip", true) }

    ScreenColumn {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Create", "Repositories").forEachIndexed { i, label ->
                SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
            }
        }
        if (tab == 1) {
            RepositoriesTab(state, actions)
            return@ScreenColumn
        }
        ForgeCard("Module details", "Writes module.prop and the installer shim", Icons.Filled.Extension) {
            OutlinedTextField(id, { id = it }, Modifier.fillMaxWidth(), label = { Text("Module id") }, singleLine = true, supportingText = { Text("Letters, digits, . _ -  Starts with a letter") })
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(version, { version = it }, Modifier.weight(1f), label = { Text("Version") }, singleLine = true)
                OutlinedTextField(versionCode, { versionCode = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Version code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text("Author") }, singleLine = true)
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Description") }, minLines = 2)
            DropdownField("Target framework", ModuleTarget.entries, target, { it.label }, { target = it })
        }

        ForgeCard("Components", "Choose which lifecycle files to generate") {
            Toggle("customize.sh", "Runs once at install", customize) { customize = it }
            Toggle("post-fs-data.sh", "Early boot, blocking", postFs) { postFs = it }
            Toggle("service.sh", "Late start service", service) { service = it }
            Toggle("boot-completed.sh", "Once Android has finished booting", bootCompleted) { bootCompleted = it }
            Toggle("action.sh", "Runs when you tap Action in the manager", action) { action = it }
            Toggle("system.prop", "Property overrides", sysProp) { sysProp = it }
            Toggle("sepolicy.rule", "SELinux rules", sepolicy) { sepolicy = it }
            Toggle("uninstall.sh", "Cleanup on removal", uninstall) { uninstall = it }
            Toggle("webroot/", "WebUI page", webroot) { webroot = it }
        }

        val editable = ModuleBuilder.EDITABLE.filter { ModuleBuilder.enabled(base, it) }
        if (editable.isNotEmpty()) {
            val current = if (editing in editable) editing else editable.first()
            ForgeCard("Script contents", "Leave a file blank to get a starter stub", Icons.Filled.Edit) {
                DropdownField("File", editable, current, { it }, { editing = it })
                OutlinedTextField(
                    value = bodies[current].orEmpty(),
                    onValueChange = { bodies = bodies + (current to it) },
                    modifier = Modifier.fillMaxWidth(), minLines = 6, textStyle = MonoStyle,
                    label = { Text(current) },
                    supportingText = { Text("Up to ${ModuleBuilder.MAX_BODY_BYTES / 1024} KiB. Windows line endings are converted.") },
                )
                NoticeCard("This text runs as root on the target. RootForge does not check it. Test on a spare device and keep a way to recover from a bootloop.")
            }
        }

        ForgeCard("Preview", icon = Icons.Filled.Visibility) {
            Text(if (problems.isEmpty()) ModuleBuilder.modulePropText(spec) else "module.prop is not valid yet", style = MonoStyle)
            problems.forEach { NoticeCard(it, danger = true) }
            ModuleBuilder.compatibilityNotes(spec).forEach { NoticeCard(it) }
        }

        ForgeCard("Build and install", icon = Icons.Filled.Build) {
            ActionButton("Build flashable zip", Icons.Filled.Build, ActionKind.Primary, enabled = problems.isEmpty() && state.busy == null) {
                actions.buildModule(spec) { built = it }
            }
            built?.let { b ->
                KeyValueRow("File", b.fileName)
                KeyValueRow("Size", humanSize(b.zip.size.toLong()))
                KeyValueRow("SHA-256", b.sha256.take(32) + "...")
                Text("Saved to the imported files list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val installable: LibraryFile? = built?.let { b -> zips.firstOrNull { it.name == b.fileName } }
            DropdownField("Install through", RootManager.entries, installer, { it.label }, { installer = it })
            ActionButton("Push and install on target", Icons.Filled.Download, enabled = installable != null && state.ready(TargetMode.ADB)) {
                installable?.let { actions.installModule(it, installer) }
            }
            NoticeCard(
                "Installing runs su on the target through ADB, so the manager must be active and grant root to the shell. " +
                    "Otherwise install the zip from the manager app or a recovery (ADB screen: Sideload).",
            )
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun RepositoriesTab(state: AppUiState, actions: AppActions) {
    var zip by remember { mutableStateOf<LibraryFile?>(null) }
    var installer by rememberSaveable { mutableStateOf(RootManager.MAGISK) }

    NoticeCard(
        "RootForge only links to these places. It does not download, index or check anything from them, and a module " +
            "runs scripts as root on the target. Prefer modules whose source you can read, and read it.",
    )
    RepoKind.entries.forEach { kind ->
        val repos = ModuleRepos.all.filter { it.kind == kind }
        if (repos.isNotEmpty()) {
            SectionLabel(kind.label)
            repos.forEach { RepoCard(it, actions) }
        }
    }

    ForgeCard("Install a downloaded module", "Download in your browser, then import the zip here", Icons.Filled.Download) {
        FilePickerField("Module zip", state.library, zip, actions, { zip = it }, listOf(".zip"))
        DropdownField("Install through", RootManager.entries, installer, { it.label }, { installer = it })
        ActionButton("Push and install on target", Icons.Filled.Download, enabled = zip != null && state.ready(TargetMode.ADB)) {
            zip?.let { actions.installModule(it, installer) }
        }
        Text(
            "You will be asked to type a confirmation that shows the zip's SHA-256. LSPosed modules are APKs: install them " +
                "with ADB → Install APK and enable them inside LSPosed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RepoCard(repo: ModuleRepo, actions: AppActions) {
    ForgeCard(repo.name, repo.supports.joinToString(" · ") { it.label }, Icons.Filled.Public) {
        Text(repo.description, style = MaterialTheme.typography.bodyMedium)
        Text(
            if (repo.checked.isNotBlank()) "Checked ${repo.checked}" else "Not checked",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (repo.note.isNotBlank()) Text(repo.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ActionButton("Open in browser", Icons.Filled.OpenInNew, ActionKind.Outline) { actions.openUrl(repo.url) }
    }
}
