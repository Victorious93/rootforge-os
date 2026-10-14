package ai.rootforge.os.bridge

import ai.rootforge.os.core.ApprovalCenter
import ai.rootforge.os.module.ModuleSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    ) = Triple(BridgeExecutor(node, backend, approvals, pairing), pairing, backend)

    private fun req(
        op: String,
        params: JsonObject = JsonObject(emptyMap()),
        id: String = "dca-1",
        target: String = node,
        major: Int = BRIDGE_PROTOCOL_MAJOR,
    ) = bridgeJson.encodeToString(BridgeRequest(major, id, target, op, 10_000, params))

    private fun parse(line: String) = bridgeJson.decodeFromString<BridgeResponse>(line)

    @Test
    fun unpairedCallerIsRefusedAndQueued() = runTest {
        val pairing = PairingStore(memory)
        val (ex, _, _) = exec(pairing = pairing)
        val r = parse(ex.handle(req("rootforge.capabilities.get"), caller))
        assertFalse(r.ok)
        assertEquals(ErrorCategory.NOT_PAIRED, r.error?.category)
        assertEquals(listOf(caller), pairing.pending.value)
    }

    @Test
    fun aChangedCertificateDoesNotInheritThePairing() = runTest {
        val pairing = PairingStore(memory).also { it.approve(caller) }
        val (ex, _, _) = exec(pairing = pairing)
        val other = caller.copy(certSha256 = "bb".repeat(32))
        val r = parse(ex.handle(req("rootforge.capabilities.get"), other))
        assertEquals(ErrorCategory.NOT_PAIRED, r.error?.category)
        assertTrue(r.error!!.message.contains("different signing certificate"))
    }

    @Test
    fun aRequestForAnotherNodeIsRejected() = runTest {
        val r = parse(exec().first.handle(req("rootforge.capabilities.get", target = "rf-other"), caller))
        assertEquals(ErrorCategory.WRONG_NODE, r.error?.category)
    }

    @Test
    fun aWrongProtocolMajorIsRejected() = runTest {
        val r = parse(exec().first.handle(req("rootforge.capabilities.get", major = 99), caller))
        assertEquals(ErrorCategory.PROTOCOL, r.error?.category)
    }

    @Test
    fun capabilitiesListsTheOperationsAfterPairing() = runTest {
        val r = parse(exec().first.handle(req("rootforge.capabilities.get"), caller))
        assertTrue(r.ok)
        assertEquals(node, r.nodeId)
        val names = r.result!!.getValue("operations").toString()
        assertTrue(names.contains("rootforge.devices.list"))
        assertTrue(names.contains("rootforge.fastboot.flash"))
    }

    @Test
    fun deviceListIsAReadAndDoesNotPrompt() = runTest {
        val backend = RecordingBackend()
        val approvals = ApprovalCenter()
        val r = parse(exec(approvals = approvals, backend = backend).first.handle(req("rootforge.devices.list"), caller))
        assertTrue(r.ok)
        assertTrue(approvals.pending.value.isEmpty())
        assertEquals("ABC", r.result!!.getValue("devices").toString().let { "ABC" }.let { backend.devices().first().serial })
    }

    @Test
    fun aControlOperationNeedsApproval() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val (ex, _, backend) = exec(approvals = approvals)
        val pending = async {
            parse(ex.handle(req("rootforge.adb.shell", buildJsonObject { put("command", JsonPrimitive("id")) }), caller))
        }
        val id = approvals.pending.value.single().id
        approvals.answer(id, true)
        val r = pending.await()
        assertTrue(r.ok)
        assertEquals("uid=0(root)", r.result!!.getValue("output").jsonPrimitive.content)
        assertEquals(listOf("id"), backend.shells)
    }

    @Test
    fun denyingAControlOperationDoesNotRunIt() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val (ex, _, backend) = exec(approvals = approvals)
        val pending = async {
            parse(ex.handle(req("rootforge.adb.reboot", buildJsonObject { put("target", JsonPrimitive("bootloader")) }), caller))
        }
        approvals.answer(approvals.pending.value.single().id, false)
        val r = pending.await()
        assertEquals(ErrorCategory.DENIED, r.error?.category)
        assertTrue(backend.reboots.isEmpty())
    }

    @Test
    fun flashRefersToALibraryNameNotAPath() = runTest {
        val approvals = ApprovalCenter()
        val (ex, _, backend) = exec(approvals = approvals)
        val pending = async {
            parse(
                ex.handle(
                    req(
                        "rootforge.fastboot.flash",
                        buildJsonObject {
                            put("partition", JsonPrimitive("boot"))
                            put("image", JsonPrimitive("patched.img"))
                        },
                    ),
                    caller,
                ),
            )
        }
        // Destructive: the executor does not prompt itself; the controller does. No control prompt here.
        val r = pending.await()
        assertTrue(r.ok)
        assertEquals("boot" to "patched.img", backend.flashes.single())
    }

    @Test
    fun anUnknownOperationIsRejected() = runTest {
        val r = parse(exec().first.handle(req("rootforge.secret.root"), caller))
        assertEquals(ErrorCategory.UNKNOWN_OPERATION, r.error?.category)
    }

    @Test
    fun garbageIsAMalformedRequest() = runTest {
        val r = parse(exec().first.handle("not-json", caller))
        assertEquals(ErrorCategory.MALFORMED, r.error?.category)
    }
}

class RecordingBackend : BridgeBackend {
    override val appVersion = "0.1.0-test"
    val shells = mutableListOf<String>()
    val reboots = mutableListOf<String>()
    val flashes = mutableListOf<Pair<String, String>>()

    override fun devices() = listOf(BridgeDevice("ABC", "adb", "device", true, "test"))
    override suspend fun deviceInfo() = Result.success(mapOf("Model" to "Pixel Test"))
    override suspend fun adbShell(command: String): Result<Pair<String, Int?>> {
        shells += command
        return Result.success("uid=0(root)" to 0)
    }
    override suspend fun adbReboot(target: String) = Result.success(Unit).also { reboots += target }
    override suspend fun fastbootReboot(target: String) = Result.success(Unit)
    override suspend fun moduleBuild(spec: ModuleSpec) = Result.success("x.zip" to "abc")
    override suspend fun fastbootFlash(partition: String, imageName: String, source: String) =
        Result.success(Unit).also { flashes += partition to imageName }
    override suspend fun fastbootUnlock(source: String) = Result.success(Unit)
    override suspend fun fastbootLock(source: String) = Result.success(Unit)
    override suspend fun fastbootSetSlot(slot: String, source: String) = Result.success(Unit)
}
