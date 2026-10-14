package ai.rootforge.os.desktop

import java.io.File
import java.security.SecureRandom
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DesktopSettings(private val file: File) {
    private val props = Properties()

    init {
        if (file.isFile) file.reader().use { props.load(it) }
        if (nodeId.isBlank()) {
            nodeId = generateNodeId()
            save()
        }
    }

    private val bridgeState = MutableStateFlow(props.getProperty(KEY_BRIDGE, "false").toBoolean())
    val bridgeEnabledFlow: StateFlow<Boolean> = bridgeState.asStateFlow()
    var bridgeEnabled: Boolean
        get() = bridgeState.value
        set(v) {
            bridgeState.value = v
            props.setProperty(KEY_BRIDGE, v.toString())
            save()
        }

    private val autoState = MutableStateFlow(props.getProperty(KEY_AUTO, "true").toBoolean())
    val autoConnectFlow: StateFlow<Boolean> = autoState.asStateFlow()
    var autoConnect: Boolean
        get() = autoState.value
        set(v) {
            autoState.value = v
            props.setProperty(KEY_AUTO, v.toString())
            save()
        }

    var nodeId: String
        get() = props.getProperty(KEY_NODE, "")
        set(v) {
            props.setProperty(KEY_NODE, v)
        }

    var apiToken: String
        get() = props.getProperty(KEY_TOKEN, "")
        set(v) {
            props.setProperty(KEY_TOKEN, v)
            save()
        }

    fun ensureApiToken(): String {
        if (apiToken.isBlank()) {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            apiToken = bytes.joinToString("") { "%02x".format(it) }
        }
        return apiToken
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writer().use { props.store(it, "RootForge OS desktop") }
    }

    private companion object {
        const val KEY_BRIDGE = "bridge_enabled"
        const val KEY_AUTO = "auto_connect"
        const val KEY_NODE = "node_id"
        const val KEY_TOKEN = "api_token"

        fun generateNodeId(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return "rf-" + bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
