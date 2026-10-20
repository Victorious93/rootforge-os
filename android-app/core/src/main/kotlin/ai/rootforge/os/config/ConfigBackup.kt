package ai.rootforge.os.config

import ai.rootforge.os.bridge.BRIDGE_PACKAGE
import ai.rootforge.os.bridge.BRIDGE_PACKAGE_DEBUG
import ai.rootforge.os.bridge.FleetError
import ai.rootforge.os.bridge.bridgeJson
import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.core.TerminalApp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Local SETTINGS/BACKUP implementation for this EXTERNAL_NODE.
 *
 * Export writes only [ConfigPreferenceId.EXPORTABLE] rows. Import applies
 * skip / overwrite / merge-by-id, requires [ApprovalCenter] unless
 * [dryRun], and never writes [NodeConfigStore.externalControlEnabled].
 * Ciphertext wrapping is not implemented here (`wrapping=none` +
 * `clearPayload` only).
 */
class ConfigBackup(
    val store: NodeConfigStore = MemoryNodeConfigStore(),
    private val approvals: ApprovalCenter = ApprovalCenter(),
    private val packageName: String = BRIDGE_PACKAGE,
    private val nodeRef: String = "rootforge",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun export(restrictPackage: String? = null): JsonObject {
        if (!restrictPackage.isNullOrBlank() && !isThisNode(restrictPackage)) {
            throw ConfigBackupException(FleetError.NOT_FOUND, "this node is not $restrictPackage")
        }
        val payload = payloadObject()
        rejectSecrets(payload)
        val clear = payload.toString()
        if (clear.length > CONFIG_MAX_BYTES) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "export payload larger than $CONFIG_MAX_BYTES bytes")
        }
        val entry = buildJsonObject {
            put("packageName", packageName)
            put("schemaVersion", CONFIG_PAYLOAD_SCHEMA)
            put("clearPayload", clear)
        }
        return buildJsonObject {
            put("packageName", packageName)
            put("schemaVersion", CONFIG_PAYLOAD_SCHEMA)
            put("clearPayload", clear)
            put("entry", entry)
            put(
                "bundle",
                encodeBundle(
                    UnifiedFleetBackup(
                        version = CONFIG_BUNDLE_VERSION,
                        createdAtMs = clock(),
                        sourceNodeRef = nodeRef,
                        wrapping = ConfigWrapping.NONE,
                        entries = listOf(
                            UnifiedBackupEntry(packageName, CONFIG_PAYLOAD_SCHEMA, clearPayload = clear),
                        ),
                    ),
                ),
            )
        }
    }

    suspend fun import(
        bundleText: String?,
        bundleObject: JsonObject? = null,
        dryRun: Boolean = false,
        conflictPolicy: String = ConfigConflictPolicy.SKIP,
        source: String = "config.import",
    ): JsonObject {
        val policy = parsePolicy(conflictPolicy)
        val raw = when {
            bundleObject != null -> bundleObject
            !bundleText.isNullOrBlank() -> parseJsonObject(bundleText)
            else -> throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "missing parameter 'bundle'")
        }
        if (raw.toString().length > CONFIG_MAX_BYTES) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "bundle larger than $CONFIG_MAX_BYTES bytes")
        }
        val bundle = decodeBundle(raw)
        val entry = bundle.entries.singleOrNull { isThisNode(it.packageName) }
            ?: throw ConfigBackupException(FleetError.NOT_FOUND, "named packageName has no entry")
        if (entry.schemaVersion != CONFIG_PAYLOAD_SCHEMA) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "unsupported schemaVersion ${entry.schemaVersion}")
        }
        if (bundle.wrapping != ConfigWrapping.NONE) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "unsupported wrapping '${bundle.wrapping}'")
        }
        val clear = entry.clearPayload
            ?: throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "entry is missing clearPayload")
        if (clear.length > CONFIG_MAX_BYTES) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "clearPayload larger than $CONFIG_MAX_BYTES bytes")
        }
        val payload = parseJsonObject(clear)
        rejectSecrets(payload)
        val incoming = readPreferences(payload)
        val plan = planChanges(incoming, policy)

        if (dryRun) {
            return report(dryRun = true, policy = policy, planned = plan, applied = emptyList())
        }

        val ok = approvals.request(
            title = "Import settings?",
            lines = listOf(
                "Requested by $source",
                "Non-secret preferences only. External control / remote.command will stay as they are.",
                "conflictPolicy=$policy",
                "changes=${plan.count { it.action != "skip" }}",
            ),
            source = source,
        )
        if (!ok) {
            throw ConfigBackupException(FleetError.CONFIRMATION_REQUIRED, "the person at the host phone did not approve config.import")
        }

        val beforeControl = store.externalControlEnabled
        apply(plan)
        store.externalControlEnabled = beforeControl
        return report(dryRun = false, policy = policy, planned = plan, applied = plan.filter { it.action != "skip" })
    }

    private fun payloadObject(): JsonObject = buildJsonObject {
        put("schemaVersion", CONFIG_PAYLOAD_SCHEMA)
        putJsonArray("preferences") {
            for (id in ConfigPreferenceId.EXPORTABLE) {
                val value = store.getPreference(id) ?: continue
                if (ConfigSecrets.isForbiddenName(id)) continue
                add(
                    buildJsonObject {
                        put("id", id)
                        put("value", value)
                    },
                )
            }
        }
    }

    private fun readPreferences(payload: JsonObject): List<ConfigPreference> {
        val extra = payload.keys - setOf("schemaVersion", "preferences")
        if (extra.isNotEmpty()) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "unknown field(s): ${extra.sorted().joinToString()}")
        }
        val version = (payload["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "missing field 'schemaVersion'")
        if (version != CONFIG_PAYLOAD_SCHEMA) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "unsupported schemaVersion $version")
        }
        val arr = payload["preferences"] as? JsonArray
            ?: throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "'preferences' must be an array")
        return arr.mapIndexed { i, el ->
            val obj = el as? JsonObject
                ?: throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "preferences[$i] must be an object")
            val rowExtra = obj.keys - setOf("id", "value")
            if (rowExtra.isNotEmpty()) {
                throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "unknown field(s) in preferences[$i]: ${rowExtra.sorted().joinToString()}")
            }
            val id = obj.string("id")
            val value = obj.string("value")
            if (id.isBlank()) throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "preferences[$i] missing id")
            validatePreference(id, value)
            ConfigPreference(id, value)
        }
    }

    private fun validatePreference(id: String, value: String) {
        when (id) {
            ConfigPreferenceId.AUTO_CONNECT -> {
                if (value != "true" && value != "false") {
                    throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "autoConnect must be true or false")
                }
            }
            ConfigPreferenceId.TERMINAL_APP -> {
                if (value.isNotEmpty() && !TerminalApp.isValidPackage(value)) {
                    throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "terminalApp is not a valid Android package name")
                }
            }
        }
    }

    private fun planChanges(incoming: List<ConfigPreference>, policy: String): List<ConfigChange> {
        val seen = HashSet<String>()
        val out = ArrayList<ConfigChange>()
        for (row in incoming) {
            if (row.id in seen) {
                throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "duplicate preference id '${row.id}'")
            }
            seen += row.id
            if (row.id !in ConfigPreferenceId.EXPORTABLE) continue
            if (ConfigSecrets.isForbiddenName(row.id)) {
                throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "payload contains a forbidden name '${row.id}'")
            }
            val live = store.getPreference(row.id)
            val action = when (policy) {
                ConfigConflictPolicy.OVERWRITE -> "overwrite"
                ConfigConflictPolicy.MERGE_BY_ID -> if (live != null) "skip" else "add"
                else -> "skip"
            }
            out += ConfigChange(row.id, live, row.value, action)
        }
        return out
    }

    private fun apply(plan: List<ConfigChange>) {
        for (change in plan) {
            if (change.action == "skip") continue
            val value = change.to ?: continue
            store.setPreference(change.id, value)
        }
    }

    private fun report(
        dryRun: Boolean,
        policy: String,
        planned: List<ConfigChange>,
        applied: List<ConfigChange>,
    ): JsonObject = buildJsonObject {
        put("dryRun", dryRun)
        put("conflictPolicy", policy)
        put("externalControlEnabled", store.externalControlEnabled)
        putJsonArray("changes") {
            planned.forEach { change ->
                add(
                    buildJsonObject {
                        put("id", change.id)
                        put("action", change.action)
                        put("from", change.from?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("to", change.to?.let { JsonPrimitive(it) } ?: JsonNull)
                    },
                )
            }
        }
        put("appliedCount", applied.size)
        put("skippedCount", planned.count { it.action == "skip" })
    }

    private fun rejectSecrets(root: JsonElement) {
        val name = ConfigSecrets.firstForbiddenName(root) ?: return
        throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "payload contains a forbidden name '$name'")
    }

    private fun parsePolicy(raw: String): String {
        val v = raw.trim()
        if (v !in ConfigConflictPolicy.ALL) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "conflictPolicy must be skip, overwrite or merge-by-id")
        }
        return v
    }

    private fun parseJsonObject(text: String): JsonObject {
        if (text.length > CONFIG_MAX_BYTES) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "payload larger than $CONFIG_MAX_BYTES bytes")
        }
        return runCatching { bridgeJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "not valid JSON")
    }

    private fun decodeBundle(root: JsonObject): UnifiedFleetBackup {
        val extra = root.keys - BUNDLE_KEYS
        if (extra.isNotEmpty()) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "unknown field(s): ${extra.sorted().joinToString()}")
        }
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
            ?: throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "missing field 'version'")
        if (version != CONFIG_BUNDLE_VERSION) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "unknown bundle version $version")
        }
        val createdAtMs = (root["createdAtMs"] as? JsonPrimitive)?.longOrNull
            ?: throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "missing field 'createdAtMs'")
        val sourceNodeRef = root.string("sourceNodeRef")
        val wrapping = root.string("wrapping")
        if (wrapping !in ConfigWrapping.ALL) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "unknown wrapping '$wrapping'")
        }
        val arr = root["entries"] as? JsonArray
            ?: throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "'entries' must be an array")
        val entries = arr.mapIndexed { i, el ->
            val obj = el as? JsonObject
                ?: throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "entries[$i] must be an object")
            decodeEntry(obj, i, wrapping)
        }
        val packages = entries.map { it.packageName }
        if (packages.size != packages.toSet().size) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "duplicate packageName")
        }
        return UnifiedFleetBackup(version, createdAtMs, sourceNodeRef, wrapping, entries)
    }

    private fun decodeEntry(obj: JsonObject, index: Int, wrapping: String): UnifiedBackupEntry {
        val extra = obj.keys - ENTRY_KEYS
        if (extra.isNotEmpty()) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "unknown field(s) in entries[$index]: ${extra.sorted().joinToString()}")
        }
        val pkg = obj.string("packageName")
        if (pkg.isBlank()) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "entries[$index] missing packageName")
        }
        val schemaVersion = (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "entries[$index] missing schemaVersion")
        val clear = obj.optionalString("clearPayload")
        val cipher = obj.optionalString("ciphertext")
        if ((clear == null) == (cipher == null)) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "entries[$index] must have exactly one of ciphertext or clearPayload")
        }
        if (wrapping == ConfigWrapping.NONE && clear == null) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "wrapping=none requires clearPayload")
        }
        if (wrapping != ConfigWrapping.NONE && cipher == null) {
            throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "wrapping '$wrapping' requires ciphertext")
        }
        return UnifiedBackupEntry(pkg, schemaVersion, clearPayload = clear, ciphertext = cipher)
    }

    private fun encodeBundle(bundle: UnifiedFleetBackup): JsonObject = buildJsonObject {
        put("version", bundle.version)
        put("createdAtMs", bundle.createdAtMs)
        put("sourceNodeRef", bundle.sourceNodeRef)
        put("wrapping", bundle.wrapping)
        putJsonArray("entries") {
            bundle.entries.forEach { entry ->
                add(
                    buildJsonObject {
                        put("packageName", entry.packageName)
                        put("schemaVersion", entry.schemaVersion)
                        if (entry.clearPayload != null) put("clearPayload", entry.clearPayload)
                        if (entry.ciphertext != null) put("ciphertext", entry.ciphertext)
                    },
                )
            }
        }
    }

    private fun isThisNode(name: String): Boolean =
        name == packageName || name == BRIDGE_PACKAGE || name == BRIDGE_PACKAGE_DEBUG

    private fun JsonObject.string(key: String): String =
        optionalString(key) ?: throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "missing field '$key'")

    private fun JsonObject.optionalString(key: String): String? {
        val el = this[key] ?: return null
        val prim = el as? JsonPrimitive
            ?: throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "'$key' must be a string")
        if (!prim.isString) throw ConfigBackupException(FleetError.INVALID_PARAMETERS, "'$key' must be a string")
        return prim.content
    }

    private companion object {
        val BUNDLE_KEYS = setOf("version", "createdAtMs", "sourceNodeRef", "wrapping", "entries")
        val ENTRY_KEYS = setOf("packageName", "schemaVersion", "ciphertext", "clearPayload")
    }
}
