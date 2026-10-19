package ai.rootforge.os.ui.screens

import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.DropdownField
import ai.rootforge.os.ui.FilePickerField
import ai.rootforge.os.ui.ForgeCard
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.ui.ScreenColumn
import ai.rootforge.os.usb.TargetMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

private data class RecoveryTarget(val partition: String, val explanation: String)

private val RECOVERY_TARGETS = listOf(
    RecoveryTarget("recovery", "Devices that still have a dedicated recovery partition (most pre-A/B devices and some A/B)."),
    RecoveryTarget("vendor_boot", "Many newer devices with a generic boot image keep recovery in vendor_boot. Use the image built for vendor_boot."),
    RecoveryTarget("boot", "Some A/B devices carry recovery inside boot. This replaces the kernel: only with an image built for this model."),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecoveryScreen(state: AppUiState, actions: AppActions) {
    val fbReady = state.ready(TargetMode.FASTBOOT)
    val adbReady = state.ready(TargetMode.ADB)
    var image by remember { mutableStateOf<LibraryFile?>(null) }
    var target by rememberSaveable { mutableStateOf("recovery") }
    val explanation = RECOVERY_TARGETS.firstOrNull { it.partition == target }?.explanation.orEmpty()

    ScreenColumn {
        ModeNotice(state, TargetMode.FASTBOOT, "Flashing a recovery needs fastboot. Use Reboot > Bootloader from the ADB screen first.")

        ForgeCard("Recovery image", "TWRP, OrangeFox, LineageOS recovery or a vendor image", Icons.Filled.SettingsBackupRestore) {
            FilePickerField("Recovery image", state.library, image, actions, { image = it }, listOf(".img"))
            DropdownField("Target partition", RECOVERY_TARGETS.map { it.partition }, target, { it }, { target = it })
            Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Boot once", Icons.Filled.PlayArrow, ActionKind.Primary, enabled = fbReady && image != null) { image?.let(actions::fastbootBoot) }
                ActionButton("Flash", Icons.Filled.FlashOn, ActionKind.Danger, enabled = fbReady && image != null) { image?.let { actions.fastbootFlash(target, it) } }
            }
            NoticeCard(
                "Boot once is the safe test: nothing is written. If it does not start the recovery, do not flash it. " +
                    "A recovery built for another model or Android version can leave the device unable to boot.",
            )
        }

        ForgeCard("Enter recovery", icon = Icons.Filled.RestartAlt) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("From Android", enabled = adbReady) { actions.adbReboot("recovery") }
                ActionButton("From bootloader", enabled = fbReady) { actions.fastbootReboot(ai.rootforge.os.fastboot.RebootTarget.RECOVERY) }
            }
            Text("Then choose Apply update > Apply from ADB and use the ADB screen's Sideload to install a zip.", style = MaterialTheme.typography.bodySmall)
        }

        ForgeCard("Where to get a recovery", "Opens your browser; this app does not download images", Icons.Filled.OpenInNew) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("TWRP devices", kind = ActionKind.Outline) { actions.openUrl("https://twrp.me/Devices/") }
                ActionButton("OrangeFox", kind = ActionKind.Outline) { actions.openUrl("https://orangefox.download/") }
                ActionButton("PitchBlack (PBRP)", kind = ActionKind.Outline) { actions.openUrl("https://pitchblackrecovery.com/") }
                ActionButton("LineageOS", kind = ActionKind.Outline) { actions.openUrl("https://wiki.lineageos.org/devices/") }
            }
        }
    }
}
