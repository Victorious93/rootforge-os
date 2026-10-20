package ai.rootforge.os.config

/** Bundle envelope version from droidcommand-AI `docs/specs/UNIFIED_BACKUP.md`. */
const val CONFIG_BUNDLE_VERSION = 1

/** RootForge node payload schema. Unknown versions fail closed. */
const val CONFIG_PAYLOAD_SCHEMA = 1

const val CONFIG_MAX_BYTES = 48 * 1024

object ConfigWrapping {
    const val NONE = "none"
    const val RAW_AES = "raw-aes-256-gcm"
    const val ANDROID_KEYSTORE = "android-keystore"
    val ALL: Set<String> = setOf(NONE, RAW_AES, ANDROID_KEYSTORE)
}

object ConfigConflictPolicy {
    const val SKIP = "skip"
    const val OVERWRITE = "overwrite"
    const val MERGE_BY_ID = "merge-by-id"
    val ALL: Set<String> = setOf(SKIP, OVERWRITE, MERGE_BY_ID)
}

/** Preference ids this node may put on the wire. Everything else is omitted. */
object ConfigPreferenceId {
    const val AUTO_CONNECT = "autoConnect"
    const val TERMINAL_APP = "terminalApp"
    val EXPORTABLE: Set<String> = setOf(AUTO_CONNECT, TERMINAL_APP)
}

data class ConfigPreference(val id: String, val value: String)

data class UnifiedBackupEntry(
    val packageName: String,
    val schemaVersion: Int,
    val clearPayload: String? = null,
    val ciphertext: String? = null,
)

data class UnifiedFleetBackup(
    val version: Int,
    val createdAtMs: Long,
    val sourceNodeRef: String,
    val wrapping: String,
    val entries: List<UnifiedBackupEntry>,
)

data class ConfigChange(
    val id: String,
    val from: String?,
    val to: String?,
    val action: String,
)

class ConfigBackupException(val code: String, override val message: String) : Exception(message)

/** Live non-secret preferences this node can export and import. */
interface NodeConfigStore {
    /** Settings → Allow external controllers. Import never writes this. */
    var externalControlEnabled: Boolean

    fun getPreference(id: String): String?

    fun setPreference(id: String, value: String)

    fun preferenceIds(): Set<String>
}

class MemoryNodeConfigStore(
    override var externalControlEnabled: Boolean = false,
    /** Keys that exist on the device but must never appear in an export. */
    val secrets: MutableMap<String, String> = linkedMapOf(),
) : NodeConfigStore {
    private val values = linkedMapOf<String, String>()

    override fun getPreference(id: String): String? = values[id]

    override fun setPreference(id: String, value: String) {
        values[id] = value
    }

    override fun preferenceIds(): Set<String> = values.keys.toSet()

    fun snapshot(): Map<String, String> = values.toMap()
}
