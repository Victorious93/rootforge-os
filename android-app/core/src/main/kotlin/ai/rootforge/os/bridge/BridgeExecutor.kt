package ai.rootforge.os.bridge

import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.module.ModuleSpec
import ai.rootforge.os.module.ModuleTarget
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class BridgeDevice(val serial: String, val mode: String, val state: String, val usable: Boolean, val note: String)

/** What the bridge can ask of the app. Implemented by the real controller and by a fake in tests. */
interface BridgeBackend {
    val appVersion: String
    fun devices(): List<BridgeDevice>
    suspend fun deviceInfo(): Result<Map<String, String>>
    suspend fun adbShell(command: String): Result<Pair<String, Int?>>
    suspend fun adbReboot(target: String): Result<Unit>
    suspend fun fastbootReboot(target: String): Result<Unit>
    suspend fun moduleBuild(spec: ModuleSpec): Result<Pair<String, String>>
    suspend fun fastbootFlash(partition: String, imageName: String, source: String): Result<Unit>
    suspend fun fastbootUnlock(source: String): Result<Unit>
    suspend fun fastbootLock(source: String): Result<Unit>
    suspend fun fastbootSetSlot(slot: String, source: String): Result<Unit>
}

private class BadParameters(message: String) : Exception(message)

/**
 * Turns one request line into one response line. Every gate lives here, in
 * this order: well-formed, supported protocol major, addressed to this node,
 * caller paired, operation known, parameters valid, approval for anything
 * that is not a read, then the operation under a timeout.
 *
 * Nothing in a request can raise its own tier, skip approval or pick a file
 * path: images are referred to by library name, and the typed confirmation
 * for destructive operations is requested by the controller itself.
 */
class BridgeExecutor(
    private val nodeId: String,
    private val backend: BridgeBackend,
    private val approvals: ApprovalCenter,
    private val pairing: PairingStore,
) {
    private class Operation(
        val name: String,
        val tier: Tier,
        val description: String,
        val params: String,
        val run: suspend (JsonObject, CallerIdentity) -> JsonObject,
    )

    private val operations: Map<String, Operation> = listOf(
        Operation("rootforge.capabilities.get", Tier.READ, "Node identity, version and the operations this controller may call.", "none") { _, _ -> capabilities() },
        Operation("rootforge.devices.list", Tier.READ, "USB targets attached to the host phone (adb, fastboot, edl).", "none") { _, _ -> devices() },
        Operation("rootforge.device.info", Tier.READ, "Properties of the connected target (getprop or fastboot getvar).", "none") { _, _ ->
            val info = backend.deviceInfo().getOrElse { throw OperationFailed(ErrorCategory.NO_TARGET, it.message) }
            buildJsonObject { put("info", buildJsonObject { info.forEach { (k, v) -> put(k, v) } }) }
        },
        Operation("rootforge.adb.shell", Tier.CONTROL, "Run one shell command on the target over ADB.", "command: string") { p, _ ->
            val cmd = p.string("command", max = 2000)
            if (cmd.contains('\u0000')) throw BadParameters("command contains a NUL byte")
            val (out, code) = backend.adbShell(cmd).getOrElse { throw OperationFailed(ErrorCategory.FAILED, it.message) }
            buildJsonObject { put("exit_code", code?.let { JsonPrimitive(it) } ?: JsonNull); put("output", out.take(MAX_OUTPUT)); put("truncated", out.length > MAX_OUTPUT) }
        },
        Operation("rootforge.adb.reboot", Tier.CONTROL, "Reboot the target (system, bootloader, recovery, sideload, fastboot, edl).", "target?: string") { p, _ ->
            val t = p.stringOrNull("target") ?: ""
            if (t !in ADB_REBOOT_TARGETS) throw BadParameters("target must be one of ${ADB_REBOOT_TARGETS.filter { it.isNotEmpty() }}")
            backend.adbReboot(t).getOrElse { throw OperationFailed(ErrorCategory.FAILED, it.message) }
            buildJsonObject { put("requested", t.ifEmpty { "system" }) }
        },
        Operation("rootforge.fastboot.reboot", Tier.CONTROL, "Reboot a target that is in fastboot (system, bootloader, recovery, fastbootd).", "target?: string") { p, _ ->
            val t = p.stringOrNull("target") ?: "system"
            if (t !in FASTBOOT_REBOOT_TARGETS) throw BadParameters("target must be one of $FASTBOOT_REBOOT_TARGETS")
            backend.fastbootReboot(t).getOrElse { throw OperationFailed(ErrorCategory.FAILED, it.message) }
            buildJsonObject { put("requested", t) }
        },
        Operation("rootforge.module.build", Tier.CONTROL, "Build a Magisk/KernelSU/APatch module zip into the app library (no device is touched).", "id, name, version?, version_code?, author?, description?, target?") { p, _ ->
            val spec = ModuleSpec(
                id = p.string("id", max = 64),
                name = p.string("name", max = 80),
                version = p.stringOrNull("version", max = 32) ?: "v1.0",
                versionCode = p.intOrNull("version_code") ?: 1,
                author = p.stringOrNull("author", max = 64) ?: "RootForge",
                description = p.stringOrNull("description", max = 200) ?: "",
                target = p.stringOrNull("target")?.let { t -> ModuleTarget.entries.firstOrNull { it.name.equals(t, true) } ?: throw BadParameters("unknown target '$t'") } ?: ModuleTarget.MAGISK,
            )
            val (file, sha) = backend.moduleBuild(spec).getOrElse { throw BadParameters(it.message ?: "invalid module") }
            buildJsonObject { put("library_file", file); put("sha256", sha) }
        },
        Operation("rootforge.fastboot.flash", Tier.DESTRUCTIVE, "Flash a library image to a partition. Typed confirmation on the host phone is required every time.", "partition: string, image: string (library file name)") { p, c ->
            val part = p.string("partition", max = 40)
            val image = p.string("image", max = 96)
            backend.fastbootFlash(part, image, c.packageName).getOrElse { throw OperationFailed(failureCategory(it), it.message) }
            buildJsonObject { put("flashed", part); put("image", image) }
        },
        Operation("rootforge.fastboot.slot.set", Tier.DESTRUCTIVE, "Switch the active A/B slot. Typed confirmation required.", "slot: a|b") { p, c ->
            val slot = p.string("slot", max = 1)
            if (slot != "a" && slot != "b") throw BadParameters("slot must be a or b")
            backend.fastbootSetSlot(slot, c.packageName).getOrElse { throw OperationFailed(failureCategory(it), it.message) }
            buildJsonObject { put("active_slot", slot) }
        },
        Operation("rootforge.fastboot.unlock", Tier.DESTRUCTIVE, "Unlock the bootloader (erases all data). Typed confirmation required.", "none") { _, c ->
            backend.fastbootUnlock(c.packageName).getOrElse { throw OperationFailed(failureCategory(it), it.message) }
            buildJsonObject { put("requested", "unlock") }
        },
        Operation("rootforge.fastboot.lock", Tier.DESTRUCTIVE, "Lock the bootloader (erases all data). Typed confirmation required.", "none") { _, c ->
            backend.fastbootLock(c.packageName).getOrElse { throw OperationFailed(failureCategory(it), it.message) }
            buildJsonObject { put("requested", "lock") }
        },
    ).associateBy { it.name }

    suspend fun handle(requestLine: String, caller: CallerIdentity): String =
        bridgeJson.encodeToString(process(requestLine, caller))

    private suspend fun process(requestLine: String, caller: CallerIdentity): BridgeResponse {
        if (requestLine.length > MAX_REQUEST) return error(null, ErrorCategory.MALFORMED, "request larger than $MAX_REQUEST bytes")
        val request = runCatching { bridgeJson.decodeFromString<BridgeRequest>(requestLine) }
            .getOrElse { return error(null, ErrorCategory.MALFORMED, "not a valid bridge request") }
        val id = request.requestId
        if (request.protocolMajor != BRIDGE_PROTOCOL_MAJOR) {
            return error(id, ErrorCategory.PROTOCOL, "node speaks protocol $BRIDGE_PROTOCOL_MAJOR, request uses ${request.protocolMajor}")
        }
        if (request.targetNodeId != nodeId) return error(id, ErrorCategory.WRONG_NODE, "this node is not ${request.targetNodeId}")

        when (pairing.status(caller)) {
            PairingStatus.PAIRED -> Unit
            PairingStatus.CERT_CHANGED -> {
                pairing.noteAttempt(caller)
                return error(id, ErrorCategory.NOT_PAIRED, "${caller.packageName} is paired with a different signing certificate; the owner must re-pair it in RootForge OS")
            }
            else -> {
                pairing.noteAttempt(caller)
                return error(id, ErrorCategory.NOT_PAIRED, "open RootForge OS > Settings > Controllers and approve ${caller.packageName}")
            }
        }

        val op = operations[request.operation] ?: return error(id, ErrorCategory.UNKNOWN_OPERATION, "no such operation '${request.operation}'")

        if (op.tier == Tier.CONTROL) {
            val ok = approvals.request(
                title = "Allow ${op.name}?",
                lines = listOf("Requested by ${caller.packageName}", op.description) + request.parameters.entries.map { "${it.key} = ${it.value.toString().take(300)}" },
                source = caller.packageName,
            )
            if (!ok) return error(id, ErrorCategory.DENIED, "the person at the host phone did not approve ${op.name}")
        }

        val budget = request.timeoutMs.toLong().coerceIn(1_000, MAX_TIMEOUT).let { if (op.tier == Tier.READ) it else maxOf(it, MIN_ACTION_TIMEOUT) }
        return try {
            val result = withTimeout(budget) { op.run(request.parameters, caller) }
            BridgeResponse(BRIDGE_PROTOCOL_MAJOR, id, nodeId, true, result)
        } catch (e: BadParameters) {
            error(id, ErrorCategory.BAD_PARAMETERS, e.message ?: "bad parameters")
        } catch (e: OperationFailed) {
            error(id, e.category, e.detail ?: "operation failed")
        } catch (e: TimeoutCancellationException) {
            error(id, ErrorCategory.TIMEOUT, "${op.name} did not finish within ${budget} ms")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error(id, ErrorCategory.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun failureCategory(t: Throwable): String = when {
        t.message?.contains("not confirmed", ignoreCase = true) == true -> ErrorCategory.DENIED
        t.message?.contains("not connected", ignoreCase = true) == true || t.message?.contains("not in fastboot", ignoreCase = true) == true -> ErrorCategory.NO_TARGET
        else -> ErrorCategory.FAILED
    }

    private fun capabilities(): JsonObject = buildJsonObject {
        put("protocol_major", BRIDGE_PROTOCOL_MAJOR)
        put("node_kind", "android-host-app")
        put("app", "rootforge-os-android")
        put("app_version", backend.appVersion)
        put("node_id", nodeId)
        put("usb_host", true)
        putJsonArray("operations") {
            operations.values.forEach { op ->
                add(buildJsonObject {
                    put("name", op.name); put("tier", op.tier.wire); put("description", op.description); put("parameters", op.params)
                })
            }
        }
    }

    private fun devices(): JsonObject = buildJsonObject {
        put("devices", buildJsonArray {
            backend.devices().forEach { d ->
                add(buildJsonObject { put("serial", d.serial); put("mode", d.mode); put("state", d.state); put("usable", d.usable); put("note", d.note) })
            }
        })
    }

    private fun error(requestId: String?, category: String, message: String) =
        BridgeResponse(BRIDGE_PROTOCOL_MAJOR, requestId, nodeId, false, null, BridgeErrorBody(category, message))

    private fun JsonObject.string(key: String, max: Int): String = stringOrNull(key, max) ?: throw BadParameters("missing parameter '$key'")

    private fun JsonObject.stringOrNull(key: String, max: Int = 200): String? {
        val el: JsonElement = this[key] ?: return null
        val prim = el as? JsonPrimitive ?: throw BadParameters("'$key' must be a string")
        if (!prim.isString) throw BadParameters("'$key' must be a string")
        val v = prim.content
        if (v.length > max) throw BadParameters("'$key' is longer than $max characters")
        return v
    }

    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: throw BadParameters("'$key' must be an integer") }

    private class OperationFailed(val category: String, val detail: String?) : Exception(detail)

    companion object {
        private const val MAX_REQUEST = 64 * 1024
        private const val MAX_OUTPUT = 64 * 1024
        private const val MAX_TIMEOUT = 15 * 60_000L
        private const val MIN_ACTION_TIMEOUT = 120_000L
        private val ADB_REBOOT_TARGETS = listOf("", "bootloader", "recovery", "sideload", "fastboot", "edl")
        private val FASTBOOT_REBOOT_TARGETS = listOf("system", "bootloader", "recovery", "fastbootd")
    }
}
