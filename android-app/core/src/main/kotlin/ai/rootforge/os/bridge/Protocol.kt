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

/** Bound-service action. Debug APKs keep this action; only the package name changes. */
const val BRIDGE_ACTION = "ai.rootforge.os.action.BRIDGE"

const val BRIDGE_PACKAGE = "ai.rootforge.os"
const val BRIDGE_PACKAGE_DEBUG = "ai.rootforge.os.debug"

/** Messenger `Message.what` / Bundle keys. One request, one reply. */
const val BRIDGE_MSG_REQUEST = 1
const val BRIDGE_MSG_RESPONSE = 2
const val BRIDGE_KEY_REQUEST = "request"
const val BRIDGE_KEY_RESPONSE = "response"

val bridgeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Operation names the Android host-app bridge accepts. Do not invent others here. */
object BridgeOp {
    const val CAPABILITIES = "rootforge.capabilities.get"
    const val DEVICES_LIST = "rootforge.devices.list"
    const val DEVICE_INFO = "rootforge.device.info"
    const val ADB_SHELL = "rootforge.adb.shell"
    const val ADB_REBOOT = "rootforge.adb.reboot"
    const val FASTBOOT_REBOOT = "rootforge.fastboot.reboot"
    const val MODULE_BUILD = "rootforge.module.build"
    const val FASTBOOT_FLASH = "rootforge.fastboot.flash"
    const val FASTBOOT_SLOT = "rootforge.fastboot.slot.set"
    const val FASTBOOT_UNLOCK = "rootforge.fastboot.unlock"
    const val FASTBOOT_LOCK = "rootforge.fastboot.lock"

    val ALL: Set<String> = setOf(
        CAPABILITIES, DEVICES_LIST, DEVICE_INFO,
        ADB_SHELL, ADB_REBOOT, FASTBOOT_REBOOT, MODULE_BUILD,
        FASTBOOT_FLASH, FASTBOOT_SLOT, FASTBOOT_UNLOCK, FASTBOOT_LOCK,
    )
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
