package ai.rootforge.os.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The RootForge bridge wire contract, major version 1: one JSON object per
 * request and per response. It is the same envelope droidcommand-ai's
 * `core-rootforge` module already speaks to a RootForge node over SSH
 * (`BridgeRequest` / `BridgeResponse`), so DCA needs only a different
 * transport, not a different protocol. See docs/DROIDCOMMAND_INTEGRATION.md.
 */
const val BRIDGE_PROTOCOL_MAJOR = 1

val bridgeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class BridgeRequest(
    @SerialName("protocol_major") val protocolMajor: Int = BRIDGE_PROTOCOL_MAJOR,
    @SerialName("request_id") val requestId: String,
    @SerialName("target_node_id") val targetNodeId: String,
    val operation: String,
    @SerialName("timeout_ms") val timeoutMs: Int = 10_000,
    val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class BridgeErrorBody(val category: String, val message: String)

@Serializable
data class BridgeResponse(
    @SerialName("protocol_major") val protocolMajor: Int,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("node_id") val nodeId: String? = null,
    val ok: Boolean,
    val result: JsonObject? = null,
    val error: BridgeErrorBody? = null,
)

/** Error categories a controller can branch on. Free text goes in `message` only. */
object ErrorCategory {
    const val MALFORMED = "malformed_request"
    const val PROTOCOL = "protocol_mismatch"
    const val WRONG_NODE = "wrong_node"
    const val NOT_PAIRED = "not_paired"
    const val DISABLED = "bridge_disabled"
    const val UNKNOWN_OPERATION = "unknown_operation"
    const val BAD_PARAMETERS = "bad_parameters"
    const val DENIED = "denied_by_user"
    const val NO_TARGET = "no_target"
    const val TIMEOUT = "timeout"
    const val FAILED = "operation_failed"
}

enum class Tier(val wire: String) {
    /** Reads only; allowed for any paired controller without a prompt. */
    READ("read"),

    /** Changes state or runs code on the target; the person at the host phone approves each call. */
    CONTROL("control"),

    /** Writes or erases; each call needs a typed confirmation on the host phone. */
    DESTRUCTIVE("destructive"),
}
