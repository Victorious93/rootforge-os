package ai.rootforge.os.bridge

import kotlinx.serialization.json.JsonObject

/**
 * One bound-service entry point for both envelopes:
 * - protocol 1.1 fleet (`op`, `requestId`, nonce) → [FleetExecutor]
 * - protocol 1 snake_case (`operation`, `rootforge.*`) → [BridgeExecutor]
 *
 * Discrimination is the presence of a top-level `op` field after JSON parse.
 */
class BridgeIngress(
    private val legacy: BridgeExecutor,
    private val fleet: FleetExecutor,
) {
    suspend fun handle(requestLine: String, caller: CallerIdentity): String {
        val obj = runCatching { bridgeJson.parseToJsonElement(requestLine) as? JsonObject }.getOrNull()
        return if (obj != null && "op" in obj) fleet.handle(requestLine, caller)
        else legacy.handle(requestLine, caller)
    }
}
