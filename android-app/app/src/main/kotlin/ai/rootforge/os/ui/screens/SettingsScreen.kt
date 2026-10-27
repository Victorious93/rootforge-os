package ai.rootforge.os.ui.screens

import ai.rootforge.os.bridge.CallerIdentity
import ai.rootforge.os.bridge.PairedController
import ai.rootforge.os.core.TerminalApp
import ai.rootforge.os.shizuku.ShizukuCopy
import ai.rootforge.os.shizuku.ShizukuState
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.ForgeCard
import ai.rootforge.os.ui.KeyValueRow
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.ui.ScreenColumn
import ai.rootforge.os.ui.StatusPill
import ai.rootforge.os.ui.theme.ForgeColors
import ai.rootforge.os.ui.theme.MonoStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(state: AppUiState, actions: AppActions, onOpenHelp: () -> Unit = {}, onOpenLinks: () -> Unit = {}) {
    ScreenColumn {
        ForgeCard("Connection", icon = Icons.Filled.Usb) {
            SwitchRow("Connect automatically", "Connect when a recognised device appears and USB access is already granted", state.autoConnect, actions::setAutoConnect)
        }

        if (state.shizuku != null) ShizukuCard(state, actions)

        if (state.terminalHandoff) TerminalAppCard(state, actions)

        ForgeCard("ADB identity", "This phone's RSA key, shown on the target when you allow USB debugging", Icons.Filled.Key) {
            KeyValueRow("Fingerprint", state.adbFingerprint.ifBlank { "not generated yet" })
            ActionButton("Generate a new key", kind = ActionKind.Outline) { actions.resetAdbKey() }
            Text("The key is stored in this app's private storage. A new key makes every target ask for authorisation again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        ForgeCard("External control", "Let droidcommand-ai (or another paired app) send commands", Icons.Filled.Hub) {
            SwitchRow("Allow external controllers", "Off by default. Even when on, an app can do nothing until you pair it below.", state.bridgeEnabled, actions::setBridgeEnabled)
            KeyValueRow("Node id", state.nodeId.ifBlank { "unknown" })
            if (state.pending.isNotEmpty()) {
                Text("Waiting for approval", style = MaterialTheme.typography.titleSmall)
                state.pending.forEach { PendingRow(it, state.paired, actions) }
            }
            val stale = state.pending.map { it.packageName }.toSet()
            val activePaired = state.paired.filterNot { it.packageName in stale }
            if (activePaired.isNotEmpty()) {
                Text("Paired controllers", style = MaterialTheme.typography.titleSmall)
                activePaired.forEach { PairedRow(it, actions) }
            }
            if (state.pending.isEmpty() && state.paired.isEmpty()) {
                Text(
                    "No controller has asked to pair yet. Copy the node id, bind droidcommand-ai on " +
                        "action ai.rootforge.os.action.BRIDGE (debug RootForge package is ai.rootforge.os.debug) " +
                        "and send rootforge.capabilities.get. The caller appears here even if this switch is still off; " +
                        "tap Pair before it can do anything.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NoticeCard("Reads are allowed after pairing. Anything that runs a command or changes the target asks you on this phone every time, and writes need a typed confirmation. No setting turns that off.")
        }

        ForgeCard("About", icon = Icons.Filled.Info) {
            KeyValueRow("Version", state.appVersion)
            KeyValueRow("Network", "Catalog Download, plus Custom Recovery lookup/download (https, only when tapped)")
            KeyValueRow("USB", state.transportCaption)
            KeyValueRow("Fleet theme", state.fleetTheme.message)
            ActionButton("Open the manual", kind = ActionKind.Primary) { onOpenHelp() }
            ActionButton("Links directory", kind = ActionKind.Outline) { onOpenLinks() }
            if (state.shizuku != null) {
                KeyValueRow("Shizuku", "${ShizukuCopy.statusTitle(state.shizuku)} — optional, this phone only, read-only")
            }
            Text(
                "Known limits: EDL is experimental and unverified on hardware; oversized non-sparse images are refused; " +
                    "ADB uses classic shell (no exit status framing); sideload needs a seekable file. See docs/ANDROID_APP.md.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShizukuCard(state: AppUiState, actions: AppActions) {
    val status = state.shizuku ?: return
    ForgeCard(ShizukuCopy.SECTION_TITLE, "Optional privileged reads on this phone (Sui counts as the same thing)", Icons.Filled.AdminPanelSettings) {
        StatusPill(ShizukuCopy.statusTitle(status), shizukuColor(status))
        Text(ShizukuCopy.statusBody(status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Refresh", kind = ActionKind.Outline, enabled = !state.shizukuBusy) { actions.refreshShizuku() }
            when (status) {
                ShizukuState.NOT_INSTALLED -> {
                    ActionButton("How to install Shizuku", kind = ActionKind.Primary) { actions.openUrl(ShizukuCopy.DOWNLOAD) }
                    ActionButton("Sui (rooted)", kind = ActionKind.Outline) { actions.openUrl(ShizukuCopy.SUI) }
                }
                ShizukuState.NOT_RUNNING -> {
                    ActionButton("Open Shizuku", kind = ActionKind.Primary) { actions.openShizukuApp() }
                    ActionButton("How to start it", kind = ActionKind.Outline) { actions.openUrl(ShizukuCopy.DOWNLOAD) }
                }
                ShizukuState.DENIED -> {
                    ActionButton(ShizukuCopy.REQUEST_BUTTON, kind = ActionKind.Primary, enabled = !state.shizukuBusy) { actions.requestShizukuPermission() }
                }
                ShizukuState.GRANTED -> Unit
            }
        }
        if (status == ShizukuState.GRANTED) {
            Text("Read-only lookups on this phone — each one asks for confirmation", style = MaterialTheme.typography.titleSmall)
            HostLookupRow(state, actions)
        }
        if (state.shizukuResult.isNotBlank()) {
            Text(
                state.shizukuResult,
                style = MonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        NoticeCard("This path never flashes, unlocks, wipes or writes. Every privileged lookup asks for confirmation first. Output stays on this screen and is not copied into the shared command log.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostLookupRow(state: AppUiState, actions: AppActions) {
    var pkg by rememberSaveable { mutableStateOf("") }
    var namespace by rememberSaveable { mutableStateOf("secure") }
    var settingKey by rememberSaveable { mutableStateOf("") }
    var prop by rememberSaveable { mutableStateOf("ro.product.model") }
    val ready = !state.shizukuBusy
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Device info", enabled = ready) { actions.shizukuDeviceInfo() }
        ActionButton("List packages", enabled = ready) { actions.shizukuListPackages(false) }
        ActionButton("User apps", enabled = ready) { actions.shizukuListPackages(true) }
        ActionButton("Block devices", enabled = ready) { actions.shizukuListBlockDevices() }
    }
    OutlinedTextField(pkg, { pkg = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Package name") })
    ActionButton("Package info", enabled = ready && pkg.isNotBlank()) { actions.shizukuPackageInfo(pkg.trim()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(namespace, { namespace = it }, Modifier.weight(0.35f), singleLine = true, label = { Text("settings ns") })
        OutlinedTextField(settingKey, { settingKey = it }, Modifier.weight(0.65f), singleLine = true, label = { Text("settings key") })
    }
    ActionButton("settings get", enabled = ready && settingKey.isNotBlank()) { actions.shizukuSettingsGet(namespace.trim(), settingKey.trim()) }
    OutlinedTextField(prop, { prop = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("getprop key") })
    ActionButton("getprop", enabled = ready && prop.isNotBlank()) { actions.shizukuGetprop(prop.trim()) }
}

@Composable
private fun shizukuColor(status: ShizukuState) = when (status) {
    ShizukuState.NOT_INSTALLED -> MaterialTheme.colorScheme.outline
    ShizukuState.NOT_RUNNING -> ForgeColors.Warn
    ShizukuState.DENIED -> ForgeColors.Ember
    ShizukuState.GRANTED -> ForgeColors.Ok
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun PendingRow(c: CallerIdentity, paired: List<PairedController>, actions: AppActions) {
    val certChanged = paired.any { it.packageName == c.packageName }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(c.packageName, style = MaterialTheme.typography.bodyMedium)
        Text("certificate sha256 ${c.certSha256.take(24)}...", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (certChanged) {
            Text(
                "This package was paired with a different signing certificate. Pair again to replace it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Pair", kind = ActionKind.Primary) { actions.approveController(c) }
            ActionButton("Dismiss", kind = ActionKind.Outline) { actions.dismissController(c) }
        }
    }
}

@Composable
private fun PairedRow(c: PairedController, actions: AppActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(c.packageName, style = MaterialTheme.typography.bodyMedium)
            Text("sha256 ${c.certSha256.take(24)}...", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ActionButton("Revoke", kind = ActionKind.Danger) { actions.revokeController(c.packageName) }
    }
}

@Composable
private fun TerminalAppCard(state: AppUiState, actions: AppActions) {
    var text by remember(state.terminalApp) { mutableStateOf(state.terminalApp) }
    val trimmed = text.trim()
    val valid = trimmed.isEmpty() || TerminalApp.isValidPackage(trimmed)
    ForgeCard("Terminal app", "A full terminal for tasks the one-command shell cannot do", Icons.Filled.Terminal) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Package name") }, isError = !valid,
            supportingText = { Text(if (valid) "Default com.termux — VictorSuite keeps that Termux id. Change it only if you use a different terminal app." else "Not a valid Android package name.") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Save", kind = ActionKind.Outline, enabled = valid && trimmed != state.terminalApp) { actions.setTerminalApp(trimmed) }
            ActionButton("Open it", enabled = state.terminalApp.isNotEmpty()) { actions.openTerminalApp() }
        }
        Text(
            "RootForge only launches the app. It passes no command, serial or file, and the default is only Termux's package; clear the field to turn the button off.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
