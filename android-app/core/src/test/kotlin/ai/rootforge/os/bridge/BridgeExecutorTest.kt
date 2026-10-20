package ai.rootforge.os.bridge

import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.module.ModuleSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeExecutorTest {
    private val node = "rf-00112233445566778899aabbccddeeff"
    private val caller = CallerIdentity("ai.droidcommand.app", "aa".repeat(32))
    private val memory = object : PairingPersistence {
        var json = "[]"
        override fun read() = json
        override fun write(j: String) { json = j }
    }

    private fun exec(
        pairing: PairingStore = PairingStore(memory).also { it.approve(caller) },
        approvals: ApprovalCenter = ApprovalCenter(),
        backend: RecordingBackend = RecordingBackend(),
        enabled: Boolean = true,
    ) = Triple(BridgeExecutor(node, backend, approvals, pairing, bridgeEnabled = { enabled }), pairing, backend)

    private fun req(
        op: String,
        params: JsonObject = JsonObject(emptyMap()),
        id: String = "dca-1",
        target: String = node,
        major: Int = BRIDGE_PROTOCOL_MAJOR,
        timeoutMs: Int = 10_000,
    ) = bridgeJson.encodeToString(BridgeRequest(major, id, target, op, timeoutMs, params))

    private fun parse(line: String) = bridgeJson.decodeFromString<BridgeResponse>(line)

    private suspend fun CoroutineScope.approveNext(approvals: ApprovalCenter, ex: BridgeExecutor, request: String, ok: Boolean = true): BridgeResponse {
        val pending = async { parse(ex.handle(request, caller)) }
        approvals.answer(approvals.pending.value.single().id, ok)
        return pending.await()
    }

    @Test
    fun unpairedCallerIsRefusedAndQueued() = runTest {
        val pairing = PairingStore(memory)
        val (ex, _, _) = exec(pairing = pairing)
        val r = parse(ex.handle(req(BridgeOp.CAPABILITIES), caller))
        assertFalse(r.ok)
        assertEquals(ErrorCategory.NOT_PAIRED, r.error?.category)
        assertEquals(listOf(caller), pairing.pending.value)
        assertEquals("dca-1", r.requestId)
        assertEquals(node, r.nodeId)
    }

    @Test
    fun aChangedCertificateDoesNotInheritThePairing() = runTest {
        val pairing = PairingStore(memory).also { it.approve(caller) }
        val (ex, _, _) = exec(pairing = pairing)
        val other = caller.copy(certSha256 = "bb".repeat(32))
        val r = parse(ex.handle(req(BridgeOp.CAPABILITIES), other))
        assertEquals(ErrorCategory.NOT_PAIRED, r.error?.category)
        assertTrue(r.error!!.message.contains("different signing certificate"))
        assertEquals(listOf(other), pairing.pending.value)
    }

    @Test
    fun disabledBridgeStillRecordsPendingThenRefuses() = runTest {
        val pairing = PairingStore(memory)
        val (ex, _, _) = exec(pairing = pairing, enabled = false)
        val r = parse(ex.handle(req(BridgeOp.CAPABILITIES), caller))
        assertEquals(ErrorCategory.DISABLED, r.error?.category)
        assertEquals(listOf(caller), pairing.pending.value)
    }

    @Test
    fun aPairedCallerIsRefusedWhileTheSwitchIsOff() = runTest {
        val r = parse(exec(enabled = false).first.handle(req(BridgeOp.DEVICES_LIST), caller))
        assertEquals(ErrorCategory.DISABLED, r.error?.category)
    }

    @Test
    fun aRequestForAnotherNodeIsRejected() = runTest {
        val r = parse(exec().first.handle(req(BridgeOp.CAPABILITIES, target = "rf-other"), caller))
        assertEquals(ErrorCategory.WRONG_NODE, r.error?.category)
    }

    @Test
    fun aWrongProtocolMajorIsRejected() = runTest {
        val r = parse(exec().first.handle(req(BridgeOp.CAPABILITIES, major = 99), caller))
        assertEquals(ErrorCategory.PROTOCOL, r.error?.category)
    }

    @Test
    fun capabilitiesListsEveryDocumentedOperationAndNoMore() = runTest {
        val r = parse(exec().first.handle(req(BridgeOp.CAPABILITIES), caller))
        assertTrue(r.ok)
        assertEquals(node, r.nodeId)
        assertEquals(1, r.result!!.getValue("protocol_major").jsonPrimitive.intOrNull)
        assertEquals("android-host-app", r.result!!.getValue("node_kind").jsonPrimitive.content)
        assertEquals("0.1.0-test", r.result!!.getValue("app_version").jsonPrimitive.content)
        assertEquals(node, r.result!!.getValue("node_id").jsonPrimitive.content)
        assertTrue(r.result!!.getValue("usb_host").jsonPrimitive.content.toBoolean())
        val ops = r.result!!.getValue("operations").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet()
        assertEquals(BridgeOp.ALL, ops)
        assertFalse(ops.any { it.contains("edl") || it.contains("sideload") || it.contains("oem") })
    }

    @Test
    fun deviceListIsAReadAndDoesNotPrompt() = runTest {
        val backend = RecordingBackend()
        val approvals = ApprovalCenter()
        val r = parse(exec(approvals = approvals, backend = backend).first.handle(req(BridgeOp.DEVICES_LIST), caller))
        assertTrue(r.ok)
        assertTrue(approvals.pending.value.isEmpty())
        val devices = r.result!!.getValue("devices").jsonArray
        assertEquals(1, devices.size)
        assertEquals("ABC", devices[0].jsonObject.getValue("serial").jsonPrimitive.content)
        assertEquals("adb", devices[0].jsonObject.getValue("mode").jsonPrimitive.content)
        assertTrue(devices[0].jsonObject.getValue("usable").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun deviceInfoReturnsRowsOfTheConnectedTarget() = runTest {
        val r = parse(exec().first.handle(req(BridgeOp.DEVICE_INFO), caller))
        assertTrue(r.ok)
        assertEquals("Pixel Test", r.result!!.getValue("info").jsonObject.getValue("Model").jsonPrimitive.content)
    }

    @Test
    fun deviceInfoWithoutATargetIsNoTarget() = runTest {
        val backend = RecordingBackend().also { it.info = Result.failure(IllegalStateException("No target is connected in the app.")) }
        val r = parse(exec(backend = backend).first.handle(req(BridgeOp.DEVICE_INFO), caller))
        assertEquals(ErrorCategory.NO_TARGET, r.error?.category)
    }

    @Test
    fun aControlOperationNeedsApproval() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val (ex, _, backend) = exec(approvals = approvals)
        val r = approveNext(approvals, ex, req(BridgeOp.ADB_SHELL, buildJsonObject { put("command", JsonPrimitive("id")) }))
        assertTrue(r.ok)
        assertEquals("uid=0(root)", r.result!!.getValue("output").jsonPrimitive.content)
        assertEquals(0, r.result!!.getValue("exit_code").jsonPrimitive.intOrNull)
        assertFalse(r.result!!.getValue("truncated").jsonPrimitive.content.toBoolean())
        assertEquals(listOf("id"), backend.shells)
    }

    @Test
    fun denyingAControlOperationDoesNotRunIt() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val (ex, _, backend) = exec(approvals = approvals)
        val r = approveNext(approvals, ex, req(BridgeOp.ADB_REBOOT, buildJsonObject { put("target", JsonPrimitive("bootloader")) }), ok = false)
        assertEquals(ErrorCategory.DENIED, r.error?.category)
        assertTrue(backend.reboots.isEmpty())
    }

    @Test
    fun adbRebootOmittingTargetMeansSystem() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val (ex, _, backend) = exec(approvals = approvals)
        val r = approveNext(approvals, ex, req(BridgeOp.ADB_REBOOT))
        assertTrue(r.ok)
        assertEquals("system", r.result!!.getValue("requested").jsonPrimitive.content)
        assertEquals(listOf(""), backend.reboots)
    }

    @Test
    fun adbRebootRejectsAnUnknownTarget() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val (ex, _, _) = exec(approvals = approvals)
        val r = approveNext(approvals, ex, req(BridgeOp.ADB_REBOOT, buildJsonObject { put("target", JsonPrimitive("download")) }))
        assertEquals(ErrorCategory.BAD_PARAMETERS, r.error?.category)
    }

    @Test
    fun fastbootRebootAndModuleBuildAreControlOps() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val (ex, _, backend) = exec(approvals = approvals)
        val reboot = approveNext(approvals, ex, req(BridgeOp.FASTBOOT_REBOOT, buildJsonObject { put("target", JsonPrimitive("fastbootd")) }))
        assertTrue(reboot.ok)
        assertEquals(listOf("fastbootd"), backend.fbReboots)

        val built = approveNext(
            approvals,
            ex,
            req(
                BridgeOp.MODULE_BUILD,
                buildJsonObject {
                    put("id", JsonPrimitive("demo"))
                    put("name", JsonPrimitive("Demo"))
                    put("target", JsonPrimitive("kernelsu"))
                },
            ),
        )
        assertTrue(built.ok)
        assertEquals("x.zip", built.result!!.getValue("library_file").jsonPrimitive.content)
        assertEquals("abc", built.result!!.getValue("sha256").jsonPrimitive.content)
        assertEquals("demo", backend.modules.single().id)
        assertEquals(ai.rootforge.os.module.ModuleTarget.KERNELSU, backend.modules.single().target)
    }

    @Test
    fun flashRefersToALibraryNameNotAPath() = runTest {
        val (ex, _, backend) = exec()
        val r = parse(
            ex.handle(
                req(
                    BridgeOp.FASTBOOT_FLASH,
                    buildJsonObject {
                        put("partition", JsonPrimitive("boot"))
                        put("image", JsonPrimitive("patched.img"))
                    },
                ),
                caller,
            ),
        )
        assertTrue(r.ok)
        assertEquals("boot" to "patched.img", backend.flashes.single())
        assertEquals("boot", r.result!!.getValue("flashed").jsonPrimitive.content)
    }

    @Test
    fun aPathImageIsRejectedBeforeTheBackendRuns() = runTest {
        val (ex, _, backend) = exec()
        val r = parse(
            ex.handle(
                req(
                    BridgeOp.FASTBOOT_FLASH,
                    buildJsonObject {
                        put("partition", JsonPrimitive("boot"))
                        put("image", JsonPrimitive("/sdcard/evil.img"))
                    },
                ),
                caller,
            ),
        )
        assertEquals(ErrorCategory.BAD_PARAMETERS, r.error?.category)
        assertTrue(backend.flashes.isEmpty())
    }

    @Test
    fun extraParametersAreRejected() = runTest {
        val r = parse(
            exec().first.handle(
                req(BridgeOp.CAPABILITIES, buildJsonObject { put("raise_tier", JsonPrimitive("destructive")) }),
                caller,
            ),
        )
        assertEquals(ErrorCategory.BAD_PARAMETERS, r.error?.category)
        assertTrue(r.error!!.message.contains("raise_tier"))
    }

    @Test
    fun moduleBuildCannotSmuggleFilesOrWebroot() = runTest {
        val (ex, _, backend) = exec()
        val r = parse(
            ex.handle(
                req(
                    BridgeOp.MODULE_BUILD,
                    buildJsonObject {
                        put("id", JsonPrimitive("demo"))
                        put("name", JsonPrimitive("Demo"))
                        put("files", JsonPrimitive("no"))
                    },
                ),
                caller,
            ),
        )
        assertEquals(ErrorCategory.BAD_PARAMETERS, r.error?.category)
        assertTrue(backend.modules.isEmpty())
    }

    @Test
    fun missingShellCommandIsBadParameters() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val (ex, _, _) = exec(approvals = approvals)
        val r = approveNext(approvals, ex, req(BridgeOp.ADB_SHELL))
        assertEquals(ErrorCategory.BAD_PARAMETERS, r.error?.category)
    }

    @Test
    fun slotUnlockAndLockReturnTheDocumentedShape() = runTest {
        val (ex, _, backend) = exec()
        val slot = parse(ex.handle(req(BridgeOp.FASTBOOT_SLOT, buildJsonObject { put("slot", JsonPrimitive("b")) }), caller))
        assertTrue(slot.ok)
        assertEquals("b", slot.result!!.getValue("active_slot").jsonPrimitive.content)
        assertEquals(listOf("b"), backend.slots)

        val unlock = parse(ex.handle(req(BridgeOp.FASTBOOT_UNLOCK), caller))
        assertTrue(unlock.ok)
        assertEquals("unlock", unlock.result!!.getValue("requested").jsonPrimitive.content)

        val lock = parse(ex.handle(req(BridgeOp.FASTBOOT_LOCK), caller))
        assertTrue(lock.ok)
        assertEquals("lock", lock.result!!.getValue("requested").jsonPrimitive.content)
    }

    @Test
    fun aFailedTypedConfirmationIsDeniedByUser() = runTest {
        val backend = RecordingBackend().also {
            it.flashResult = Result.failure(IllegalStateException("Flash was not confirmed; nothing was written."))
        }
        val r = parse(
            exec(backend = backend).first.handle(
                req(
                    BridgeOp.FASTBOOT_FLASH,
                    buildJsonObject {
                        put("partition", JsonPrimitive("boot"))
                        put("image", JsonPrimitive("patched.img"))
                    },
                ),
                caller,
            ),
        )
        assertEquals(ErrorCategory.DENIED, r.error?.category)
    }

    @Test
    fun anUnknownOperationIsRejected() = runTest {
        for (op in listOf("rootforge.secret.root", "rootforge.edl.upload", "rootforge.adb.sideload", "rootforge.fastboot.oem")) {
            val r = parse(exec().first.handle(req(op), caller))
            assertEquals(op, ErrorCategory.UNKNOWN_OPERATION, r.error?.category)
        }
    }

    @Test
    fun garbageIsAMalformedRequest() = runTest {
        val r = parse(exec().first.handle("not-json", caller))
        assertEquals(ErrorCategory.MALFORMED, r.error?.category)
        assertNull(r.requestId)
    }

    @Test
    fun anOversizedRequestIsMalformed() = runTest {
        val r = parse(exec().first.handle("x".repeat(65 * 1024), caller))
        assertEquals(ErrorCategory.MALFORMED, r.error?.category)
    }

    @Test
    fun aReadThatExceedsTimeoutMsIsTimeout() = runTest {
        val backend = RecordingBackend().also { it.infoDelayMs = 1_500 }
        val r = parse(exec(backend = backend).first.handle(req(BridgeOp.DEVICE_INFO, timeoutMs = 1_000), caller))
        assertEquals(ErrorCategory.TIMEOUT, r.error?.category)
    }

    @Test
    fun messengerContractConstantsMatchTheDoc() {
        assertEquals(1, BRIDGE_PROTOCOL_MAJOR)
        assertEquals(1, BRIDGE_PROTOCOL_MINOR)
        assertEquals(1, BRIDGE_MSG_REQUEST)
        assertEquals(2, BRIDGE_MSG_RESPONSE)
        assertEquals("request", BRIDGE_KEY_REQUEST)
        assertEquals("response", BRIDGE_KEY_RESPONSE)
        assertEquals("ai.rootforge.os.action.BRIDGE", BRIDGE_ACTION)
        assertEquals("ai.rootforge.os", BRIDGE_PACKAGE)
        assertEquals("ai.rootforge.os.debug", BRIDGE_PACKAGE_DEBUG)
    }
}

class RecordingBackend : BridgeBackend {
    override val appVersion = "0.1.0-test"
    override val nodeKind = "android-host-app"
    override val usbHost = true
    val shells = mutableListOf<String>()
    val reboots = mutableListOf<String>()
    val fbReboots = mutableListOf<String>()
    val flashes = mutableListOf<Pair<String, String>>()
    val slots = mutableListOf<String>()
    val modules = mutableListOf<ModuleSpec>()
    var info: Result<Map<String, String>> = Result.success(mapOf("Model" to "Pixel Test"))
    var infoDelayMs: Long = 0
    var flashResult: Result<Unit> = Result.success(Unit)

    override fun devices() = listOf(BridgeDevice("ABC", "adb", "device", true, "test"))
    override suspend fun deviceInfo(): Result<Map<String, String>> {
        if (infoDelayMs > 0) delay(infoDelayMs)
        return info
    }
    override suspend fun adbShell(command: String): Result<Pair<String, Int?>> {
        shells += command
        return Result.success("uid=0(root)" to 0)
    }
    override suspend fun adbReboot(target: String) = Result.success(Unit).also { reboots += target }
    override suspend fun fastbootReboot(target: String) = Result.success(Unit).also { fbReboots += target }
    override suspend fun moduleBuild(spec: ModuleSpec) = Result.success("x.zip" to "abc").also { modules += spec }
    override suspend fun fastbootFlash(partition: String, imageName: String, source: String): Result<Unit> {
        flashes += partition to imageName
        return flashResult
    }
    override suspend fun fastbootUnlock(source: String) = Result.success(Unit)
    override suspend fun fastbootLock(source: String) = Result.success(Unit)
    override suspend fun fastbootSetSlot(slot: String, source: String) = Result.success(Unit).also { slots += slot }
}
