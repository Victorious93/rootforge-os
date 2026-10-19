package ai.rootforge.os.ui.screens

import ai.rootforge.os.bridge.CallerIdentity
import ai.rootforge.os.bridge.PairedController
import ai.rootforge.os.core.TerminalApp
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.ForgeCard
import ai.rootforge.os.ui.KeyValueRow
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.ui.ScreenColumn
import ai.rootforge.os.ui.theme.MonoStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(state: AppUiState, actions: AppActions) {
    ScreenColumn {
        ForgeCard("Connection", icon = Icons.Filled.Usb) {
            SwitchRow("Connect automatically", "Connect when a recognised device appears and USB access is already granted", state.autoConnect, actions::setAutoConnect)
        }

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
            KeyValueRow("Network", "Only the build-catalog Download button (https, raw.githubusercontent.com)")
            KeyValueRow("USB", state.transportCaption)
            Text(
                "Known limits: EDL is experimental and unverified on hardware; oversized non-sparse images are refused; " +
                    "ADB uses classic shell (no exit status framing); sideload needs a seekable file. See docs/ANDROID_APP.md.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
            supportingText = { Text(if (valid) "Default com.termux (Termux). Use VictorSuite's package if it has its own, shown in Android's app info." else "Not a valid Android package name.") },
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
