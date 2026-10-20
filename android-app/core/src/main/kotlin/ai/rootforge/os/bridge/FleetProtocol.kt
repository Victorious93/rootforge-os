package ai.rootforge.os.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Envelope `op` names for protocol major 1 / minor 1. */
object FleetOp {
    const val CAPABILITIES = "capabilities.get"
    const val REMOTE_COMMAND = "remote.command"
    const val MACRO_STEP = "macro.step"
    const val MACRO_RUN = "macro.run"

    val ALL: Set<String> = setOf(CAPABILITIES, REMOTE_COMMAND, MACRO_STEP, MACRO_RUN)
}

/**
 * Error `code` values for the fleet envelope. These are the only codes a
 * controller may branch on; free text stays in `message`.
 */
object FleetError {
    const val INVALID_PARAMETERS = "invalid_parameters"
    const val NOT_PAIRED = "not_paired"
    const val NOT_FOUND = "not_found"
    const val INTERNAL = "internal"
    const val UNAUTHORIZED = "unauthorized"
    const val REPLAY_DETECTED = "replay_detected"
    const val EXPIRED = "expired"
    const val EXTERNAL_CONTROL_DISABLED = "external_control_disabled"
    const val CONFIRMATION_REQUIRED = "confirmation_required"
    const val UNSUPPORTED_OP = "unsupported_op"
    const val CAPABILITY_OFF = "capability_off"

    val ALL: Set<String> = setOf(
        INVALID_PARAMETERS, NOT_PAIRED, NOT_FOUND, INTERNAL, UNAUTHORIZED,
        REPLAY_DETECTED, EXPIRED, EXTERNAL_CONTROL_DISABLED,
        CONFIRMATION_REQUIRED, UNSUPPORTED_OP, CAPABILITY_OFF,
    )
}

object FleetKind {
    const val READ = "read"
    const val ACTION = "action"
    const val DESTRUCTIVE = "destructive"
}

object FleetOnError {
    const val ABORT = "abort"
    const val CONTINUE = "continue"
    val ALL: Set<String> = setOf(ABORT, CONTINUE)
}

const val FLEET_DEFAULT_TTL_MS = 120_000L
const val FLEET_MAX_TTL_MS = 300_000L
const val FLEET_MAX_SKEW_MS = 120_000L
const val FLEET_NONCE_RETAIN_MS = 10 * 60_000L
const val FLEET_NONCE_HEX_CHARS = 32

data class FleetMacroStep(
    val actionRef: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val onError: String = FleetOnError.ABORT,
)

data class FleetRequest(
    val op: String,
    val requestId: String,
    val targetNodeRef: String,
    val nonce: String,
    val timestampMs: Long,
    val ttlMs: Long,
    val actionRef: String? = null,
    val params: JsonObject = JsonObject(emptyMap()),
    val destructive: Boolean? = null,
    val trigger: JsonObject? = null,
    val macroId: String? = null,
    val stepIndex: Int? = null,
    val onError: String? = null,
    val steps: List<FleetMacroStep> = emptyList(),
    val dryRun: Boolean = false,
    val protocolMajor: Int? = null,
    val protocolMinor: Int? = null,
)

@Serializable
data class FleetErrorBody(val code: String, val message: String)

@Serializable
data class FleetResponse(
    val ok: Boolean,
    val protocolMajor: Int = BRIDGE_PROTOCOL_MAJOR,
    val protocolMinor: Int = BRIDGE_PROTOCOL_MINOR,
    val requestId: String? = null,
    val nodeRef: String? = null,
    val result: JsonObject? = null,
    val error: FleetErrorBody? = null,
)

data class RemoteCapability(
    val name: String,
    val kind: String,
    val remoteAllowed: Boolean,
    val requiresConfirm: Boolean,
    val description: String = "",
)
