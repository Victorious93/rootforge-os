package ai.rootforge.os.ui.screens

import ai.rootforge.os.catalog.CatalogRecoveryPrefill
import ai.rootforge.os.catalog.CatalogRecoveryBridge
import ai.rootforge.os.device.DeviceCardModel
import ai.rootforge.os.device.DeviceRoster
import ai.rootforge.os.device.HostPlatform
import ai.rootforge.os.device.RootGuideAction
import ai.rootforge.os.device.RootGuideKind
import ai.rootforge.os.device.RootGuideStep
import ai.rootforge.os.device.RootGuides
import ai.rootforge.os.device.SerialMask
import ai.rootforge.os.device.publicLabel
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevicesScreen(
    state: AppUiState,
    actions: AppActions,
    onOpenAdb: (AttachedTarget) -> Unit = {},
    onOpenFastboot: (AttachedTarget) -> Unit = {},
    onOpenEdl: (AttachedTarget) -> Unit = {},
    onOpenRoot: (AttachedTarget) -> Unit = {},
    onOpenModules: (AttachedTarget) -> Unit = {},
    onOpenCustomRecovery: (AttachedTarget, CatalogRecoveryPrefill?) -> Unit = { _, _ -> },
) {
    val cards = remember(state.targets, state.identities, state.serialBusy, state.buildCatalog) {
        DeviceRoster.cards(state.targets, state.identities, state.serialBusy, state.buildCatalog)
    }
    val pc = HostPlatform.isHostComputer(state.hostCaption)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = RootGuides.columnsForWidthDp(maxWidth.value.roundToInt())
        ScreenColumn {
            ForgeCard("Connected phones", "USB attach plus a poll of ADB, fastboot and Qualcomm 9008. Identify is read-only.", Icons.Filled.PhoneAndroid) {
                Text(
                    if (pc) {
                        val pi = state.hostCaption == HostPlatform.PI_CAPTION
                        val host = if (pi) "This Raspberry Pi" else "This computer"
                        "$host talks to every plugged-in phone through host adb and fastboot (`adb -s` / `fastboot -s`) — the same `:desktop` path on a Pi as on a PC. Wide windows show two cards side by side. Serials show the last four characters. Destructive work still uses dry-run, a typed phrase, a backup before a recovery write, and never reboots by itself."
                    } else {
                        "Every plugged-in phone is listed, even when only one is the active target. Serials show the last four characters. Destructive work still uses dry-run, a typed phrase, a backup before a recovery write, and never reboots by itself. One action per serial; other phones can continue."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.targets.isEmpty()) {
                    Text(
                        state.emptyTargetsHint ?: "No USB device detected. Plug a phone into ${state.hostCaption}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ActionButton("Identify attached", Icons.Filled.Refresh, enabled = true) { actions.identifyDevices() }
            }

            cards.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { card ->
                        DevicePhoneCard(
                            card = card,
                            actions = actions,
                            modifier = Modifier.weight(1f),
                            onOpenAdb = onOpenAdb,
                            onOpenFastboot = onOpenFastboot,
                            onOpenEdl = onOpenEdl,
                            onOpenRoot = onOpenRoot,
                            onOpenModules = onOpenModules,
                            onOpenCustomRecovery = onOpenCustomRecovery,
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            if (state.targets.isEmpty()) CableSetupCard()
            NoticeCard("EDL (Qualcomm 05c6:9008) is identified only. Devices never starts a Firehose write. Samsung Download Mode / Odin is explained only — RootForge does not speak Odin. Unlock, flash, recovery write and module install keep the same confirms as their own screens. ESP32 and Raspberry Pi helpers (Links) are owner unlock/flash only: a Pi is this Linux host path; an ESP32 may be a USB-serial bridge. RootForge does not bypass a screen lock or FRP.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicePhoneCard(
    card: DeviceCardModel,
    actions: AppActions,
    modifier: Modifier = Modifier,
    onOpenAdb: (AttachedTarget) -> Unit,
    onOpenFastboot: (AttachedTarget) -> Unit,
    onOpenEdl: (AttachedTarget) -> Unit,
    onOpenRoot: (AttachedTarget) -> Unit,
    onOpenModules: (AttachedTarget) -> Unit,
    onOpenCustomRecovery: (AttachedTarget, CatalogRecoveryPrefill?) -> Unit,
) {
    val identity = card.identity
    val title = identity?.let { listOfNotNull(it.brand, it.model).joinToString(" ").ifBlank { card.target.publicLabel() } }
        ?: card.target.publicLabel()
    ForgeCard(title, card.plan.headline, Icons.Filled.Usb, modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(card.target.mode.label, modeColor(card.target.mode))
            card.busy?.let { StatusPill(it, ForgeColors.Warn) }
            if (!card.target.hasPermission) StatusPill("needs USB permission", ForgeColors.Warn)
        }
        if (identity != null) {
            KeyValueRow("Codename", identity.codename ?: "unknown")
            KeyValueRow("SoC", identity.soc ?: "unknown")
            KeyValueRow("Android", identity.androidVersion?.let { v -> "$v${identity.sdk?.let { " (SDK $it)" } ?: ""}" } ?: "unknown")
            KeyValueRow("Security patch", identity.securityPatch ?: "unknown")
            KeyValueRow("Bootloader locked", identity.bootloader.lockedLabel)
            KeyValueRow("Root manager", identity.rootManagers.joinToString { it.label }.ifBlank { "none detected" })
            KeyValueRow("Slot", identity.slot ?: "none / unknown")
            KeyValueRow("USB mode", identity.mode.label)
            KeyValueRow("Serial", identity.maskedSerial)
        } else {
            KeyValueRow("USB", card.target.vidPid)
            KeyValueRow("Serial", SerialMask.mask(card.target.serial))
            Text("Identity not read yet. Tap Identify attached, or grant USB access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        identity?.note?.let { NoticeCard(it) }

        Text("Root plan", style = MaterialTheme.typography.titleSmall)
        card.plan.steps.forEach { step ->
            Text(
                "${if (step.done) "Done" else "Next"} · ${step.title}: ${step.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        card.plan.portal?.let { portal ->
            Text("Unlock portal · ${portal.name}", style = MaterialTheme.typography.titleSmall)
            Text(portal.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!portal.offered) {
                NoticeCard("This brand no longer offers official unlocking. There is no portal to open.")
            }
            Text(portal.unlockData, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            portal.unlockDataCommand?.let { cmd ->
                Text(
                    "Read it on Fastboot (dry-run first; typed UNLOCK still gates a later unlock): fastboot $cmd",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            NoticeCard("Opens in the system browser only. RootForge never sends serial, IMEI or unlock data to any site.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                portal.url?.let { url ->
                    ActionButton("Official portal", Icons.Filled.OpenInBrowser, ActionKind.Outline) { actions.openUrl(url) }
                }
                if (portal.unlockDataCommand != null) {
                    ActionButton("Open Fastboot", kind = ActionKind.Outline, enabled = card.busy == null) { onOpenFastboot(card.target) }
                }
            }
        }

        card.plan.guide?.let { guide ->
            Text("Root wizard · ${guide.title}", style = MaterialTheme.typography.titleSmall)
            Text(guide.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            guide.knoxWarning?.let { NoticeCard(it, danger = true) }
            if (guide.kind == RootGuideKind.SAMSUNG && !guide.odinFlashing) {
                NoticeCard("Odin flashing is not implemented here. Use the official Samsung pages, or Custom Recovery when this phone is rooted over ADB.")
            }
            guide.steps.forEachIndexed { index, step ->
                WizardStepRow(index + 1, step, card, actions, onOpenAdb, onOpenFastboot, onOpenRoot, onOpenCustomRecovery)
            }
        }

        val matches = card.plan.roms + card.plan.recoveries + card.plan.roots
        if (matches.isNotEmpty()) {
            Text("Catalog for this codename", style = MaterialTheme.typography.titleSmall)
            matches.forEach { match ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(match.entry.name, style = MaterialTheme.typography.bodyMedium)
                        Text(match.kind.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    match.entry.safeDownloadUrl()?.let { url ->
                        ActionButton("Page", Icons.Filled.OpenInBrowser, ActionKind.Outline) { actions.openUrl(url) }
                    }
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!card.target.hasPermission) {
                ActionButton("Grant USB access", Icons.Filled.Usb, ActionKind.Primary) { actions.requestUsbPermission(card.target) }
            }
            when (card.target.mode) {
                TargetMode.ADB -> {
                    ActionButton("Open ADB", enabled = card.busy == null) { onOpenAdb(card.target) }
                    ActionButton("Root / Patching", kind = ActionKind.Outline, enabled = card.busy == null) { onOpenRoot(card.target) }
                    ActionButton("Modules (USB)", kind = ActionKind.Outline, enabled = card.busy == null) { onOpenModules(card.target) }
                    ActionButton("Custom Recovery", kind = ActionKind.Outline, enabled = card.busy == null) {
                        onOpenCustomRecovery(card.target, card.plan.writableRecovery?.let { CatalogRecoveryBridge.prefill(it) })
                    }
                }
                TargetMode.FASTBOOT -> {
                    ActionButton("Open Fastboot", enabled = card.busy == null) { onOpenFastboot(card.target) }
                    ActionButton("Custom Recovery", kind = ActionKind.Outline, enabled = card.busy == null) {
                        onOpenCustomRecovery(card.target, card.plan.writableRecovery?.let { CatalogRecoveryBridge.prefill(it) })
                    }
                }
                TargetMode.EDL -> {
                    ActionButton("Open EDL (identify)", enabled = card.busy == null) { onOpenEdl(card.target) }
                }
                TargetMode.UNKNOWN -> Unit
            }
        }
    }
}

@Composable
private fun WizardStepRow(
    number: Int,
    step: RootGuideStep,
    card: DeviceCardModel,
    actions: AppActions,
    onOpenAdb: (AttachedTarget) -> Unit,
    onOpenFastboot: (AttachedTarget) -> Unit,
    onOpenRoot: (AttachedTarget) -> Unit,
    onOpenCustomRecovery: (AttachedTarget, CatalogRecoveryPrefill?) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$number. ${step.title}", style = MaterialTheme.typography.bodyMedium)
        Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Erases: ${step.erases}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (step.dryRunFirst) {
            Text("Dry-run first: the next screen shows the plan. Nothing is written until you type the phrase.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        step.confirmPhrase?.let {
            Text("Typed confirm: $it. Never auto-reboot.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (step.action) {
            RootGuideAction.NONE -> Unit
            RootGuideAction.OPEN_URL -> step.url?.let { url ->
                ActionButton("Official page", Icons.Filled.OpenInBrowser, ActionKind.Outline) { actions.openUrl(url) }
            }
            RootGuideAction.OPEN_ADB -> ActionButton("Open ADB", kind = ActionKind.Outline, enabled = card.busy == null) { onOpenAdb(card.target) }
            RootGuideAction.OPEN_FASTBOOT -> ActionButton("Open Fastboot", kind = ActionKind.Outline, enabled = card.busy == null) { onOpenFastboot(card.target) }
            RootGuideAction.OPEN_ROOT -> ActionButton("Root / Patching", kind = ActionKind.Outline, enabled = card.busy == null) { onOpenRoot(card.target) }
            RootGuideAction.OPEN_RECOVERY -> ActionButton("Custom Recovery", kind = ActionKind.Outline, enabled = card.busy == null) {
                onOpenCustomRecovery(card.target, card.plan.writableRecovery?.let { CatalogRecoveryBridge.prefill(it) })
            }
        }
    }
}
