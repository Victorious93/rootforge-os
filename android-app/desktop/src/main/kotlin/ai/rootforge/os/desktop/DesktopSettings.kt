package ai.rootforge.os.desktop

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DesktopSettings(private val file: File) {
    private val props = Properties()

    var nodeId: String
        get() = props.getProperty(KEY_NODE, "")
        set(v) {
            props.setProperty(KEY_NODE, v)
        }

    init {
        // An older build stored api_token with the process umask (typically 0644).
        // Tighten before reading so a leftover bearer token is not left readable.
        if (file.isFile) restrictToOwner(file)
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
        // Create as owner-read/write before any bytes land. FileWriter on a new
        // path uses 0666 & ~umask, which is world-readable under umask 022, and
        // this file holds the headless API bearer token.
        preparePrivateFile(file)
        file.writer().use { props.store(it, "RootForge OS desktop") }
        restrictToOwner(file)
    }

    private fun preparePrivateFile(target: File) {
        if (target.exists()) {
            restrictToOwner(target)
            return
        }
        val path = target.toPath()
        if (supportsPosix(target)) {
            try {
                Files.createFile(
                    path,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                )
            } catch (_: FileAlreadyExistsException) {
                restrictToOwner(target)
            }
        } else if (target.createNewFile() || target.isFile) {
            restrictToOwner(target)
        }
    }

    private fun restrictToOwner(target: File) {
        val path = target.toPath()
        if (supportsPosix(target)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            return
        }
        target.setReadable(false, false)
        target.setWritable(false, false)
        target.setExecutable(false, false)
        target.setReadable(true, true)
        target.setWritable(true, true)
    }

    private fun supportsPosix(target: File): Boolean =
        target.toPath().fileSystem.supportedFileAttributeViews().contains("posix")

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
