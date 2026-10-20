package ai.rootforge.os.bridge

import ai.rootforge.os.config.ConfigBackup
import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.shizuku.PrivilegedResult
import ai.rootforge.os.shizuku.PrivilegedShell
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val NODE = "rf-00112233445566778899aabbccddeeff"
internal val CALLER = CallerIdentity("ai.droidcommand.app", "aa".repeat(32))
internal const val NOW = 1_700_000_000_000L

internal fun nonce(n: Int): String = n.toString(16).padStart(FLEET_NONCE_HEX_CHARS, '0')

internal fun memoryStore() = object : PairingPersistence {
    var json = "[]"
    override fun read() = json
    override fun write(j: String) { json = j }
}

internal fun fleetJson(
    op: String = FleetOp.CAPABILITIES,
    nonce: String,
    now: Long = NOW,
    ttlMs: Long? = null,
    extra: JsonObject = JsonObject(emptyMap()),
    params: JsonObject = JsonObject(emptyMap()),
    target: String = NODE,
    requestId: String = "dca-1",
): String {
    val body = buildJsonObject {
        put("op", op)
        put("requestId", requestId)
        put("targetNodeRef", target)
        put("nonce", nonce)
        put("timestampMs", now)
        if (ttlMs != null) put("ttlMs", ttlMs)
        if (params.isNotEmpty()) put("params", params)
            extra.forEach { (k, v) -> put(k, v) }
    }
    return body.toString()
}

internal fun fleetExec(
    pairing: PairingStore = PairingStore(memoryStore()).also { it.approve(CALLER) },
    approvals: ApprovalCenter = ApprovalCenter(),
    backend: RecordingBackend = RecordingBackend(),
    enabled: Boolean = true,
    privileged: PrivilegedShell = FakePrivilegedShell(),
    replay: ReplayGuard = ReplayGuard(clock = { NOW }),
    statusSource: NodeStatusSource = NodeStatusSource.Default,
    clock: () -> Long = { NOW },
    configBackup: ConfigBackup = ConfigBackup(),
): FleetExecutor = FleetExecutor(NODE, backend, approvals, pairing, { enabled }, privileged, replay, statusSource, clock, configBackup)

internal fun parseFleet(line: String) = bridgeJson.decodeFromString<FleetResponse>(line)

internal class FakePrivilegedShell(
    var available: Boolean = true,
    var grants: MutableList<Pair<String, String>> = mutableListOf(),
) : PrivilegedShell {
    override fun isAvailable(): Boolean = available
    override fun exec(argv: List<String>, timeoutMs: Long) = PrivilegedResult.Failure("exec is off")
    override fun readDeviceState(): PrivilegedResult =
        PrivilegedResult.Success(0, "uid=0(root)", "", timedOut = false)
    override fun pmGrant(packageName: String, permission: String): PrivilegedResult {
        grants += packageName to permission
        return PrivilegedResult.Success(0, "", "", timedOut = false)
    }
}
