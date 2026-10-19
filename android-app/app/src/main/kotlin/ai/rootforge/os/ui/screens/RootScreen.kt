package ai.rootforge.os.ui.screens

import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.core.humanSize
import ai.rootforge.os.root.BootImageInfo
import ai.rootforge.os.root.BootImageParser
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
import ai.rootforge.os.usb.TargetMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RootScreen(state: AppUiState, actions: AppActions) {
    var manager by rememberSaveable { mutableStateOf(RootManager.MAGISK) }
    var boot by remember { mutableStateOf<LibraryFile?>(null) }
    var inspected by remember { mutableStateOf<BootImageParser.Result?>(null) }
    var managerApk by remember { mutableStateOf<LibraryFile?>(null) }
    var patched by remember { mutableStateOf<LibraryFile?>(null) }
    var partition by rememberSaveable { mutableStateOf("boot") }
    val adbReady = state.ready(TargetMode.ADB)
    val fbReady = state.ready(TargetMode.FASTBOOT)

    ScreenColumn {
        ForgeCard("Root manager", "Patching runs inside the manager app on the target", Icons.Filled.Security) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RootManager.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = manager == m, onClick = { manager = m },
                        shape = SegmentedButtonDefaults.itemShape(i, RootManager.entries.size),
                    ) { Text(m.label) }
                }
            }
            Text(
                "RootForge does not bundle or reimplement ${manager.label}'s patcher. It moves the images and drives the " +
                    "target so you can patch with the official manager, then flashes the result.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            manager.homepage?.let { url ->
                ActionButton("${manager.label} releases", Icons.Filled.OpenInNew, ActionKind.Outline) { actions.openUrl(url) }
            }
            manager.caveat?.let { NoticeCard(it) }
        }

        ForgeCard("1. Boot image", "Stock boot.img or init_boot.img for the target's exact build", Icons.Filled.PhoneAndroid) {
            FilePickerField("Boot image", state.library, boot, actions, { boot = it; inspected = null }, listOf(".img"))
            ActionButton("Inspect header", Icons.Filled.Search, ActionKind.Outline, enabled = boot != null) {
                inspected = boot?.let(actions::inspectBoot)
            }
            when (val r = inspected) {
                is BootImageParser.Result.Ok -> BootInfo(r.info)
                is BootImageParser.Result.Invalid -> NoticeCard(r.reason, danger = true)
                null -> Unit
            }
        }

        ForgeCard("2. Install the manager on the target", "Skip if it is already installed (see Dashboard)", Icons.Filled.Download) {
            FilePickerField("${manager.label} APK", state.library, managerApk, actions, { managerApk = it }, listOf(".apk"))
            ActionButton("Install APK on target", Icons.Filled.Download, enabled = adbReady && managerApk != null) { managerApk?.let(actions::adbInstall) }
        }

        ForgeCard("3. Patch on the target", "Needs ADB; the manager does the patching", Icons.Filled.Upload) {
            ModeNotice(state, TargetMode.ADB, "Reboot the target to Android first.")
            ActionButton("Push boot image to target", Icons.Filled.Upload, enabled = adbReady && boot != null) { boot?.let(actions::stageBoot) }
            ActionButton("Open ${manager.label} on target", Icons.Filled.PlayArrow, enabled = adbReady) { actions.launchManager(manager) }
            manager.patchSteps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
            ActionButton("Fetch patched image", Icons.Filled.Download, ActionKind.Primary, enabled = adbReady) {
                actions.fetchPatched(manager) { patched = it }
            }
            patched?.let { Text("Patched image ready: ${it.name} (${humanSize(it.size)})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) }
        }

        ForgeCard("4. Flash the patched image", "Fastboot, with a plan and typed confirmation", Icons.Filled.FlashOn) {
            FilePickerField("Patched image", state.library, patched, actions, { patched = it }, listOf(".img"))
            DropdownField("Partition", listOf("boot", "init_boot", "vendor_boot"), partition, { it }, { partition = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Reboot to bootloader", enabled = adbReady) { actions.adbReboot("bootloader") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Boot once", Icons.Filled.PlayArrow, ActionKind.Outline, enabled = fbReady && patched != null) { patched?.let(actions::fastbootBoot) }
                ActionButton("Flash", Icons.Filled.FlashOn, ActionKind.Danger, enabled = fbReady && patched != null) { patched?.let { actions.fastbootFlash(partition, it) } }
            }
            NoticeCard(
                "Which partition: devices launched with Android 13 or newer on a GKI kernel usually patch init_boot; older ones patch boot. " +
                    "Use the same partition the stock image came from. Some devices also need vbmeta flashed with verification disabled; " +
                    "this app does not do that for you.",
            )
        }
    }
}

@Composable
private fun BootInfo(info: BootImageInfo) {
    Text("Valid boot image", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
    KeyValueRow("Header version", info.headerVersion.toString())
    KeyValueRow("Kernel", humanSize(info.kernelSize))
    KeyValueRow("Ramdisk", humanSize(info.ramdiskSize))
    KeyValueRow("OS version", info.osVersion ?: "not recorded")
    KeyValueRow("Patch level", info.osPatchLevel ?: "not recorded")
    KeyValueRow("AVB footer", if (info.hasAvbFooter) "present (signed)" else "not found")
    KeyValueRow("Command line", info.cmdline.ifBlank { "empty" }.take(120))
}
