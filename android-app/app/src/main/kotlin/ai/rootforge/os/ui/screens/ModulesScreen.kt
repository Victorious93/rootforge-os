package ai.rootforge.os.ui.screens

import ai.rootforge.os.core.FileDigest
import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.humanSize
import ai.rootforge.os.module.BuiltModule
import ai.rootforge.os.module.InstalledModule
import ai.rootforge.os.module.LocalModules
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
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var localOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var dryRun by rememberSaveable { mutableStateOf(true) }

    val localManager = localOverride?.let { n -> RootManager.entries.firstOrNull { it.name == n } }
        ?: state.localPreferred
        ?: RootManager.MAGISK

    LaunchedEffect(Unit) { actions.refreshLocalRoot() }

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
    val idle = state.busy == null
    val tabs = listOf("Create", "This phone", "Repositories")

    ScreenColumn {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, label ->
                SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, tabs.size)) { Text(label) }
            }
        }
        when (tab) {
            1 -> ThisPhoneTab(state, actions, localManager, { localOverride = it.name }, dryRun, { dryRun = it }, idle)
            2 -> RepositoriesTab(state, actions, installer, { installer = it }, localManager, dryRun, idle)
            else -> CreateTab(
                state, actions, spec, base, problems, bodies, { bodies = it }, editing, { editing = it },
                id, { id = it }, name, { name = it }, version, { version = it }, versionCode, { versionCode = it },
                author, { author = it }, description, { description = it }, target, { target = it },
                customize, { customize = it }, postFs, { postFs = it }, service, { service = it },
                bootCompleted, { bootCompleted = it }, action, { action = it },
                sysProp, { sysProp = it }, sepolicy, { sepolicy = it }, uninstall, { uninstall = it }, webroot, { webroot = it },
                built, { built = it }, zips, installer, { installer = it }, localManager, dryRun, idle,
            )
        }
    }
}

@Composable
private fun CreateTab(
    state: AppUiState,
    actions: AppActions,
    spec: ModuleSpec,
    base: ModuleSpec,
    problems: List<String>,
    bodies: Map<String, String>,
    onBodies: (Map<String, String>) -> Unit,
    editing: String,
    onEditing: (String) -> Unit,
    id: String, onId: (String) -> Unit,
    name: String, onName: (String) -> Unit,
    version: String, onVersion: (String) -> Unit,
    versionCode: String, onVersionCode: (String) -> Unit,
    author: String, onAuthor: (String) -> Unit,
    description: String, onDescription: (String) -> Unit,
    target: ModuleTarget, onTarget: (ModuleTarget) -> Unit,
    customize: Boolean, onCustomize: (Boolean) -> Unit,
    postFs: Boolean, onPostFs: (Boolean) -> Unit,
    service: Boolean, onService: (Boolean) -> Unit,
    bootCompleted: Boolean, onBootCompleted: (Boolean) -> Unit,
    action: Boolean, onAction: (Boolean) -> Unit,
    sysProp: Boolean, onSysProp: (Boolean) -> Unit,
    sepolicy: Boolean, onSepolicy: (Boolean) -> Unit,
    uninstall: Boolean, onUninstall: (Boolean) -> Unit,
    webroot: Boolean, onWebroot: (Boolean) -> Unit,
    built: BuiltModule?,
    onBuilt: (BuiltModule) -> Unit,
    zips: List<LibraryFile>,
    installer: RootManager,
    onInstaller: (RootManager) -> Unit,
    localManager: RootManager,
    dryRun: Boolean,
    idle: Boolean,
) {
    ForgeCard("Module details", "Writes module.prop and the installer shim", Icons.Filled.Extension) {
        OutlinedTextField(id, onId, Modifier.fillMaxWidth(), label = { Text("Module id") }, singleLine = true, supportingText = { Text("Letters, digits, . _ -  Starts with a letter") })
        OutlinedTextField(name, onName, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(version, onVersion, Modifier.weight(1f), label = { Text("Version") }, singleLine = true)
            OutlinedTextField(versionCode, { onVersionCode(it.filter(Char::isDigit)) }, Modifier.weight(1f), label = { Text("Version code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        OutlinedTextField(author, onAuthor, Modifier.fillMaxWidth(), label = { Text("Author") }, singleLine = true)
        OutlinedTextField(description, onDescription, Modifier.fillMaxWidth(), label = { Text("Description") }, minLines = 2)
        DropdownField("Target framework", ModuleTarget.entries, target, { it.label }, onTarget)
    }

    ForgeCard("Components", "Choose which lifecycle files to generate") {
        Toggle("customize.sh", "Runs once at install", customize, onCustomize)
        Toggle("post-fs-data.sh", "Early boot, blocking", postFs, onPostFs)
        Toggle("service.sh", "Late start service", service, onService)
        Toggle("boot-completed.sh", "Once Android has finished booting", bootCompleted, onBootCompleted)
        Toggle("action.sh", "Runs when you tap Action in the manager", action, onAction)
        Toggle("system.prop", "Property overrides", sysProp, onSysProp)
        Toggle("sepolicy.rule", "SELinux rules", sepolicy, onSepolicy)
        Toggle("uninstall.sh", "Cleanup on removal", uninstall, onUninstall)
        Toggle("webroot/", "WebUI page", webroot, onWebroot)
    }

    val editable = ModuleBuilder.EDITABLE.filter { ModuleBuilder.enabled(base, it) }
    if (editable.isNotEmpty()) {
        val current = if (editing in editable) editing else editable.first()
        ForgeCard("Script contents", "Leave a file blank to get a starter stub", Icons.Filled.Edit) {
            DropdownField("File", editable, current, { it }, onEditing)
            OutlinedTextField(
                value = bodies[current].orEmpty(),
                onValueChange = { onBodies(bodies + (current to it)) },
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
        ActionButton("Build flashable zip", Icons.Filled.Build, ActionKind.Primary, enabled = problems.isEmpty() && idle) {
            actions.buildModule(spec, onBuilt)
        }
        built?.let { b ->
            KeyValueRow("File", b.fileName)
            KeyValueRow("Size", humanSize(b.zip.size.toLong()))
            KeyValueRow("SHA-256", b.sha256.take(32) + "...")
            Text("Saved to the imported files list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val installable: LibraryFile? = built?.let { b -> zips.firstOrNull { it.name == b.fileName } }
        DropdownField("Install through", RootManager.entries, installer, { it.label }, onInstaller)
        ActionButton("Push and install on target", Icons.Filled.Download, enabled = installable != null && state.ready(TargetMode.ADB)) {
            installable?.let { actions.installModule(it, installer) }
        }
        if (state.localSu) {
            ActionButton(
                if (dryRun) "Dry-run on this phone" else "Install on this phone",
                Icons.Filled.PhoneAndroid,
                enabled = installable != null && idle,
            ) {
                installable?.let { actions.installModuleLocally(it, localManager, dryRun) }
            }
        }
        NoticeCard(
            "USB install runs su on the target through ADB. This-phone install uses su on the phone running the app " +
                "(Magisk / KernelSU / APatch). Dry-run is the default on the This phone tab. " +
                "LSPosed modules are APKs: ADB → Install APK, then enable them in LSPosed.",
        )
    }
}

@Composable
private fun ThisPhoneTab(
    state: AppUiState,
    actions: AppActions,
    localManager: RootManager,
    onManager: (RootManager) -> Unit,
    dryRun: Boolean,
    onDryRun: (Boolean) -> Unit,
    idle: Boolean,
) {
    var zip by remember { mutableStateOf<LibraryFile?>(null) }
    var sha by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(zip?.name, zip?.size) {
        sha = zip?.let { picked ->
            runCatching { withContext(Dispatchers.IO) { FileDigest.sha256Hex(picked.file) } }.getOrNull()
        }
    }

    if (!state.localSu) {
        NoticeCard("Installing with su on this host is only in the Android APK. On this computer, push the zip to a USB target instead.")
        return
    }

    NoticeCard(
        "This tab talks to the phone running RootForge, not a USB target. Needs Magisk, KernelSU or APatch (su). " +
            "LSPosed modules stay APKs: install them with ADB → Install APK and enable them inside LSPosed.",
    )

    ForgeCard("Root manager on this phone", "Auto-detected from installed packages; override if it is wrong", Icons.Filled.PhoneAndroid) {
        Text(
            state.localRootNote.ifBlank { "Tap List installed modules to also check /data/adb (needs su)." },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.localManagers.isNotEmpty()) {
            KeyValueRow("Installed", state.localManagers.joinToString { it.label })
        }
        DropdownField("Install through", RootManager.entries, localManager, { it.label }, onManager)
        localManager.caveat?.let { NoticeCard(it) }
    }

    ForgeCard("Installed modules", "Read-only list of /data/adb/modules", Icons.Filled.Search) {
        ActionButton("List installed modules", Icons.Filled.Search, enabled = idle) { actions.listLocalModules() }
        when {
            !state.localListed -> Text("Nothing listed yet. Listing asks for root once.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.localModules.isEmpty() -> Text("No modules in ${LocalModules.MODULES_DIR}.", style = MaterialTheme.typography.bodySmall)
            else -> state.localModules.forEach { ModuleRow(it) }
        }
    }

    ForgeCard("Install a zip on this phone", "Copies to /data/local/tmp then magisk --install-module / ksud module install / apd module install", Icons.Filled.Download) {
        FilePickerField("Module zip", state.library, zip, actions, { zip = it }, listOf(".zip"))
        zip?.let { picked ->
            KeyValueRow("File", picked.name)
            KeyValueRow("Size", humanSize(picked.size))
            KeyValueRow("SHA-256", sha ?: "hashing…")
        }
        DryRunSwitch(dryRun, onDryRun)
        val preview = remember(zip?.name, zip?.size, sha, localManager) {
            val picked = zip
            val hash = sha
            if (picked != null && hash != null && picked.file.absolutePath.startsWith("/")) {
                runCatching { LocalModules.plan(picked.file.absolutePath, picked.name, picked.size, hash, localManager) }.getOrNull()
            } else null
        }
        preview?.let {
            Text(it.displayCommand, style = MonoStyle)
            Text(LocalModules.NEVER_REBOOT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ActionButton(
            if (dryRun) "Show install command" else "Install on this phone",
            Icons.Filled.Download,
            if (dryRun) ActionKind.Primary else ActionKind.Danger,
            enabled = zip != null && idle,
        ) {
            zip?.let { actions.installModuleLocally(it, localManager, dryRun) }
        }
        NoticeCard(
            if (dryRun) "Dry-run is on: the next tap only logs the command. Turn it off, then type INSTALL MODULE, to actually install."
            else "Dry-run is off. The next tap asks you to type INSTALL MODULE, then runs su. RootForge will not reboot.",
        )
    }

    ForgeCard("Reboot this phone", "Separate from install; never runs by itself", Icons.Filled.RestartAlt) {
        ActionButton("Reboot this phone", Icons.Filled.RestartAlt, ActionKind.Danger, enabled = idle) { actions.rebootThisPhone() }
        Text("Modules that were just installed load after this reboot. Confirm in the dialog; nothing is typed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModuleRow(module: InstalledModule) {
    val flags = buildList {
        add(if (module.enabled) "enabled" else "disabled")
        if (module.updatePending) add("update pending")
        if (module.removePending) add("remove pending")
    }.joinToString(" · ")
    KeyValueRow(module.name.ifBlank { module.id }, "${module.version.ifBlank { "?" }} · $flags")
    if (module.description.isNotBlank()) {
        Text(module.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DryRunSwitch(dryRun: Boolean, onDryRun: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text("Dry run", style = MaterialTheme.typography.bodyMedium)
            Text("Shows the su command; does not install or reboot", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(dryRun, onDryRun)
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
private fun RepositoriesTab(
    state: AppUiState,
    actions: AppActions,
    installer: RootManager,
    onInstaller: (RootManager) -> Unit,
    localManager: RootManager,
    dryRun: Boolean,
    idle: Boolean,
) {
    var zip by remember { mutableStateOf<LibraryFile?>(null) }

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
        DropdownField("Install through", RootManager.entries, installer, { it.label }, onInstaller)
        ActionButton("Push and install on target", Icons.Filled.Download, enabled = zip != null && state.ready(TargetMode.ADB)) {
            zip?.let { actions.installModule(it, installer) }
        }
        if (state.localSu) {
            ActionButton(
                if (dryRun) "Dry-run on this phone" else "Install on this phone",
                Icons.Filled.PhoneAndroid,
                enabled = zip != null && idle,
            ) {
                zip?.let { actions.installModuleLocally(it, localManager, dryRun) }
            }
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
