package ai.rootforge.os.ui.screens

import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.fastboot.RebootTarget
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.FilePickerField
import ai.rootforge.os.ui.ForgeCard
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.ui.ScreenColumn
import ai.rootforge.os.usb.TargetMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
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

private val COMMON_PARTITIONS = listOf("boot", "init_boot", "vendor_boot", "recovery", "dtbo", "vbmeta")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FastbootScreen(state: AppUiState, actions: AppActions) {
    val ready = state.ready(TargetMode.FASTBOOT)
    var partition by rememberSaveable { mutableStateOf("boot") }
    var image by remember { mutableStateOf<LibraryFile?>(null) }
    var getvar by rememberSaveable { mutableStateOf("") }
    var erase by rememberSaveable { mutableStateOf("") }
    val vars = state.info?.takeIf { it.mode == TargetMode.FASTBOOT }?.rows?.toMap().orEmpty()

    ScreenColumn {
        ModeNotice(state, TargetMode.FASTBOOT, "Reboot the target to its bootloader (ADB screen, or hold the key combo) and connect from the Dashboard.")

        ForgeCard("Variables", "Bootloader state: ${vars["Bootloader unlocked"] ?: "unknown"} unlocked, slot ${vars["Current slot"] ?: "unknown"}", Icons.Filled.Info) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Read all", Icons.Filled.Info, enabled = ready) { actions.fastbootGetvar("all") }
            }
            OutlinedTextField(getvar, { getvar = it }, Modifier.fillMaxWidth(), label = { Text("getvar name (e.g. product, unlocked)") }, singleLine = true)
            ActionButton("Get", Icons.Filled.PlayArrow, enabled = ready && getvar.isNotBlank()) { actions.fastbootGetvar(getvar.trim()) }
        }

        ForgeCard("Flash partition", "Shows the plan and asks you to type a confirmation", Icons.Filled.FlashOn) {
            OutlinedTextField(partition, { partition = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Partition") }, singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                COMMON_PARTITIONS.forEach { p -> AssistChip(onClick = { partition = p }, label = { Text(p) }) }
            }
            FilePickerField("Image (.img)", state.library, image, actions, { image = it }, listOf(".img", ".bin"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Flash", Icons.Filled.FlashOn, ActionKind.Danger, enabled = ready && image != null && partition.isNotBlank()) {
                    image?.let { actions.fastbootFlash(partition, it) }
                }
                ActionButton("Boot once", Icons.Filled.PlayArrow, ActionKind.Outline, enabled = ready && image != null) {
                    image?.let(actions::fastbootBoot)
                }
            }
            NoticeCard("Boot once sends the image to RAM and boots it without writing anything. Prefer it to test a boot or recovery image first.")
        }

        ForgeCard("Bootloader lock", "Both directions erase all data", Icons.Filled.LockOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Unlock", Icons.Filled.LockOpen, ActionKind.Danger, enabled = ready) { actions.fastbootUnlock() }
                ActionButton("Lock", Icons.Filled.Lock, ActionKind.Danger, enabled = ready) { actions.fastbootLock() }
            }
            NoticeCard("Never lock a device that has a patched or custom boot, recovery or vendor image installed. It will refuse to start.", danger = true)
        }

        ForgeCard("Active slot", "Current: ${vars["Current slot"] ?: "unknown"}", Icons.Filled.SwapHoriz) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Slot A", enabled = ready) { actions.fastbootSlot("a") }
                ActionButton("Slot B", enabled = ready) { actions.fastbootSlot("b") }
            }
        }

        ForgeCard("Erase partition", icon = Icons.Filled.DeleteForever) {
            OutlinedTextField(erase, { erase = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Partition to erase") }, singleLine = true)
            ActionButton("Erase", Icons.Filled.DeleteForever, ActionKind.Danger, enabled = ready && erase.isNotBlank()) { actions.fastbootErase(erase) }
        }

        ForgeCard("Reboot", icon = Icons.Filled.PowerSettingsNew) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RebootTarget.entries.forEach { t -> ActionButton(t.label, enabled = ready) { actions.fastbootReboot(t) } }
            }
        }
    }
}
