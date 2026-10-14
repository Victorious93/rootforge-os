package ai.rootforge.os.ui.screens

import ai.rootforge.os.core.LinkState
import ai.rootforge.os.ui.AppUiState
import ai.rootforge.os.ui.NoticeCard
import ai.rootforge.os.usb.TargetMode
import androidx.compose.runtime.Composable

fun AppUiState.isMode(mode: TargetMode): Boolean = (link as? LinkState.Connected)?.mode == mode

/** True when the target is in [mode] and no other action is running. */
fun AppUiState.ready(mode: TargetMode): Boolean = isMode(mode) && busy == null

@Composable
fun ModeNotice(state: AppUiState, mode: TargetMode, hint: String) {
    if (!state.isMode(mode)) {
        val current = (state.link as? LinkState.Connected)?.mode?.label ?: "not connected"
        NoticeCard("This screen needs the target in ${mode.label} mode (currently: $current). $hint")
    }
}
