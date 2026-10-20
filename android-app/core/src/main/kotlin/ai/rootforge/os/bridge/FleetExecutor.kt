package ai.rootforge.os.bridge

import ai.rootforge.os.config.ConfigBackup
import ai.rootforge.os.config.ConfigBackupException
import ai.rootforge.os.config.ConfigConflictPolicy
import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.core.Library
import ai.rootforge.os.module.ModuleSpec
import ai.rootforge.os.module.ModuleTarget
import ai.rootforge.os.shizuku.NullPrivilegedShell
import ai.rootforge.os.shizuku.PrivilegedNames
import ai.rootforge.os.shizuku.PrivilegedResult
import ai.rootforge.os.shizuku.PrivilegedShell
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private class FleetBadParameters(message: String) : Exception(message)
private class FleetOpFailed(val code: String, message: String) : Exception(message)

/**
 * Protocol 1.1 fleet remote-control handler. Gates, in order: well-formed
 * envelope, caller identity (package + cert, never JSON), external-control
 * switch, pairing, node ref, replay/expiry, then the op. `trigger` is
 * logged nowhere and never grants, pairs, or skips a confirm.
 */
class FleetExecutor(
    private val nodeId: String,
    private val backend: BridgeBackend,
    private val approvals: ApprovalCenter,
    private val pairing: PairingStore,
    private val bridgeEnabled: () -> Boolean = { true },
    private val privileged: PrivilegedShell = NullPrivilegedShell(),
    private val replay: ReplayGuard = ReplayGuard(),
    private val statusSource: NodeStatusSource = NodeStatusSource.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val configBackup: ConfigBackup = ConfigBackup(),
) {
    suspend fun handle(requestLine: String, caller: CallerIdentity): String =
        bridgeJson.encodeToString(process(requestLine, caller))

    private suspend fun process(requestLine: String, caller: CallerIdentity): FleetResponse {
        val parsed = parseFleetRequest(requestLine)
        val request = when (parsed) {
            is FleetParseResult.Err -> return error(parsed.requestId, parsed.code, parsed.message)
            is FleetParseResult.Ok -> parsed.request
        }
        val id = request.requestId

        if (!caller.isComplete()) {
            return error(id, FleetError.UNAUTHORIZED, "could not establish the calling app's identity")
        }

        val status = pairing.status(caller)
        if (status != PairingStatus.PAIRED) pairing.noteAttempt(caller)

        if (!bridgeEnabled()) {
            return error(id, FleetError.EXTERNAL_CONTROL_DISABLED, "external control is switched off in RootForge OS > Settings")
        }
        when (status) {
            PairingStatus.PAIRED -> Unit
            PairingStatus.CERT_CHANGED ->
                return error(id, FleetError.NOT_PAIRED, "${caller.packageName} is paired with a different signing certificate; the owner must re-pair it in RootForge OS")
            else ->
                return error(id, FleetError.NOT_PAIRED, "open RootForge OS > Settings > Controllers and approve ${caller.packageName}")
        }

        if (request.targetNodeRef != nodeId) {
            return error(id, FleetError.NOT_FOUND, "this node is not ${request.targetNodeRef}")
        }

        when (replay.check(caller.sourceNodeRef(), request.nonce, request.timestampMs, request.ttlMs)) {
            ReplayVerdict.REPLAY -> return error(id, FleetError.REPLAY_DETECTED, "nonce was already used")
            ReplayVerdict.EXPIRED -> return error(id, FleetError.EXPIRED, "request expired or clock skew exceeds ${FLEET_MAX_SKEW_MS} ms")
            ReplayVerdict.BUSY -> return error(id, FleetError.INTERNAL, "too many recent fleet nonces; retry after some expire")
            ReplayVerdict.OK -> Unit
        }

        return try {
            val result = when (request.op) {
                FleetOp.CAPABILITIES -> capabilities()
                FleetOp.NODE_STATUS -> nodeStatus(request)
                FleetOp.REMOTE_COMMAND -> runAction(request.actionRef!!, request.params, caller)
                FleetOp.MACRO_STEP -> runAction(request.actionRef!!, request.params, caller)
                FleetOp.MACRO_RUN -> runMacro(request, caller)
                FleetOp.CONFIG_EXPORT -> runConfigExport(request)
                FleetOp.CONFIG_IMPORT -> runConfigImport(request, caller)
                else -> throw FleetOpFailed(FleetError.UNSUPPORTED_OP, "no such op '${request.op}'")
            }
            FleetResponse(ok = true, requestId = id, nodeRef = nodeId, result = result)
        } catch (e: ConfigBackupException) {
            error(id, e.code, e.message)
        } catch (e: FleetBadParameters) {
            error(id, FleetError.INVALID_PARAMETERS, e.message ?: "invalid parameters")
        } catch (e: FleetOpFailed) {
            error(id, e.code, e.message ?: e.code)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error(id, FleetError.INTERNAL, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun nodeStatus(request: FleetRequest): JsonObject {
        val extra = request.params.keys - NODE_STATUS_KEYS
        if (extra.isNotEmpty()) {
            throw FleetBadParameters("unknown parameter(s): ${extra.sorted().joinToString()}")
        }
        val asked = request.params.stringOrNull("nodeId")
        if (!asked.isNullOrBlank() && asked != nodeId) {
            throw FleetOpFailed(FleetError.NOT_FOUND, "this node is not $asked")
        }
        return try {
            NodeStatusSnapshotCodec.encode(statusSource.snapshot(nodeId, clock()))
        } catch (e: IllegalArgumentException) {
            throw FleetBadParameters(e.message ?: "invalid snapshot")
        }
    }

    private fun capabilities(): JsonObject = buildJsonObject {
        put("protocolMajor", BRIDGE_PROTOCOL_MAJOR)
        put("protocolMinor", BRIDGE_PROTOCOL_MINOR)
        put("fleetClass", "external_node")
        put("nodeKind", backend.nodeKind)
        put("app", "rootforge-os-android")
        put("appVersion", backend.appVersion)
        put("nodeRef", nodeId)
        put("usbHost", backend.usbHost)
        putJsonArray("ops") { RemoteActionCatalog.ENVELOPE_OPS.forEach { add(it.toJson()) } }
        putJsonArray("actions") { RemoteActionCatalog.ACTIONS.filter { it.remoteAllowed }.forEach { add(it.toJson()) } }
    }

    private fun refuseRemoteConfig(op: String) {
        val cap = RemoteActionCatalog.envelope(op)
            ?: throw FleetOpFailed(FleetError.UNSUPPORTED_OP, "no such op '$op'")
        if (!cap.remoteAllowed) {
            throw FleetOpFailed(FleetError.CAPABILITY_OFF, "$op is not allowed over the fleet bridge")
        }
    }

    private fun runConfigExport(request: FleetRequest): JsonObject {
        refuseRemoteConfig(request.op)
        val extra = request.params.keys - setOf("clearPayload", "packageName")
        if (extra.isNotEmpty()) {
            throw FleetBadParameters("unknown parameter(s): ${extra.sorted().joinToString()}")
        }
        val restrict = request.params.stringOrNull("packageName", max = 256)
        return configBackup.export(restrict)
    }

    private suspend fun runConfigImport(request: FleetRequest, caller: CallerIdentity): JsonObject {
        refuseRemoteConfig(request.op)
        val extra = request.params.keys - setOf("bundle", "dryRun", "conflictPolicy")
        if (extra.isNotEmpty()) {
            throw FleetBadParameters("unknown parameter(s): ${extra.sorted().joinToString()}")
        }
        val dryRun = request.dryRun || request.params.flag("dryRun")
        val policy = request.params.stringOrNull("conflictPolicy", max = 16) ?: ConfigConflictPolicy.SKIP
        val bundleEl = request.params["bundle"]
        val bundleText = when (bundleEl) {
            null -> null
            is JsonPrimitive -> if (bundleEl.isString) bundleEl.content else throw FleetBadParameters("'bundle' must be a string or object")
            is JsonObject -> null
            else -> throw FleetBadParameters("'bundle' must be a string or object")
        }
        val bundleObject = bundleEl as? JsonObject
        return configBackup.import(
            bundleText = bundleText,
            bundleObject = bundleObject,
            dryRun = dryRun,
            conflictPolicy = policy,
            source = caller.packageName,
        )
    }

    private fun JsonObject.flag(key: String): Boolean {
        val el = this[key] ?: return false
        val prim = el as? JsonPrimitive ?: throw FleetBadParameters("'$key' must be a boolean or string")
        prim.booleanOrNull?.let { return it }
        if (prim.isString) return prim.content.equals("true", ignoreCase = true)
        throw FleetBadParameters("'$key' must be a boolean or string")
    }

    private suspend fun runMacro(request: FleetRequest, caller: CallerIdentity): JsonObject {
        if (request.dryRun) {
            return buildJsonObject {
                put("macroId", request.macroId)
                put("dryRun", true)
                putJsonArray("steps") {
                    request.steps.forEachIndexed { i, step ->
                        add(buildJsonObject {
                            put("stepIndex", i)
                            put("actionRef", step.actionRef)
                            val cap = RemoteActionCatalog.action(step.actionRef)
                            when {
                                RemoteActionCatalog.isForbiddenDestructive(step.actionRef) -> {
                                    put("kind", FleetKind.DESTRUCTIVE)
                                    put("remoteAllowed", false)
                                    put("requiresConfirm", true)
                                }
                                cap == null -> {
                                    put("kind", JsonNull)
                                    put("remoteAllowed", false)
                                    put("requiresConfirm", false)
                                    put("error", FleetError.UNSUPPORTED_OP)
                                }
                                else -> {
                                    put("kind", cap.kind)
                                    put("remoteAllowed", cap.remoteAllowed)
                                    put("requiresConfirm", cap.requiresConfirm)
                                }
                            }
                        })
                    }
                }
            }
        }
        val stepResults = ArrayList<JsonObject>()
        for ((i, step) in request.steps.withIndex()) {
            val outcome = try {
                val result = runAction(step.actionRef, step.params, caller)
                buildJsonObject {
                    put("stepIndex", i)
                    put("actionRef", step.actionRef)
                    put("ok", true)
                    put("result", result)
                }
            } catch (e: FleetBadParameters) {
                stepError(i, step.actionRef, FleetError.INVALID_PARAMETERS, e.message)
            } catch (e: FleetOpFailed) {
                stepError(i, step.actionRef, e.code, e.message)
            }
            stepResults += outcome
            val ok = outcome["ok"]?.let { (it as? JsonPrimitive)?.content == "true" } ?: false
            if (!ok && step.onError != FleetOnError.CONTINUE) break
        }
        return buildJsonObject {
            put("macroId", request.macroId)
            put("dryRun", false)
            put("steps", buildJsonArray { stepResults.forEach { add(it) } })
        }
    }

    private fun stepError(index: Int, actionRef: String, code: String, message: String?): JsonObject = buildJsonObject {
        put("stepIndex", index)
        put("actionRef", actionRef)
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message ?: code)
        })
    }

    private suspend fun runAction(actionRef: String, params: JsonObject, caller: CallerIdentity): JsonObject {
        if (RemoteActionCatalog.isForbiddenDestructive(actionRef)) {
            throw FleetOpFailed(
                FleetError.CONFIRMATION_REQUIRED,
                "$actionRef is destructive and is not run from a remote trigger; confirm it on the device",
            )
        }
        val cap = RemoteActionCatalog.action(actionRef)
            ?: throw FleetOpFailed(FleetError.UNSUPPORTED_OP, "no such action '$actionRef'")
        if (!cap.remoteAllowed) {
            throw FleetOpFailed(FleetError.CAPABILITY_OFF, "$actionRef is not allowed over the fleet bridge")
        }
        val extra = params.keys - allowedKeys(actionRef)
        if (extra.isNotEmpty()) {
            throw FleetBadParameters("unknown parameter(s): ${extra.sorted().joinToString()}")
        }

        if (cap.kind == FleetKind.ACTION) {
            val ok = approvals.request(
                title = "Allow $actionRef?",
                lines = listOf("Requested by ${caller.packageName}", cap.description) +
                    params.entries.map { "${it.key} = ${it.value.toString().take(300)}" },
                source = caller.packageName,
            )
            if (!ok) {
                throw FleetOpFailed(FleetError.CONFIRMATION_REQUIRED, "the person at the host phone did not approve $actionRef")
            }
        }

        return when (actionRef) {
            BridgeOp.DEVICES_LIST -> devices()
            BridgeOp.DEVICE_INFO -> {
                val info = backend.deviceInfo().getOrElse { throw FleetOpFailed(FleetError.NOT_FOUND, it.message ?: "no target") }
                buildJsonObject { put("info", buildJsonObject { info.forEach { (k, v) -> put(k, v) } }) }
            }
            BridgeOp.ADB_SHELL -> {
                val cmd = params.string("command", max = 2000)
                if (cmd.contains('\u0000')) throw FleetBadParameters("command contains a NUL byte")
                val (out, code) = backend.adbShell(cmd).getOrElse { throw FleetOpFailed(FleetError.INTERNAL, it.message ?: "shell failed") }
                buildJsonObject {
                    put("exit_code", code?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("output", out.take(MAX_OUTPUT))
                    put("truncated", out.length > MAX_OUTPUT)
                }
            }
            BridgeOp.ADB_REBOOT -> {
                val t = params.stringOrNull("target") ?: ""
                if (t !in ADB_REBOOT_TARGETS) throw FleetBadParameters("target must be one of ${ADB_REBOOT_TARGETS.filter { it.isNotEmpty() }}")
                backend.adbReboot(t).getOrElse { throw FleetOpFailed(FleetError.INTERNAL, it.message ?: "reboot failed") }
                buildJsonObject { put("requested", t.ifEmpty { "system" }) }
            }
            BridgeOp.FASTBOOT_REBOOT -> {
                val t = params.stringOrNull("target") ?: "system"
                if (t !in FASTBOOT_REBOOT_TARGETS) throw FleetBadParameters("target must be one of $FASTBOOT_REBOOT_TARGETS")
                backend.fastbootReboot(t).getOrElse { throw FleetOpFailed(FleetError.INTERNAL, it.message ?: "reboot failed") }
                buildJsonObject { put("requested", t) }
            }
            BridgeOp.MODULE_BUILD -> {
                val spec = ModuleSpec(
                    id = params.string("id", max = 64),
                    name = params.string("name", max = 80),
                    version = params.stringOrNull("version", max = 32) ?: "v1.0",
                    versionCode = params.intOrNull("version_code") ?: 1,
                    author = params.stringOrNull("author", max = 64) ?: "RootForge",
                    description = params.stringOrNull("description", max = 200) ?: "",
                    target = params.stringOrNull("target")?.let { t ->
                        ModuleTarget.entries.firstOrNull { it.name.equals(t, true) }
                            ?: throw FleetBadParameters("unknown target '$t'")
                    } ?: ModuleTarget.MAGISK,
                )
                val (file, sha) = backend.moduleBuild(spec).getOrElse { throw FleetBadParameters(it.message ?: "invalid module") }
                buildJsonObject { put("library_file", file); put("sha256", sha) }
            }
            BridgeOp.FASTBOOT_FLASH -> {
                val part = params.string("partition", max = 40)
                if (!PARTITION.matches(part)) throw FleetBadParameters("partition is not a plausible name")
                val image = params.string("image", max = 96)
                if (Library.sanitize(image) != image) throw FleetBadParameters("image must be a library file name, not a path")
                backend.fastbootFlash(part, image, caller.packageName).getOrElse { throw flashFailure(it) }
                buildJsonObject { put("flashed", part); put("image", image) }
            }
            BridgeOp.FASTBOOT_SLOT -> {
                val slot = params.string("slot", max = 1)
                if (slot != "a" && slot != "b") throw FleetBadParameters("slot must be a or b")
                backend.fastbootSetSlot(slot, caller.packageName).getOrElse { throw flashFailure(it) }
                buildJsonObject { put("active_slot", slot) }
            }
            BridgeOp.FASTBOOT_UNLOCK -> {
                backend.fastbootUnlock(caller.packageName).getOrElse { throw flashFailure(it) }
                buildJsonObject { put("requested", "unlock") }
            }
            BridgeOp.FASTBOOT_LOCK -> {
                backend.fastbootLock(caller.packageName).getOrElse { throw flashFailure(it) }
                buildJsonObject { put("requested", "lock") }
            }
            RemoteActionCatalog.ACTION_SHIZUKU_DEVICE -> privilegedSnapshot()
            RemoteActionCatalog.ACTION_SHIZUKU_PM_GRANT -> pmGrant(params, caller)
            else -> throw FleetOpFailed(FleetError.UNSUPPORTED_OP, "no such action '$actionRef'")
        }
    }

    private suspend fun pmGrant(params: JsonObject, caller: CallerIdentity): JsonObject {
        val pkg = params.string("package", max = 256)
        val permission = params.string("permission", max = 256)
        if (!PrivilegedNames.isSafeAndroidName(pkg) || !PrivilegedNames.isSafeAndroidName(permission)) {
            throw FleetBadParameters("package and permission must be safe Android names")
        }
        val ok = approvals.request(
            title = "Shizuku: pm grant",
            lines = listOf(
                "Requested by ${caller.packageName}",
                "This writes a runtime permission on this phone.",
                "pm grant $pkg $permission",
            ),
            source = caller.packageName,
            phrase = "GRANT PERMISSION",
        )
        if (!ok) {
            throw FleetOpFailed(FleetError.CONFIRMATION_REQUIRED, "typed GRANT PERMISSION was not given; nothing ran")
        }
        if (!privileged.isAvailable()) {
            throw FleetOpFailed(FleetError.NOT_FOUND, "Shizuku is not connected")
        }
        return when (val result = privileged.pmGrant(pkg, permission)) {
            is PrivilegedResult.Success -> buildJsonObject {
                put("exitCode", result.exitCode)
                put("timedOut", result.timedOut)
            }
            is PrivilegedResult.Failure -> throw FleetOpFailed(FleetError.INTERNAL, result.reason)
        }
    }

    private suspend fun privilegedSnapshot(): JsonObject {
        if (!privileged.isAvailable()) throw FleetOpFailed(FleetError.NOT_FOUND, "Shizuku is not connected")
        return when (val result = privileged.readDeviceState()) {
            is PrivilegedResult.Success -> buildJsonObject {
                put("exitCode", result.exitCode)
                put("stdout", result.stdout.take(MAX_OUTPUT))
                put("truncated", result.stdout.length > MAX_OUTPUT)
            }
            is PrivilegedResult.Failure -> throw FleetOpFailed(FleetError.INTERNAL, result.reason)
        }
    }

    private fun flashFailure(t: Throwable): FleetOpFailed = when {
        t.message?.contains("not confirmed", ignoreCase = true) == true ->
            FleetOpFailed(FleetError.CONFIRMATION_REQUIRED, t.message ?: "not confirmed")
        t.message?.contains("not connected", ignoreCase = true) == true ||
            t.message?.contains("not in fastboot", ignoreCase = true) == true ->
            FleetOpFailed(FleetError.NOT_FOUND, t.message ?: "no target")
        else -> FleetOpFailed(FleetError.INTERNAL, t.message ?: "operation failed")
    }

    private fun devices(): JsonObject = buildJsonObject {
        put("devices", buildJsonArray {
            backend.devices().forEach { d ->
                add(buildJsonObject {
                    put("serial", d.serial); put("mode", d.mode); put("state", d.state)
                    put("usable", d.usable); put("note", d.note)
                })
            }
        })
    }

    private fun error(requestId: String?, code: String, message: String) =
        FleetResponse(ok = false, requestId = requestId, nodeRef = nodeId, error = FleetErrorBody(code, message))

    private fun JsonObject.string(key: String, max: Int): String =
        stringOrNull(key, max) ?: throw FleetBadParameters("missing parameter '$key'")

    private fun JsonObject.stringOrNull(key: String, max: Int = 200): String? {
        val el: JsonElement = this[key] ?: return null
        val prim = el as? JsonPrimitive ?: throw FleetBadParameters("'$key' must be a string")
        if (!prim.isString) throw FleetBadParameters("'$key' must be a string")
        val v = prim.content
        if (v.length > max) throw FleetBadParameters("'$key' is longer than $max characters")
        return v
    }

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: throw FleetBadParameters("'$key' must be an integer") }

    private fun RemoteCapability.toJson() = buildJsonObject {
        put("name", name)
        put("kind", kind)
        put("remoteAllowed", remoteAllowed)
        put("requiresConfirm", requiresConfirm)
    }

    companion object {
        private const val MAX_OUTPUT = 64 * 1024
        private val NODE_STATUS_KEYS = setOf("nodeId", "snapshot")
        private val ADB_REBOOT_TARGETS = listOf("", "bootloader", "recovery", "sideload", "fastboot", "edl")
        private val FASTBOOT_REBOOT_TARGETS = listOf("system", "bootloader", "recovery", "fastbootd")
        private val PARTITION = Regex("[A-Za-z0-9_.-]{1,40}")

        private fun allowedKeys(actionRef: String): Set<String> = when (actionRef) {
            BridgeOp.ADB_SHELL -> setOf("command")
            BridgeOp.ADB_REBOOT, BridgeOp.FASTBOOT_REBOOT -> setOf("target")
            BridgeOp.MODULE_BUILD -> setOf("id", "name", "version", "version_code", "author", "description", "target")
            BridgeOp.FASTBOOT_FLASH -> setOf("partition", "image")
            BridgeOp.FASTBOOT_SLOT -> setOf("slot")
            RemoteActionCatalog.ACTION_SHIZUKU_PM_GRANT -> setOf("package", "permission")
            else -> emptySet()
        }
    }
}
