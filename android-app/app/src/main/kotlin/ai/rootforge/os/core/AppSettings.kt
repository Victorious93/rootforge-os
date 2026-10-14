package ai.rootforge.os.core

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

    val nodeId: String
        get() = prefs.getString(KEY_NODE, null) ?: generateNodeId().also { prefs.edit().putString(KEY_NODE, it).apply() }

    private companion object {
        const val KEY_BRIDGE = "bridge_enabled"
        const val KEY_AUTO = "auto_connect"
        const val KEY_NODE = "node_id"

        fun generateNodeId(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return "rf-" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
