package ai.rootforge.os.core

import ai.rootforge.os.usb.TargetMode

sealed interface LinkState {
    data object Disconnected : LinkState
    data class Connecting(val message: String) : LinkState
    data class Connected(val mode: TargetMode, val label: String) : LinkState
    data class Failed(val message: String) : LinkState
}

data class TargetInfo(
    val mode: TargetMode,
    val rows: List<Pair<String, String>>,
    /** `ro.product.model` as reported over ADB; not shown in [rows]. Used only for catalog lookups. */
    val modelNumber: String? = null,
)

data class Progress(val label: String, val done: Long, val total: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}
