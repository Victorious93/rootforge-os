package ai.rootforge.os.core

import ai.rootforge.os.desktopmode.PinnedCompanion
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** Small persisted settings. The node id is this install's identity on the droidcommand-ai bridge. */
class AppSettings(private val prefs: SharedPreferences) {
    /** The external-control bridge is off until the owner turns it on. */
    private val bridgeState = MutableStateFlow(prefs.getBoolean(KEY_BRIDGE, false))
    val bridgeEnabledFlow: StateFlow<Boolean> = bridgeState.asStateFlow()
    var bridgeEnabled: Boolean
        get() = bridgeState.value
        set(v) {
            prefs.edit().putBoolean(KEY_BRIDGE, v).apply()
            bridgeState.value = v
        }

    private val autoState = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    val autoConnectFlow: StateFlow<Boolean> = autoState.asStateFlow()
    var autoConnect: Boolean
        get() = autoState.value
        set(v) {
            prefs.edit().putBoolean(KEY_AUTO, v).apply()
            autoState.value = v
        }

    /** Package of the full-terminal app to hand off to. Defaults to Termux; saving an empty value clears it. */
    private val terminalState = MutableStateFlow(prefs.getString(KEY_TERMINAL, TerminalApp.DEFAULT_PACKAGE) ?: TerminalApp.DEFAULT_PACKAGE)
    val terminalAppFlow: StateFlow<String> = terminalState.asStateFlow()
    var terminalApp: String
        get() = terminalState.value
        set(v) {
            prefs.edit().putString(KEY_TERMINAL, v).apply()
            terminalState.value = v
        }

    val nodeId: String
        get() = prefs.getString(KEY_NODE, null) ?: generateNodeId().also { prefs.edit().putString(KEY_NODE, it).apply() }

    /** VictorSuite package:cert pin. Empty until the owner confirms the installed companion. */
    private val pinState = MutableStateFlow(prefs.getString(KEY_VS_PIN, "") ?: "")
    val victorSuitePinFlow: StateFlow<String> = pinState.asStateFlow()
    var victorSuitePin: PinnedCompanion?
        get() = PinnedCompanion.parse(pinState.value)
        set(v) {
            val wire = v?.wire().orEmpty()
            prefs.edit().putString(KEY_VS_PIN, wire).apply()
            pinState.value = wire
        }

    private companion object {
        const val KEY_BRIDGE = "bridge_enabled"
        const val KEY_AUTO = "auto_connect"
        const val KEY_NODE = "node_id"
        const val KEY_TERMINAL = "terminal_app"
        const val KEY_VS_PIN = "victorsuite_pin"

        fun generateNodeId(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return "rf-" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
