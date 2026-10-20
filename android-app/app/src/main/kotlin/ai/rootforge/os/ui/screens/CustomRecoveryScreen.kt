package ai.rootforge.os.ui.screens

import ai.rootforge.os.core.LibraryFile
import ai.rootforge.os.recovery.InstallFlow
import ai.rootforge.os.recovery.RecoveryKind
import ai.rootforge.os.recovery.RecoveryOffer
import ai.rootforge.os.recovery.RecoveryProbe
import ai.rootforge.os.recovery.RecoveryProject
import ai.rootforge.os.recovery.RecoverySource
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
import ai.rootforge.os.ui.theme.ForgeColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomRecoveryScreen(state: AppUiState, actions: AppActions) {
    var probes by remember { mutableStateOf<List<RecoveryProbe>>(emptyList()) }
    var selectedSource by rememberSaveable { mutableStateOf<String?>(null) }
    var offers by remember { mutableStateOf<List<RecoveryOffer>>(emptyList()) }
    var image by remember { mutableStateOf<LibraryFile?>(null) }
    var fromOffer by remember { mutableStateOf<RecoveryOffer?>(null) }
    var dryRun by rememberSaveable { mutableStateOf(true) }

    val probe = probes.firstOrNull { it.source.name == selectedSource } ?: probes.firstOrNull()
    val idle = state.busy == null

    LaunchedEffect(Unit) {
        actions.probeCustomRecovery { list ->
            probes = list
            if (selectedSource == null) {
                selectedSource = (list.firstOrNull { it.source == RecoverySource.ADB_TARGET } ?: list.firstOrNull())?.source?.name
            }
        }
    }

    ScreenColumn {
        NoticeCard(
            "This screen installs a custom recovery on a rooted phone with su, not through fastboot. " +
                "Nothing is downloaded until you tap Look up or Download, and nothing is written until you turn dry-run off and type FLASH RECOVERY. " +
                "Images are not bundled in the APK. USB fastboot flash / Boot once stay on the Recovery screen.",
        )

        ForgeCard("Device and partitions", "Root (su), A/B vs A-only, recovery vs recovery-in-boot", Icons.Filled.Security) {
            if (probes.isEmpty()) {
                Text("Tap Detect to read ${state.hostCaption} and any USB ADB target.", style = MaterialTheme.typography.bodySmall)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    probes.forEach { p ->
                        val selected = p.source.name == probe?.source?.name
                        ActionButton(
                            sourceLabel(p),
                            kind = if (selected) ActionKind.Primary else ActionKind.Outline,
                            enabled = idle,
                        ) { selectedSource = p.source.name }
                    }
                }
            }
            probe?.let { ProbeDetails(it) }
            ActionButton("Detect", Icons.Filled.Search, enabled = idle) {
                actions.probeCustomRecovery { list ->
                    probes = list
                    selectedSource = (list.firstOrNull { it.source == RecoverySource.ADB_TARGET } ?: list.firstOrNull())?.source?.name
                }
            }
        }

        ForgeCard("Official builds", "TWRP from twrp.me / dl.twrp.me, PitchBlack from pitchblackrecovery.com / GitHub", Icons.Filled.CloudDownload) {
            val names = probe?.identity?.lookupNames().orEmpty()
            Text(
                if (names.isEmpty()) "Detect a device first so the lookup uses its codename (ro.product.device, ro.build.product)."
                else "Will match: ${names.joinToString(" / ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Look up TWRP & PitchBlack", Icons.Filled.Search, enabled = idle && names.isNotEmpty()) {
                    actions.lookupOfficialRecoveries(names) { offers = it }
                }
                ActionButton("TWRP devices", kind = ActionKind.Outline) { actions.openUrl("https://twrp.me/Devices/") }
                ActionButton("PitchBlack", kind = ActionKind.Outline) { actions.openUrl("https://pitchblackrecovery.com/") }
            }
            if (offers.isEmpty()) {
                Text("No files listed yet. Lookup is https-only and runs only when you tap it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                offers.forEach { offer ->
                    OfferRow(offer, idle, actions) { downloaded ->
                        image = downloaded
                        fromOffer = offer
                    }
                }
            }
        }

        ForgeCard("Install", "Local .img / .zip, dry-run by default, typed FLASH RECOVERY to write", Icons.Filled.SystemUpdateAlt) {
            FilePickerField("Recovery file", state.library, image, actions, { picked ->
                image = picked
                fromOffer = offers.firstOrNull { it.filename == picked?.name }
            }, listOf(".img", ".zip"))
            image?.let { f ->
                val inspect = actions.inspectLibraryFile(f)
                KeyValueRow("SHA-256", inspect.sha256)
                fromOffer?.md5?.let { KeyValueRow("Published MD5", it) }
                if (fromOffer?.hasPgpSignature == true) {
                    NoticeCard("TWRP also published a PGP signature for this file. This app checks MD5 when present, always shows SHA-256, and does not verify PGP (no bundled keyring).")
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Dry-run", style = MaterialTheme.typography.bodyMedium)
                    Text("On: only print and check. Off: backup then dd, after you type FLASH RECOVERY.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = dryRun, onCheckedChange = { dryRun = it }, enabled = idle)
            }
            val canWrite = probe != null && image != null && idle && (dryRun || probe.layout.canWriteImage)
            val writeKind = if (dryRun) ActionKind.Primary else ActionKind.Danger
            ActionButton(
                if (dryRun) "Run dry-run" else "Write recovery",
                kind = writeKind,
                enabled = canWrite,
            ) {
                val p = probe ?: return@ActionButton
                val f = image ?: return@ActionButton
                actions.installCustomRecovery(p, f, dryRun, fromOffer?.md5, fromOffer?.sha256)
            }
            if (probe != null && !probe.layout.canWriteImage) {
                NoticeCard(
                    when (probe.layout.kind) {
                        RecoveryKind.IN_VENDOR_BOOT, RecoveryKind.IN_BOOT ->
                            "No dedicated recovery partition. RootForge will not write boot or vendor_boot. Use Recovery → Boot once with the .img, or flash the official installer zip from an already-running recovery (ADB Sideload)."
                        RecoveryKind.DEDICATED ->
                            "A recovery partition exists but is not writable (usually the A/B slot is unknown). Detect again, or use the installer zip / Boot once."
                        RecoveryKind.UNKNOWN ->
                            "Partitions could not be listed. Will not guess a write target. You can still download a file and use Boot once from the Recovery screen."
                    },
                    danger = !dryRun,
                )
            }
            if (probe != null && image != null && RecoveryPlannerHint.isZip(image!!.name)) {
                NoticeCard("A zip is never written with dd. Sideload it from ADB once the phone is in recovery, or install it from the recovery UI.")
            }
        }

        ForgeCard("Reboot to recovery", "Separate from writing; never runs by itself", Icons.Filled.RestartAlt) {
            ActionButton("Reboot to recovery now", enabled = idle && probe != null && probe.rooted) {
                probe?.let(actions::rebootCustomRecovery)
            }
            Text("Uses su reboot recovery (or adb reboot recovery on a USB target). It does not run after a write unless you tap this.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProbeDetails(probe: RecoveryProbe) {
    val id = probe.identity
    KeyValueRow("Where", sourceLabel(probe))
    KeyValueRow("Codename", listOfNotNull(id.device, id.product).distinct().joinToString(" / ").ifBlank { "unknown" })
    KeyValueRow("Model", id.model ?: "unknown")
    KeyValueRow("Slot", id.slot ?: "none / unknown")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusPill(if (probe.rooted) "rooted" else "no su", if (probe.rooted) ForgeColors.Ok else ForgeColors.Danger)
        StatusPill(probe.layout.kind.name, if (probe.layout.canWriteImage) ForgeColors.Steel else ForgeColors.Warn)
        StatusPill(probe.layout.slotScheme.name, ForgeColors.Steel)
    }
    KeyValueRow("Write target", probe.layout.writePath ?: "none")
    KeyValueRow("Root", probe.rootDetail)
    KeyValueRow("Flows", probe.layout.allowedFlows.joinToString { flowLabel(it) })
    probe.layout.notes.take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfferRow(offer: RecoveryOffer, idle: Boolean, actions: AppActions, onDownloaded: (LibraryFile) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(offer.title, style = MaterialTheme.typography.bodyMedium)
        Text(
            listOfNotNull(
                offer.project.name,
                offer.filename,
                offer.version?.let { "v$it" },
                offer.payload.name,
                offer.md5?.let { "md5 ${it.take(8)}…" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Download", enabled = idle && offer.safeDownloadUrl() != null) {
                actions.downloadOfficialRecovery(offer, onDownloaded)
            }
            ActionButton("Page", kind = ActionKind.Outline) { actions.openUrl(offer.pageUrl) }
        }
    }
}

private fun sourceLabel(probe: RecoveryProbe): String = when (probe.source) {
    RecoverySource.THIS_PHONE -> "This phone"
    RecoverySource.ADB_TARGET -> "USB target"
}

private fun flowLabel(flow: InstallFlow): String = when (flow) {
    InstallFlow.FLASH_IMAGE -> "dd image"
    InstallFlow.INSTALLER_ZIP -> "installer zip"
    InstallFlow.TEMPORARY_BOOT -> "boot once"
}

private object RecoveryPlannerHint {
    fun isZip(name: String) = name.endsWith(".zip", ignoreCase = true)
}
