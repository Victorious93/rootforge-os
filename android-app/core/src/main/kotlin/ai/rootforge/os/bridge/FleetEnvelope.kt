package ai.rootforge.os.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull

sealed class FleetParseResult {
    data class Ok(val request: FleetRequest) : FleetParseResult()
    data class Err(val code: String, val message: String, val requestId: String?) : FleetParseResult()
}

private val ENVELOPE_KEYS = setOf(
    "op", "requestId", "targetNodeRef", "actionRef", "params", "nonce",
    "timestampMs", "ttlMs", "destructive", "trigger",
    "macroId", "stepIndex", "onError", "steps", "dryRun",
    "protocolMajor", "protocolMinor", "sourceNodeRef",
)

private val STEP_KEYS = setOf("actionRef", "params", "onError", "destructive")

private val NONCE_HEX = Regex("^[0-9a-fA-F]{$FLEET_NONCE_HEX_CHARS}$")

private const val MAX_FLEET_REQUEST = 64 * 1024
private const val MAX_STRING = 200

/**
 * Parses the protocol 1.1 fleet envelope. Unknown top-level keys are
 * [FleetError.INVALID_PARAMETERS]. `trigger` and `sourceNodeRef` are accepted
 * and ignored for every authorization decision.
 */
fun parseFleetRequest(line: String): FleetParseResult {
    if (line.length > MAX_FLEET_REQUEST) {
        return FleetParseResult.Err(FleetError.INVALID_PARAMETERS, "request larger than $MAX_FLEET_REQUEST bytes", null)
    }
    val root = runCatching { bridgeJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
        ?: return FleetParseResult.Err(FleetError.INVALID_PARAMETERS, "not a valid fleet request", null)
    return FleetParser(root).parse()
}

private class ParseException(val code: String, override val message: String, val requestId: String?) : Exception(message)

private class FleetParser(private val root: JsonObject) {
    private var requestId: String? = null

    fun parse(): FleetParseResult = try {
        requestId = optionalString("requestId")
        val extra = root.keys - ENVELOPE_KEYS
        if (extra.isNotEmpty()) fail(FleetError.INVALID_PARAMETERS, "unknown field(s): ${extra.sorted().joinToString()}")

        val op = requiredString("op")
        val target = requiredString("targetNodeRef")
        val nonceField = requiredString("nonce")
        if (requestId.isNullOrBlank()) fail(FleetError.INVALID_PARAMETERS, "missing field 'requestId'")
        if (op.isBlank()) fail(FleetError.INVALID_PARAMETERS, "missing field 'op'")
        if (target.isBlank()) fail(FleetError.INVALID_PARAMETERS, "missing field 'targetNodeRef'")
        if (!NONCE_HEX.matches(nonceField)) {
            fail(FleetError.INVALID_PARAMETERS, "nonce must be a unique 128-bit value as $FLEET_NONCE_HEX_CHARS hex characters")
        }

        val timestampMs = requiredLong("timestampMs")
        val ttlMs = optionalLong("ttlMs") ?: FLEET_DEFAULT_TTL_MS
        if (ttlMs <= 0 || ttlMs > FLEET_MAX_TTL_MS) {
            fail(FleetError.INVALID_PARAMETERS, "ttlMs must be between 1 and $FLEET_MAX_TTL_MS")
        }

        optionalInt("protocolMajor")?.let { major ->
            if (major != BRIDGE_PROTOCOL_MAJOR) {
                fail(FleetError.UNSUPPORTED_OP, "node speaks protocol $BRIDGE_PROTOCOL_MAJOR, request uses $major")
            }
        }
        optionalInt("protocolMinor")?.let { minor ->
            if (minor > BRIDGE_PROTOCOL_MINOR) {
                fail(FleetError.UNSUPPORTED_OP, "unsupported protocol minor $minor")
            }
        }

        val params = optionalObject("params") ?: JsonObject(emptyMap())
        val trigger = optionalObject("trigger")
        val destructive = optionalBoolean("destructive")
        val actionRef = optionalString("actionRef")
        val macroId = optionalString("macroId")
        val onError = optionalString("onError")
        if (onError != null && onError !in FleetOnError.ALL) {
            fail(FleetError.INVALID_PARAMETERS, "onError must be abort or continue")
        }
        val stepIndex = optionalInt("stepIndex")
        if (stepIndex != null && stepIndex < 0) fail(FleetError.INVALID_PARAMETERS, "stepIndex must be >= 0")
        val dryRun = optionalBoolean("dryRun") ?: false
        val steps = optionalArray("steps")?.let { parseSteps(it) } ?: emptyList()

        when (op) {
            FleetOp.CAPABILITIES, FleetOp.NODE_STATUS -> Unit
            FleetOp.REMOTE_COMMAND -> if (actionRef.isNullOrBlank()) fail(FleetError.INVALID_PARAMETERS, "missing field 'actionRef'")
            FleetOp.MACRO_STEP -> {
                if (actionRef.isNullOrBlank()) fail(FleetError.INVALID_PARAMETERS, "missing field 'actionRef'")
                if (macroId.isNullOrBlank()) fail(FleetError.INVALID_PARAMETERS, "missing field 'macroId'")
                if (stepIndex == null) fail(FleetError.INVALID_PARAMETERS, "missing field 'stepIndex'")
                if (onError == null) fail(FleetError.INVALID_PARAMETERS, "missing field 'onError'")
            }
            FleetOp.MACRO_RUN -> {
                if (macroId.isNullOrBlank()) fail(FleetError.INVALID_PARAMETERS, "missing field 'macroId'")
                if (root["steps"] == null) fail(FleetError.INVALID_PARAMETERS, "missing field 'steps'")
            }
            else -> fail(FleetError.UNSUPPORTED_OP, "no such op '$op'")
        }

        FleetParseResult.Ok(
            FleetRequest(
                op = op,
                requestId = requestId!!,
                targetNodeRef = target,
                nonce = nonceField.lowercase(),
                timestampMs = timestampMs,
                ttlMs = ttlMs,
                actionRef = actionRef,
                params = params,
                destructive = destructive,
                trigger = trigger,
                macroId = macroId,
                stepIndex = stepIndex,
                onError = onError,
                steps = steps,
                dryRun = dryRun,
                protocolMajor = optionalInt("protocolMajor"),
                protocolMinor = optionalInt("protocolMinor"),
            ),
        )
    } catch (e: ParseException) {
        FleetParseResult.Err(e.code, e.message, e.requestId)
    }

    private fun parseSteps(arr: JsonArray): List<FleetMacroStep> = arr.mapIndexed { i, el ->
        val obj = el as? JsonObject ?: fail(FleetError.INVALID_PARAMETERS, "steps[$i] must be an object")
        val stepExtra = obj.keys - STEP_KEYS
        if (stepExtra.isNotEmpty()) {
            fail(FleetError.INVALID_PARAMETERS, "unknown field(s) in steps[$i]: ${stepExtra.sorted().joinToString()}")
        }
        val ref = obj.stringField("actionRef")
            ?: fail(FleetError.INVALID_PARAMETERS, "steps[$i] missing actionRef")
        if (ref.isBlank()) fail(FleetError.INVALID_PARAMETERS, "steps[$i] missing actionRef")
        val stepParams = when (val p = obj["params"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> p
            else -> fail(FleetError.INVALID_PARAMETERS, "steps[$i].params must be an object")
        }
        val stepOnError = when (val e = obj["onError"]) {
            null -> FleetOnError.ABORT
            else -> {
                val prim = e as? JsonPrimitive
                if (prim == null || !prim.isString || prim.content !in FleetOnError.ALL) {
                    fail(FleetError.INVALID_PARAMETERS, "steps[$i].onError must be abort or continue")
                }
                prim.content
            }
        }
        FleetMacroStep(ref, stepParams, stepOnError)
    }

    private fun fail(code: String, message: String): Nothing = throw ParseException(code, message, requestId)

    private fun requiredString(key: String): String =
        optionalString(key) ?: fail(FleetError.INVALID_PARAMETERS, "missing field '$key'")

    private fun optionalString(key: String): String? = root.stringField(key)

    private fun requiredLong(key: String): Long = when (val el = root[key]) {
        null -> fail(FleetError.INVALID_PARAMETERS, "missing field '$key'")
        else -> (el as? JsonPrimitive)?.longOrNull
            ?: fail(FleetError.INVALID_PARAMETERS, "'$key' must be an integer")
    }

    private fun optionalLong(key: String): Long? {
        val el = root[key] ?: return null
        return (el as? JsonPrimitive)?.longOrNull
            ?: fail(FleetError.INVALID_PARAMETERS, "'$key' must be an integer")
    }

    private fun optionalInt(key: String): Int? {
        val el = root[key] ?: return null
        return (el as? JsonPrimitive)?.intOrNull
            ?: fail(FleetError.INVALID_PARAMETERS, "'$key' must be an integer")
    }

    private fun optionalBoolean(key: String): Boolean? {
        val el = root[key] ?: return null
        return (el as? JsonPrimitive)?.booleanOrNull
            ?: fail(FleetError.INVALID_PARAMETERS, "'$key' must be a boolean")
    }

    private fun optionalObject(key: String): JsonObject? {
        val el = root[key] ?: return null
        return el as? JsonObject ?: fail(FleetError.INVALID_PARAMETERS, "'$key' must be an object")
    }

    private fun optionalArray(key: String): JsonArray? {
        val el = root[key] ?: return null
        return runCatching { el.jsonArray }.getOrNull()
            ?: fail(FleetError.INVALID_PARAMETERS, "'$key' must be an array")
    }

    private fun JsonObject.stringField(key: String): String? {
        val el = this[key] ?: return null
        val prim = el as? JsonPrimitive
        if (prim == null || !prim.isString) fail(FleetError.INVALID_PARAMETERS, "'$key' must be a string")
        if (prim.content.length > MAX_STRING) fail(FleetError.INVALID_PARAMETERS, "'$key' is longer than $MAX_STRING characters")
        return prim.content
    }
}
