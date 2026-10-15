package ai.rootforge.os.ui.screens

import ai.rootforge.os.core.FileInspection
import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.fastboot.RebootTarget
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.FilePickerField
import ai.rootforge.os.ui.ForgeCard
import ai.rootforge.os.ui.KeyValueRow
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.ui.ScreenColumn
import ai.rootforge.os.ui.StatusPill
import ai.rootforge.os.usb.TargetMode
import ai.rootforge.os.usb.UsbScanFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private enum class FlashToolsPane(val label: String) {
    Guide("Guide"),
    Scan("Scan"),
    Adb("ADB"),
    Fastboot("Fastboot"),
    Inspector("Inspect"),
    Log("Log"),
}

/**
 * Guided hub inspired by host-phone flash toolkits. Every write still goes
 * through [ai.rootforge.os.core.TargetController] and the typed-phrase dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlashToolsScreen(state: AppUiState, actions: AppActions) {
    var pane by rememberSaveable { mutableStateOf(FlashToolsPane.Guide.name) }
    val current = FlashToolsPane.entries.firstOrNull { it.name == pane } ?: FlashToolsPane.Guide

    ScreenColumn {
        Text(
            "Host phone controls the target over USB OTG. Same ADB, fastboot and approval gates as the other screens — this page groups them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlashToolsPane.entries.forEach { p ->
                FilterChip(selected = p == current, onClick = { pane = p.name }, label = { Text(p.label) })
            }
        }
        when (current) {
            FlashToolsPane.Guide -> GuidePane()
            FlashToolsPane.Scan -> ScanPane(state, actions)
            FlashToolsPane.Adb -> AdbPane(state, actions)
            FlashToolsPane.Fastboot -> FastbootPane(state, actions)
            FlashToolsPane.Inspector -> InspectorPane(state, actions)
            FlashToolsPane.Log -> LogPane(state)
        }
    }
}

@Composable
private fun GuidePane() {
    CableSetupCard()
    ForgeCard("ADB on the target") {
        listOf(
            "Developer options → USB debugging on. USB mode = File transfer (not charge-only).",
            "The first time this host connects, accept the RSA dialog on the target. Settings shows the MD5 fingerprint that dialog displays.",
            "Then Scan → Grant USB access → Connect. Device check reads product, slot and build fingerprint.",
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    ForgeCard("Fastboot on the target") {
        listOf(
            "Reboot to the bootloader from the ADB pane, or hold the model's key combo.",
            "Flash and unlock always show a plan and ask you to type FLASH <partition> or UNLOCK. There is no skip.",
            "An image without a sidecar <name>.manifest.json is refused. The sidecar must name product or codename; if it also names a fingerprint, the target build must match. See docs/FLASH_TOOLS_PLAN.md.",
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ScanPane(state: AppUiState, actions: AppActions) {
    val scan = UsbScanFilter.partition(state.targets)
    ForgeCard("USB / OTG devices", "Android USB Host API — no root on this phone", Icons.Filled.Search) {
        ActionButton("Scan now", Icons.Filled.Search, ActionKind.Primary, enabled = state.busy == null) { actions.scanUsb() }
        if (scan.flashTargets.isEmpty()) {
            Text(
                state.emptyTargetsHint ?: "No ADB, fastboot or EDL device. Plug the target in with a data cable, then scan again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        scan.flashTargets.forEach { TargetRow(it, state, actions) }
        scan.ignored.forEach { ignored ->
            Text(
                "Ignored ${ignored.vidPid} (${ignored.product ?: ignored.manufacturer ?: "unrecognised"})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdbPane(state: AppUiState, actions: AppActions) {
    val ready = state.ready(TargetMode.ADB)
    var command by rememberSaveable { mutableStateOf("") }
    var pushFile by remember { mutableStateOf<LibraryFile?>(null) }
    var remotePath by rememberSaveable { mutableStateOf("/sdcard/Download/") }
    var pullPath by rememberSaveable { mutableStateOf("/sdcard/Download/") }
    var filter by rememberSaveable { mutableStateOf("") }

    ModeNotice(state, TargetMode.ADB, "Connect a target with USB debugging from Scan.")

    ForgeCard("Device check", "Product, slot, fingerprint — the same facts the flash gate uses", Icons.AutoMirrored.Filled.FactCheck) {
        ActionButton("Run device check", Icons.AutoMirrored.Filled.FactCheck, ActionKind.Primary, enabled = ready) { actions.adbDeviceCheck() }
        state.info?.takeIf { it.mode == TargetMode.ADB }?.rows?.take(8)?.forEach { (k, v) -> KeyValueRow(k, v) }
    }

    ForgeCard("Shell", icon = Icons.Filled.Terminal) {
        OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), label = { Text("Command") }, singleLine = true)
        ActionButton("Run", Icons.Filled.PlayArrow, ActionKind.Primary, enabled = ready && command.isNotBlank()) { actions.adbShell(command.trim()) }
    }

    ForgeCard("Packages", icon = Icons.Filled.Apps) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("All packages", enabled = ready) { actions.adbListPackages(false) }
            ActionButton("User apps", enabled = ready) { actions.adbListPackages(true) }
        }
    }

    ForgeCard("Push and pull", icon = Icons.Filled.FolderOpen) {
        FilePickerField("File to push", state.library, pushFile, actions, { pushFile = it })
        OutlinedTextField(remotePath, { remotePath = it }, Modifier.fillMaxWidth(), label = { Text("Target path (absolute)") }, singleLine = true)
        ActionButton("Push", Icons.Filled.Upload, enabled = ready && pushFile != null && remotePath.startsWith("/")) {
            pushFile?.let { f -> actions.adbPush(f, if (remotePath.endsWith("/")) remotePath + f.name else remotePath) }
        }
        OutlinedTextField(pullPath, { pullPath = it }, Modifier.fillMaxWidth(), label = { Text("Path to pull") }, singleLine = true)
        ActionButton("Pull into library", Icons.Filled.Download, enabled = ready && pullPath.startsWith("/") && !pullPath.endsWith("/")) {
            actions.adbPull(pullPath, pullPath.substringAfterLast('/'))
        }
    }

    ForgeCard("Screen", icon = Icons.Filled.PhotoCamera) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Screen info", Icons.Filled.Info, enabled = ready) { actions.adbScreenInfo() }
            ActionButton("Screenshot", Icons.Filled.PhotoCamera, enabled = ready) { actions.adbScreenshot() }
        }
    }

    ForgeCard("Logcat", "Holds the USB connection until you stop it", Icons.Filled.Terminal) {
        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("Filter (optional)") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.logcatRunning) ActionButton("Stop", Icons.Filled.Stop, ActionKind.Danger) { actions.stopLogcat() }
            else ActionButton("Start", Icons.Filled.PlayArrow, ActionKind.Primary, enabled = ready) { actions.startLogcat(filter.trim()) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FastbootPane(state: AppUiState, actions: AppActions) {
    val ready = state.ready(TargetMode.FASTBOOT)
    var partition by rememberSaveable { mutableStateOf("boot") }
    var image by remember { mutableStateOf<LibraryFile?>(null) }
    val vars = state.info?.takeIf { it.mode == TargetMode.FASTBOOT }?.rows?.toMap().orEmpty()

    ModeNotice(state, TargetMode.FASTBOOT, "Reboot the target to its bootloader, then Connect on Scan.")

    ForgeCard("Device and partitions", "Product ${vars["Product"] ?: "unknown"}, slot ${vars["Current slot"] ?: "unknown"}", Icons.Filled.Info) {
        ActionButton("Read product + partitions", Icons.Filled.Info, enabled = ready) { actions.fastbootPartitionInfo() }
        vars.entries.take(6).forEach { (k, v) -> KeyValueRow(k, v) }
    }

    ForgeCard("Flash a file", "Blocked unless the sidecar matches this device. Then type FLASH <partition>.", Icons.Filled.FlashOn) {
        OutlinedTextField(partition, { partition = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Partition") }, singleLine = true)
        FilePickerField("Image", state.library, image, actions, { image = it }, listOf(".img", ".bin"))
        ActionButton("Flash", Icons.Filled.FlashOn, ActionKind.Danger, enabled = ready && image != null && partition.isNotBlank()) {
            image?.let { actions.fastbootFlash(partition, it) }
        }
        NoticeCard(
            "A mismatch (wrong product, slot, fingerprint, or SHA-256) is logged as Refused: … and nothing is written. Import <image>.manifest.json next to the image. Details: docs/FLASH_TOOLS_PLAN.md.",
            danger = true,
        )
    }

    ForgeCard("Unlock bootloader", "Erases all data on the target", Icons.Filled.LockOpen) {
        NoticeCard(
            "Unlocking factory-resets the target, can trip Knox / Widevine / banking apps, and may void warranty. The target will also ask you to confirm with its hardware keys. Type UNLOCK in the dialog to continue. There is no skip.",
            danger = true,
        )
        ActionButton("Unlock…", Icons.Filled.LockOpen, ActionKind.Danger, enabled = ready) { actions.fastbootUnlock() }
    }

    ForgeCard("Reboot", icon = Icons.Filled.PowerSettingsNew) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("System", enabled = ready) { actions.fastbootReboot(RebootTarget.SYSTEM) }
            ActionButton("Bootloader", enabled = ready) { actions.fastbootReboot(RebootTarget.BOOTLOADER) }
        }
    }
}

@Composable
private fun InspectorPane(state: AppUiState, actions: AppActions) {
    var file by remember { mutableStateOf<LibraryFile?>(null) }
    var inspection by remember { mutableStateOf<FileInspection?>(null) }
    var expected by rememberSaveable { mutableStateOf("") }
    var verifyNote by remember { mutableStateOf<String?>(null) }

    ForgeCard("Image / APK inspector", "SHA-256 and sidecar claim. Does not talk to the target.", Icons.Filled.Verified) {
        FilePickerField("File", state.library, file, actions, {
            file = it
            inspection = it?.let(actions::inspectLibraryFile)
            verifyNote = null
        })
        ActionButton("Inspect", Icons.Filled.Search, enabled = file != null) {
            inspection = file?.let(actions::inspectLibraryFile)
        }
        inspection?.let { info ->
            StatusPill(info.kind.name.replace('_', ' '), MaterialTheme.colorScheme.primary)
            KeyValueRow("SHA-256", info.sha256)
            info.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            info.claim?.let { claim ->
                KeyValueRow("Claim product", claim.product ?: "—")
                KeyValueRow("Claim codename", claim.codename ?: "—")
                KeyValueRow("Claim slot", claim.slot ?: "—")
                KeyValueRow("Claim fingerprint", claim.fingerprint ?: "(declared manifest)")
            }
        }
        OutlinedTextField(expected, { expected = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Expected SHA-256 (hex)") }, singleLine = true)
        ActionButton("Verify hash", Icons.Filled.Verified, enabled = file != null && expected.isNotBlank()) {
            val check = file?.let { actions.verifySha256(it, expected) }
            verifyNote = check?.message
        }
        verifyNote?.let { NoticeCard(it, danger = it.contains("not match") || it.contains("empty") || it.contains("not a")) }
    }
}

@Composable
private fun LogPane(state: AppUiState) {
    ForgeCard("Command log", "Same redacted log as the output panel. Survives an app restart.") {
        Text(
            "${state.log.size} line(s) in memory. Share or clear from the output panel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.log.takeLast(12).forEach { line ->
            Text(line.text, style = MaterialTheme.typography.bodySmall)
        }
        if (state.log.isEmpty()) {
            Text("Nothing logged yet. Scan, run a device check, or flash (and be refused) to see lines here.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
