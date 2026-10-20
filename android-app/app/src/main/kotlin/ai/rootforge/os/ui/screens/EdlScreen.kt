package ai.rootforge.os.ui.screens

import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.device.MsmGuides
import ai.rootforge.os.device.WizardDiagrams
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
import ai.rootforge.os.ui.StepDiagram
import ai.rootforge.os.usb.TargetMode
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Upload
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

@Composable
fun EdlScreen(state: AppUiState, actions: AppActions) {
    val ready = state.ready(TargetMode.EDL)
    var programmer by remember { mutableStateOf<LibraryFile?>(null) }
    var memory by rememberSaveable { mutableStateOf("ufs") }
    var lun by rememberSaveable { mutableStateOf("0") }
    var start by rememberSaveable { mutableStateOf("") }
    var sector by rememberSaveable { mutableStateOf("4096") }
    var image by remember { mutableStateOf<LibraryFile?>(null) }
    val lunInt = lun.toIntOrNull()
    val startLong = start.toLongOrNull()
    val sectorInt = sector.toIntOrNull()
    val identity = remember(state.identities, state.info) {
        state.identities.values.firstOrNull { it.mode == TargetMode.EDL }
    }
    val msm = remember(identity) { MsmGuides.forIdentity(identity) }

    ScreenColumn {
        ForgeCard("MSM / EDL guide", msm.detected, Icons.Filled.Memory) {
            StepDiagram(WizardDiagrams.forMsm())
            Text(msm.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NoticeCard(
                "Detect + explain + official https only. RootForge never ships MSM Download Tool, QFIL, Rescue and Smart Assistant, Mi Flash, a Firehose programmer, or firmware, and this guide never writes. " +
                    "EDL as an FRP or screen-lock wipe, and Qualcomm auth bypass, are refused.",
            )
            msm.steps.forEachIndexed { index, step ->
                Text("${index + 1}. ${step.title}", style = MaterialTheme.typography.bodyMedium)
                Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            msm.sources.forEach { source ->
                Text(source.label, style = MaterialTheme.typography.bodySmall)
                Text(source.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionButton("Official page", Icons.Filled.OpenInBrowser, ActionKind.Outline) { actions.openUrl(source.url) }
            }
        }

        NoticeCard(
            "EDL support below is experimental Sahara/Firehose, not MSM Download Tool. Reading the chip identity (Sahara) and uploading a programmer are implemented and " +
                "tested against a simulated device only. No real EDL device was available. Most retail phones accept only the " +
                "OEM-signed programmer for that exact model; RootForge ships none and cannot bypass Secure Boot.",
        )
        ModeNotice(state, TargetMode.EDL, "A device in Qualcomm EDL shows up as 05c6:9008 on the Dashboard and Devices.")

        state.info?.takeIf { it.mode == TargetMode.EDL }?.let { info ->
            ForgeCard("Chip identity (Sahara)", icon = Icons.Filled.Memory) { info.rows.forEach { (k, v) -> KeyValueRow(k, v) } }
        }

        ForgeCard("Programmer", "Firehose loader (.mbn / .elf) for this exact device — you supply it. Not MSM.", Icons.Filled.Upload) {
            FilePickerField("Programmer", state.library, programmer, actions, { programmer = it }, listOf(".mbn", ".elf", ".bin"))
            ActionButton("Upload programmer", Icons.Filled.Upload, ActionKind.Danger, enabled = ready && programmer != null) { programmer?.let(actions::edlUpload) }
        }

        ForgeCard("Firehose", "After the programmer is running. Separate from the MSM guide above.", Icons.Filled.Science) {
            DropdownField("Storage type", listOf("ufs", "emmc"), memory, { it }, { memory = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Configure", enabled = ready) { actions.firehoseConfigure(memory) }
                ActionButton("Storage info", enabled = ready && lunInt != null) { lunInt?.let(actions::firehoseInfo) }
            }
            OutlinedTextField(lun, { lun = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("LUN") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(start, { start = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Start sector") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(sector, { sector = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Sector size (512 or 4096)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            FilePickerField("Raw image to write", state.library, image, actions, { image = it })
            ActionButton(
                "Write raw sectors", Icons.Filled.Save, ActionKind.Danger,
                enabled = ready && image != null && lunInt != null && startLong != null && (sectorInt == 512 || sectorInt == 4096),
            ) { image?.let { actions.firehoseProgram(lunInt!!, startLong!!, sectorInt!!, it) } }
            ActionButton("Reset device", Icons.Filled.PowerSettingsNew, enabled = ready) { actions.firehoseReset() }
        }

        ForgeCard("Getting a phone into EDL") {
            listOf(
                "adb reboot edl works on some Qualcomm phones (ADB screen > Reboot > EDL).",
                "Some bootloaders accept: fastboot oem edl.",
                "Otherwise use the model's hardware key combo or test points; this varies per device.",
                "Over USB-C to USB-C the host phone must supply power; if the target never enumerates as 9008, try a powered hub.",
            ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
