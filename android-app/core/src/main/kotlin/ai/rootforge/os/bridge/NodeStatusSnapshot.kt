package ai.rootforge.os.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Frozen STATUS-TRAY connection-class names ([docs/specs/STATUS_TRAY.md] in
 * droidcommand-AI). Wire is the uppercase token, same as the rest of the fleet.
 */
object FleetConnectionClass {
    const val EXTERNAL_NODE = "EXTERNAL_NODE"
    const val BOUND_COMPANION = "BOUND_COMPANION"
    const val TERMUX_IPC = "TERMUX_IPC"
    const val HOST_PROCESS_TOOL = "HOST_PROCESS_TOOL"
    val ALL: Set<String> = setOf(EXTERNAL_NODE, BOUND_COMPANION, TERMUX_IPC, HOST_PROCESS_TOOL)
}

/**
 * Frozen status-tray domains. Wire names are the lowercase [wire] values.
 * Unknown wire names decode as [OTHER].
 */
enum class StatusTrayDomain(val wire: String) {
    MESH("mesh"),
    VPN("vpn"),
    BROWSER("browser"),
    SMS("sms"),
    NODE_HEALTH("node_health"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String): StatusTrayDomain =
            entries.find { it.wire.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: OTHER
    }
}

/** Frozen domain state. Wire: `ok` / `degraded` / `down` / `unknown` / `off`. */
enum class StatusTrayState(val wire: String) {
    OK("ok"),
    DEGRADED("degraded"),
    DOWN("down"),
    UNKNOWN("unknown"),
    OFF("off"),
    ;

    companion object {
        fun fromWire(value: String): StatusTrayState =
            entries.find { it.wire.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: UNKNOWN
    }
}

/** One domain row inside a [NodeStatusSnapshot]. Frozen `{ name, state, detail? }`. */
data class StatusTrayDomainStatus(
    val name: StatusTrayDomain,
    val state: StatusTrayState = StatusTrayState.UNKNOWN,
    val detail: String? = null,
    /** Original wire name when [name] is [StatusTrayDomain.OTHER]. Not a second domain. */
    val originalName: String? = null,
) {
    init {
        detail?.let {
            require(it.length <= MAX_DETAIL_CHARS) {
                "detail exceeds $MAX_DETAIL_CHARS characters"
            }
        }
    }

    fun wireName(): String =
        if (name == StatusTrayDomain.OTHER && !originalName.isNullOrBlank()) originalName else name.wire

    companion object {
        const val MAX_DETAIL_CHARS = 200
    }
}

/**
 * Frozen per-node status snapshot. Wire fields: [nodeId], [packageName],
 * [connectionClass], [updatedAtMs], [domains]. JVM-only aliases from the
 * controller skeleton are not placed on the wire.
 */
data class NodeStatusSnapshot(
    val nodeId: String,
    val packageName: String? = null,
    val connectionClass: String = FleetConnectionClass.EXTERNAL_NODE,
    val updatedAtMs: Long,
    val domains: List<StatusTrayDomainStatus> = emptyList(),
    val payload: Map<String, String> = emptyMap(),
) {
    init {
        require(nodeId.isNotBlank()) { "nodeId is required" }
        packageName?.let { require(it.isNotBlank()) { "packageName must be blank or a real applicationId" } }
        require(connectionClass in FleetConnectionClass.ALL) {
            "unknown connectionClass '$connectionClass'"
        }
    }
}

/**
 * JSON codec for the frozen STATUS-TRAY wire object. Extra JVM fields
 * (`capability`, `actionRef`, `payload`) are **not** emitted. Encode refuses
 * a snapshot whose `detail` or leftover payload keys match the forbidden
 * secret-name list (`invalid_parameters` at the executor, not a silent strip).
 */
object NodeStatusSnapshotCodec {
    fun encode(snapshot: NodeStatusSnapshot): JsonObject {
        assertEncodable(snapshot)
        return buildJsonObject {
            put("nodeId", snapshot.nodeId)
            snapshot.packageName?.let { put("packageName", it) }
            put("connectionClass", snapshot.connectionClass)
            put("updatedAtMs", snapshot.updatedAtMs)
            putJsonArray("domains") {
                snapshot.domains.forEach { domain ->
                    add(buildJsonObject {
                        put("name", domain.wireName())
                        put("state", domain.state.wire)
                        domain.detail?.let { put("detail", it) }
                    })
                }
            }
        }
    }

    fun decode(raw: JsonObject): NodeStatusSnapshot {
        val nodeId = raw.string("nodeId")?.takeIf { it.isNotBlank() }
            ?: raw.string("sourceNodeRef")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("nodeId is required")
        val packageName = raw.string("packageName")?.takeIf { it.isNotBlank() }
        val connectionClass = raw.string("connectionClass")?.takeIf { it.isNotBlank() }
            ?: FleetConnectionClass.EXTERNAL_NODE
        require(connectionClass in FleetConnectionClass.ALL) { "unknown connectionClass '$connectionClass'" }
        val updatedAtMs = raw.long("updatedAtMs") ?: raw.long("receivedAtMs")
            ?: throw IllegalArgumentException("updatedAtMs is required")
        val domainsEl = raw["domains"]
        val domains = when (domainsEl) {
            null, JsonNull -> emptyList()
            is JsonArray -> domainsEl.mapIndexed { i, el ->
                val obj = el as? JsonObject ?: throw IllegalArgumentException("domains[$i] must be an object")
                decodeDomain(obj, i)
            }
            else -> throw IllegalArgumentException("domains must be an array")
        }
        val snapshot = NodeStatusSnapshot(
            nodeId = nodeId,
            packageName = packageName,
            connectionClass = connectionClass,
            updatedAtMs = updatedAtMs,
            domains = domains,
        )
        assertEncodable(snapshot)
        return snapshot
    }

    fun decode(raw: String): NodeStatusSnapshot {
        val obj = runCatching { bridgeJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw IllegalArgumentException("not a valid NodeStatusSnapshot")
        return decode(obj)
    }

    fun assertEncodable(snapshot: NodeStatusSnapshot) {
        StatusTrayPrivacy.assertSafe(snapshot)
        snapshot.domains.forEach { domain ->
            domain.detail?.let {
                require(it.length <= StatusTrayDomainStatus.MAX_DETAIL_CHARS) {
                    "detail exceeds ${StatusTrayDomainStatus.MAX_DETAIL_CHARS} characters"
                }
            }
        }
    }

    private fun decodeDomain(obj: JsonObject, index: Int): StatusTrayDomainStatus {
        val name = obj.string("name") ?: throw IllegalArgumentException("domains[$index] missing name")
        val state = obj.string("state") ?: throw IllegalArgumentException("domains[$index] missing state")
        val domain = StatusTrayDomain.fromWire(name)
        return StatusTrayDomainStatus(
            name = domain,
            state = StatusTrayState.fromWire(state),
            detail = obj.string("detail"),
            originalName = name.takeIf { domain == StatusTrayDomain.OTHER && StatusTrayDomain.entries.none { it.wire == name } },
        )
    }

    private fun JsonObject.string(key: String): String? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        val prim = el as? JsonPrimitive ?: throw IllegalArgumentException("'$key' must be a string")
        if (!prim.isString) throw IllegalArgumentException("'$key' must be a string")
        return prim.content
    }

    private fun JsonObject.long(key: String): Long? {
        val el = this[key] ?: return null
        if (el is JsonNull) return null
        return (el as? JsonPrimitive)?.longOrNull
            ?: throw IllegalArgumentException("'$key' must be an integer")
    }
}

/**
 * Privacy gate for STATUS-TRAY `detail` and leftover payload keys. Refusal is
 * an exception (mapped to `invalid_parameters`), never a silent strip.
 */
object StatusTrayPrivacy {
    val FORBIDDEN_FRAGMENTS: List<String> = listOf(
        "password", "passwd", "api_key", "apikey", "api-key", "api_token",
        "token", "secret", "codeword", "code_word", "whitelist",
        "signingkey", "signing_key", "privatekey", "private_key", "keystore",
        "cookie", "authorization", "bearer",
    )

    fun isForbiddenName(name: String): Boolean {
        val folded = fold(name)
        return FORBIDDEN_FRAGMENTS.any { fragment -> folded.contains(fold(fragment)) }
    }

    fun assertSafe(snapshot: NodeStatusSnapshot) {
        snapshot.payload.keys.forEach { key ->
            if (isForbiddenName(key)) {
                throw IllegalArgumentException("status snapshot must not carry secret field '$key'")
            }
        }
        snapshot.domains.forEach { domain ->
            val detail = domain.detail ?: return@forEach
            FORBIDDEN_FRAGMENTS.forEach { fragment ->
                if (detail.lowercase().contains(fragment)) {
                    throw IllegalArgumentException("status detail must not carry '$fragment'")
                }
            }
        }
    }

    private fun fold(value: String): String = value.lowercase().replace("-", "").replace("_", "")
}
