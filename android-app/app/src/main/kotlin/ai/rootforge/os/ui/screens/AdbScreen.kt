package ai.rootforge.os.ui.screens

import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.FilePickerField
import ai.rootforge.os.ui.ForgeCard
import ai.rootforge.os.ui.ScreenColumn
import ai.rootforge.os.usb.TargetMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val QUICK_COMMANDS = listOf(
    "getprop ro.product.model" to "Model",
    "pm list packages -3" to "User apps",
    "dumpsys battery" to "Battery",
    "df -h" to "Storage",
    "uname -a" to "Kernel",
    "id" to "Identity",
    "ls /sdcard" to "List /sdcard",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdbScreen(state: AppUiState, actions: AppActions) {
    val ready = state.ready(TargetMode.ADB)
    var command by rememberSaveable { mutableStateOf("") }
    var apk by remember { mutableStateOf<LibraryFile?>(null) }
    var pushFile by remember { mutableStateOf<LibraryFile?>(null) }
    var remotePath by rememberSaveable { mutableStateOf("/sdcard/Download/") }
    var pullPath by rememberSaveable { mutableStateOf("/sdcard/Download/") }
    var zip by remember { mutableStateOf<LibraryFile?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }

    ScreenColumn {
        ModeNotice(state, TargetMode.ADB, "Connect a target with USB debugging enabled from the Dashboard.")

        ForgeCard("Shell", "Run a command on the target", Icons.Filled.Terminal) {
            OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), label = { Text("Command") }, singleLine = true)
            ActionButton("Run", Icons.Filled.PlayArrow, ActionKind.Primary, enabled = ready && command.isNotBlank()) { actions.adbShell(command.trim()) }
            if (state.terminalHandoff) {
                ActionButton("Open full terminal", Icons.Filled.Terminal, ActionKind.Outline, enabled = state.terminalApp.isNotEmpty()) { actions.openTerminalApp() }
                if (state.terminalApp.isEmpty()) {
                    Text("Set the terminal app's package in Settings to enable this.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK_COMMANDS.forEach { (cmd, label) ->
                    AssistChip(onClick = { command = cmd; if (ready) actions.adbShell(cmd) }, label = { Text(label) })
                }
            }
        }

        ForgeCard("Install APK", "Pushes the file and runs pm install -r", Icons.Filled.Android) {
            FilePickerField("APK", state.library, apk, actions, { apk = it }, listOf(".apk"))
            ActionButton("Install on target", Icons.Filled.Download, ActionKind.Primary, enabled = ready && apk != null) { apk?.let(actions::adbInstall) }
        }

        ForgeCard("Push and pull files", icon = Icons.Filled.FolderOpen) {
            FilePickerField("File to push", state.library, pushFile, actions, { pushFile = it })
            OutlinedTextField(remotePath, { remotePath = it }, Modifier.fillMaxWidth(), label = { Text("Target path (absolute)") }, singleLine = true)
            ActionButton("Push", Icons.Filled.Upload, enabled = ready && pushFile != null && remotePath.startsWith("/")) {
                pushFile?.let { f -> actions.adbPush(f, if (remotePath.endsWith("/")) remotePath + f.name else remotePath) }
            }
            OutlinedTextField(pullPath, { pullPath = it }, Modifier.fillMaxWidth(), label = { Text("Path to pull from target") }, singleLine = true)
            ActionButton("Pull into library", Icons.Filled.Download, enabled = ready && pullPath.startsWith("/") && !pullPath.endsWith("/")) {
                actions.adbPull(pullPath, pullPath.substringAfterLast('/'))
            }
        }

        ForgeCard("Sideload", "For a target in recovery with Apply update from ADB selected", Icons.Filled.SystemUpdate) {
            FilePickerField("Update or module zip", state.library, zip, actions, { zip = it }, listOf(".zip"))
            ActionButton("Sideload", Icons.Filled.SystemUpdate, ActionKind.Primary, enabled = ready && zip != null) { zip?.let(actions::adbSideload) }
        }

        ForgeCard("Reboot", icon = Icons.Filled.PowerSettingsNew) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("System", enabled = ready) { actions.adbReboot("") }
                ActionButton("Recovery", enabled = ready) { actions.adbReboot("recovery") }
                ActionButton("Bootloader", enabled = ready) { actions.adbReboot("bootloader") }
                ActionButton("Fastbootd", enabled = ready) { actions.adbReboot("fastboot") }
                ActionButton("Sideload", enabled = ready) { actions.adbReboot("sideload") }
                ActionButton("EDL", kind = ActionKind.Danger, enabled = ready) { actions.adbReboot("edl") }
            }
        }

        ForgeCard("Logcat", "Streams into the output panel", Icons.Filled.Terminal) {
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("Filter (optional, e.g. *:W or ActivityManager:I *:S)") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.logcatRunning) ActionButton("Stop", Icons.Filled.Stop, ActionKind.Danger) { actions.stopLogcat() }
                else ActionButton("Start", Icons.Filled.PlayArrow, ActionKind.Primary, enabled = ready) { actions.startLogcat(filter.trim()) }
            }
        }
    }
}
