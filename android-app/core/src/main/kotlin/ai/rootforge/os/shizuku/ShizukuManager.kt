package ai.rootforge.os.shizuku

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Canonical fleet Shizuku manager: detection + the four-state permission
 * flow. It never runs a privileged command — that is [PrivilegedShell],
 * always reached through `ApprovalCenter`.
 *
 * Call [start] once from Application.onCreate so Settings can show a live
 * status. Do **not** call [requestPermission] except from an explicit UI tap.
 */
class ShizukuManager(private val host: ShizukuHost) {
    private val listeners = CopyOnWriteArrayList<(ShizukuState) -> Unit>()

    @Volatile
    private var started = false

    fun state(): ShizukuState = ShizukuStateResolver.resolve(host.signals())

    fun start() {
        if (started) return
        started = true
        host.setListener(
            object : ShizukuHostListener {
                override fun onBinderChanged() = emit()

                override fun onPermissionResult(requestCode: Int, granted: Boolean) {
                    if (requestCode == PERMISSION_REQUEST_CODE) emit()
                }
            },
        )
        emit()
    }

    fun stop() {
        host.setListener(null)
        listeners.clear()
        started = false
    }

    /** Re-reads [ShizukuHost.signals] and notifies listeners. Does not request permission. */
    fun refresh() {
        emit()
    }

    fun addListener(listener: (ShizukuState) -> Unit) {
        listeners += listener
        listener(state())
    }

    fun removeListener(listener: (ShizukuState) -> Unit) {
        listeners -= listener
    }

    /**
     * Asks Shizuku for `API_V23` only when the binder is alive and permission
     * is not granted. Other states are a no-op so a tap never crashes when
     * Shizuku is missing or not running — the UI already shows how to start it.
     */
    fun requestPermission() {
        if (state() == ShizukuState.DENIED) {
            host.requestPermission(PERMISSION_REQUEST_CODE)
        } else {
            emit()
        }
    }

    private fun emit() {
        val current = state()
        listeners.forEach { it(current) }
    }

    companion object {
        /** Matches Shizuku's own V23 permission id; any stable int is fine. */
        const val PERMISSION_REQUEST_CODE = 23
    }
}
