package ai.rootforge.os.ui.screens

import ai.rootforge.os.core.LinkState
import ai.rootforge.os.core.humanSize
import ai.rootforge.os.fastboot.RebootTarget
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
import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun modeColor(mode: TargetMode) = when (mode) {
    TargetMode.ADB -> ForgeColors.Ok
    TargetMode.FASTBOOT -> ForgeColors.Ember
    TargetMode.EDL -> ForgeColors.Danger
    TargetMode.UNKNOWN -> MaterialTheme.colorScheme.outline
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(state: AppUiState, actions: AppActions) {
    ScreenColumn {
        ForgeCard("Target device", state.transportCaption, Icons.Filled.Usb) {
            val link = state.link
            when (link) {
                is LinkState.Connected -> StatusPill("Connected: ${link.mode.label}", modeColor(link.mode))
                is LinkState.Connecting -> StatusPill(link.message, ForgeColors.Warn)
                is LinkState.Failed -> StatusPill("Failed: ${link.message}", ForgeColors.Danger)
                LinkState.Disconnected -> StatusPill("Not connected", MaterialTheme.colorScheme.outline)
            }
            if (state.targets.isEmpty()) {
                Text(
                    state.emptyTargetsHint ?: (
                        "No USB device detected. Plug the target into ${state.hostCaption} with a USB-C to USB-C cable, " +
                            "then set USB debugging on the target (ADB), or hold the key combo for bootloader/EDL."
                        ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.targets.forEach { TargetRow(it, state, actions) }
            if (state.link is LinkState.Connected) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Refresh", Icons.Filled.Refresh, enabled = state.busy == null) { actions.refreshInfo() }
                    ActionButton("Disconnect", Icons.Filled.LinkOff, ActionKind.Outline) { actions.disconnect() }
                }
            }
        }

        state.info?.let { info ->
            ForgeCard("Device information", info.mode.label) {
                info.rows.forEach { (k, v) -> KeyValueRow(k, v) }
            }
        }

        BuildCatalogCard(state.buildCatalog, state.info, busy = state.busy != null, actions)

        DesktopModeCard(state.desktopMode, actions)

        val mode = (state.link as? LinkState.Connected)?.mode
        if (mode == TargetMode.ADB || mode == TargetMode.FASTBOOT) {
            ForgeCard("Reboot target", "Switch the target to another mode", Icons.Filled.PowerSettingsNew) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode == TargetMode.ADB) {
                        ActionButton("System") { actions.adbReboot("") }
                        ActionButton("Recovery") { actions.adbReboot("recovery") }
                        ActionButton("Bootloader") { actions.adbReboot("bootloader") }
                        ActionButton("Fastbootd") { actions.adbReboot("fastboot") }
                    } else {
                        RebootTarget.entries.forEach { t -> ActionButton(t.label) { actions.fastbootReboot(t) } }
                    }
                }
            }
        }

        ForgeCard("Imported files", "Images, APKs and zips available to every screen") {
            if (state.library.isEmpty()) {
                Text("Nothing imported yet. Use Import on any screen to add a boot image, recovery, APK or zip.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.library.forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Text(humanSize(f.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { actions.deleteFile(f.name) }) { Icon(Icons.Filled.Delete, "Delete ${f.name}") }
                }
            }
            ActionButton("Import file", kind = ActionKind.Outline) { actions.importFile() }
        }

        if (state.targets.isEmpty()) CableSetupCard()
    }
}

@Composable
internal fun TargetRow(target: AttachedTarget, state: AppUiState, actions: AppActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(target.displayName, style = MaterialTheme.typography.titleSmall)
                Text("${target.vidPid}${target.serial?.let { "  serial $it" } ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(target.mode.label, modeColor(target.mode))
        }
        if (target.mode != TargetMode.UNKNOWN) {
            val connected = (state.link as? LinkState.Connected)?.mode == target.mode
            if (!target.hasPermission) {
                ActionButton("Grant USB access", Icons.Filled.Usb, ActionKind.Primary) { actions.requestUsbPermission(target) }
            } else if (!connected) {
                ActionButton("Connect (${target.mode.label})", Icons.Filled.Usb, ActionKind.Primary, enabled = state.busy == null) { actions.connect(target) }
            }
        }
    }
}

@Composable
fun CableSetupCard() {
    ForgeCard("Host-to-target cable setup", "This phone is the host; the other phone is the target") {
        listOf(
            "1. Use a USB-C to USB-C data cable (a charge-only cable shows nothing).",
            "2. Plug one end into this phone, the other into the target. This phone must act as USB host (most phones switch automatically; if not, use a USB-C OTG adapter on this phone's end).",
            "3. On the target enable Developer options, then USB debugging, and set USB mode to File transfer. For fastboot use the bootloader key combo; for EDL see the EDL screen.",
            "4. Accept Android's USB access prompt here, then tap Connect. On first ADB connection, accept the RSA key prompt on the target.",
            "5. If the target charges from this phone and drains it, charge this phone first or use a powered USB-C hub with pass-through charging.",
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        NoticeCard("The app talks USB directly (no adb/fastboot binaries; device work never uses the network). Another app holding the same USB device, or a cable that only negotiates charging, will block the connection.")
    }
}
