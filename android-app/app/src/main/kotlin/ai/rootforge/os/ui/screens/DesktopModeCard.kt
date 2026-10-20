package ai.rootforge.os.ui.screens

import ai.rootforge.os.desktopmode.DesktopModeStatus
import ai.rootforge.os.desktopmode.DesktopModeView
import ai.rootforge.os.desktopmode.DesktopModeOp
import ai.rootforge.os.ui.ActionButton
import ai.rootforge.os.ui.ActionKind
import ai.rootforge.os.ui.AppActions
import ai.rootforge.os.ui.ForgeCard
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.ui.StatusPill
import ai.rootforge.os.ui.theme.ForgeColors
import ai.rootforge.os.ui.theme.MonoStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Linux desktop session through VictorSuite. Hidden when [view] is null (desktop
 * client). On Android a missing VictorSuite still shows a hint — never crashes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesktopModeCard(view: DesktopModeView?, actions: AppActions) {
    if (view == null) return
    ForgeCard("Desktop mode", "XFCE on this phone via VictorSuite, with RootForge’s desktop UI and VS Code", Icons.Filled.DesktopWindows) {
        StatusPill(statusLabel(view.status), statusColor(view.status))
        Text(view.headline, style = MaterialTheme.typography.titleSmall)
        Text(view.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (view.packageName.isNotEmpty()) {
            Text(
                listOfNotNull(
                    view.packageName,
                    view.certSha256.takeIf { it.isNotEmpty() }?.let { "sha256 ${it.take(16)}…" },
                    view.transportLabel.takeIf { it.isNotEmpty() },
                ).joinToString(" · "),
                style = MonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view.needsPin) {
                ActionButton("Pin this VictorSuite", kind = ActionKind.Primary) { actions.pinVictorSuite() }
            }
            if (view.canLaunch) {
                ActionButton("Start desktop", kind = ActionKind.Primary) { actions.launchDesktopMode(DesktopModeOp.START) }
                ActionButton("Set up environment", kind = ActionKind.Outline) { actions.launchDesktopMode(DesktopModeOp.SETUP) }
                ActionButton("Status", kind = ActionKind.Outline) { actions.launchDesktopMode(DesktopModeOp.STATUS) }
                ActionButton("Stop", kind = ActionKind.Outline) { actions.launchDesktopMode(DesktopModeOp.STOP) }
            }
        }
        when (view.status) {
            DesktopModeStatus.READY_RUN_COMMAND ->
                NoticeCard("VictorSuite still needs the narrower Desktop mode action described in docs/DESKTOP_MODE.md. Until then this only runs the documented rootforge-desktop-mode command.")
            DesktopModeStatus.INSTALLED_NO_ENTRYPOINT ->
                NoticeCard("Nothing is sent to VictorSuite. The setup scripts in the RootForge rootfs are ready when the entrypoint exists.")
            DesktopModeStatus.NOT_INSTALLED ->
                NoticeCard("Install VictorSuite alongside RootForge. The two apps pair by package and certificate; they do not share a signing key.")
            else -> Unit
        }
    }
}

private fun statusLabel(status: DesktopModeStatus) = when (status) {
    DesktopModeStatus.NOT_INSTALLED -> "Not installed"
    DesktopModeStatus.INSTALLED_NO_ENTRYPOINT -> "Not supported yet"
    DesktopModeStatus.NEEDS_PIN -> "Needs confirmation"
    DesktopModeStatus.CERT_CHANGED -> "Certificate changed"
    DesktopModeStatus.READY_DEDICATED -> "Ready"
    DesktopModeStatus.READY_RUN_COMMAND -> "Ready (RUN_COMMAND)"
}

@Composable
private fun statusColor(status: DesktopModeStatus) = when (status) {
    DesktopModeStatus.READY_DEDICATED -> ForgeColors.Ok
    DesktopModeStatus.READY_RUN_COMMAND, DesktopModeStatus.NEEDS_PIN -> ForgeColors.Warn
    DesktopModeStatus.CERT_CHANGED, DesktopModeStatus.INSTALLED_NO_ENTRYPOINT -> ForgeColors.Danger
    DesktopModeStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.outline
}
