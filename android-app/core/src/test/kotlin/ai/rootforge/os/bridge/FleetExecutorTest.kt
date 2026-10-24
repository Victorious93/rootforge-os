package ai.rootforge.os.bridge

import ai.rootforge.os.config.ConfigBackup
import ai.rootforge.os.config.ConfigPreferenceId
import ai.rootforge.os.config.MemoryNodeConfigStore
import ai.rootforge.os.core.ApprovalCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FleetExecutorTest {
    private suspend fun CoroutineScope.approveNext(
        approvals: ApprovalCenter,
        ex: FleetExecutor,
        request: String,
        ok: Boolean = true,
        typed: String = "",
    ): FleetResponse {
        val pending = async { parseFleet(ex.handle(request, CALLER)) }
        val req = approvals.pending.value.single()
        approvals.answer(req.id, ok, typed)
        return pending.await()
    }

    @Test
    fun devicesListIsAReadAndDoesNotPrompt() = runTest {
        val approvals = ApprovalCenter()
        val r = parseFleet(
            fleetExec(approvals = approvals).handle(
                fleetJson(
                    op = FleetOp.REMOTE_COMMAND,
                    nonce = nonce(1),
                    extra = buildJsonObject { put("actionRef", JsonPrimitive(BridgeOp.DEVICES_LIST)) },
                ),
                CALLER,
            ),
        )
        assertTrue(r.ok)
        assertTrue(approvals.pending.value.isEmpty())
        assertEquals("ABC", r.result!!.getValue("devices").jsonArray[0].jsonObject.getValue("serial").jsonPrimitive.content)
    }

    @Test
    fun aControlActionNeedsApproval() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val backend = RecordingBackend()
        val ex = fleetExec(approvals = approvals, backend = backend)
        val r = approveNext(
            approvals,
            ex,
            fleetJson(
                op = FleetOp.REMOTE_COMMAND,
                nonce = nonce(2),
                extra = buildJsonObject { put("actionRef", JsonPrimitive(BridgeOp.ADB_SHELL)) },
                params = buildJsonObject { put("command", JsonPrimitive("id")) },
            ),
        )
        assertTrue(r.ok)
        assertEquals("uid=0(root)", r.result!!.getValue("output").jsonPrimitive.content)
        assertEquals(listOf("id"), backend.shells)
    }

    @Test
    fun denyingAControlActionIsConfirmationRequired() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val backend = RecordingBackend()
        val ex = fleetExec(approvals = approvals, backend = backend)
        val r = approveNext(
            approvals,
            ex,
            fleetJson(
                op = FleetOp.REMOTE_COMMAND,
                nonce = nonce(3),
                extra = buildJsonObject { put("actionRef", JsonPrimitive(BridgeOp.ADB_REBOOT)) },
                params = buildJsonObject { put("target", JsonPrimitive("bootloader")) },
            ),
            ok = false,
        )
        assertEquals(FleetError.CONFIRMATION_REQUIRED, r.error?.code)
        assertTrue(backend.reboots.isEmpty())
    }

    /** What the person approves must be everything that runs: a 2000-character command is allowed, so show all of it. */
    @Test
    fun theApprovalPromptShowsTheWholeShellCommand() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val backend = RecordingBackend()
        val ex = fleetExec(approvals = approvals, backend = backend)
        val command = "echo ok;" + " ".repeat(600) + "touch /sdcard/TAIL_MARKER"
        val pending = async {
            parseFleet(
                ex.handle(
                    fleetJson(
                        op = FleetOp.REMOTE_COMMAND,
                        nonce = nonce(40),
                        extra = buildJsonObject { put("actionRef", JsonPrimitive(BridgeOp.ADB_SHELL)) },
                        params = buildJsonObject { put("command", JsonPrimitive(command)) },
                    ),
                    CALLER,
                ),
            )
        }
        val request = approvals.pending.value.single()
        assertTrue(
            "the prompt must contain the end of the command",
            request.lines.any { it.contains("TAIL_MARKER") },
        )
        approvals.answer(request.id, false)
        assertEquals(FleetError.CONFIRMATION_REQUIRED, pending.await().error?.code)
        assertTrue(backend.shells.isEmpty())
    }

    @Test
    fun aParameterTooLongToShowInFullIsRefusedBeforeAnyPrompt() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val backend = RecordingBackend()
        val ex = fleetExec(approvals = approvals, backend = backend)
        val r = parseFleet(
            ex.handle(
                fleetJson(
                    op = FleetOp.REMOTE_COMMAND,
                    nonce = nonce(41),
                    extra = buildJsonObject { put("actionRef", JsonPrimitive(BridgeOp.ADB_SHELL)) },
                    params = buildJsonObject { put("command", JsonPrimitive("x".repeat(30_000))) },
                ),
                CALLER,
            ),
        )
        assertEquals(FleetError.INVALID_PARAMETERS, r.error?.code)
        assertTrue("nobody should be asked to approve a command they cannot read", approvals.pending.value.isEmpty())
        assertTrue(backend.shells.isEmpty())
    }

    @Test
    fun aNestedParameterIsRefusedBeforeAnyPrompt() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val backend = RecordingBackend()
        val r = parseFleet(
            fleetExec(approvals = approvals, backend = backend).handle(
                fleetJson(
                    op = FleetOp.REMOTE_COMMAND,
                    nonce = nonce(42),
                    extra = buildJsonObject { put("actionRef", JsonPrimitive(BridgeOp.ADB_SHELL)) },
                    params = buildJsonObject { put("command", nestedArray(20)) },
                ),
                CALLER,
            ),
        )
        assertEquals(FleetError.INVALID_PARAMETERS, r.error?.code)
        assertTrue(approvals.pending.value.isEmpty())
        assertTrue(backend.shells.isEmpty())
    }

    @Test
    fun destructiveFalseDoesNotSkipAFlashConfirmFailure() = runTest {
        val backend = RecordingBackend().also {
            it.flashResult = Result.failure(IllegalStateException("Flash was not confirmed; nothing was written."))
        }
        val r = parseFleet(
            fleetExec(backend = backend).handle(
                fleetJson(
                    op = FleetOp.REMOTE_COMMAND,
                    nonce = nonce(4),
                    extra = buildJsonObject {
                        put("actionRef", JsonPrimitive(BridgeOp.FASTBOOT_FLASH))
                        put("destructive", JsonPrimitive(false))
                    },
                    params = buildJsonObject {
                        put("partition", JsonPrimitive("boot"))
                        put("image", JsonPrimitive("patched.img"))
                    },
                ),
                CALLER,
            ),
        )
        assertEquals(FleetError.CONFIRMATION_REQUIRED, r.error?.code)
    }

    @Test
    fun pmGrantNeedsTypedConfirmAndDoesNotRunWithoutIt() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val shell = FakePrivilegedShell()
        val ex = fleetExec(approvals = approvals, privileged = shell)
        val pending = async {
            parseFleet(
                ex.handle(
                    fleetJson(
                        op = FleetOp.REMOTE_COMMAND,
                        nonce = nonce(5),
                        extra = buildJsonObject { put("actionRef", JsonPrimitive(RemoteActionCatalog.ACTION_SHIZUKU_PM_GRANT)) },
                        params = buildJsonObject {
                            put("package", JsonPrimitive("ai.example.app"))
                            put("permission", JsonPrimitive("android.permission.INTERNET"))
                        },
                    ),
                    CALLER,
                ),
            )
        }
        val req = approvals.pending.value.single()
        assertEquals("GRANT PERMISSION", req.phrase)
        assertFalse(approvals.answer(req.id, true, "nope"))
        approvals.answer(req.id, false)
        val r = pending.await()
        assertEquals(FleetError.CONFIRMATION_REQUIRED, r.error?.code)
        assertTrue(shell.grants.isEmpty())
    }

    @Test
    fun pmGrantRunsAfterTypedGrantPermission() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val shell = FakePrivilegedShell()
        val ex = fleetExec(approvals = approvals, privileged = shell)
        val r = approveNext(
            approvals,
            ex,
            fleetJson(
                op = FleetOp.REMOTE_COMMAND,
                nonce = nonce(6),
                extra = buildJsonObject { put("actionRef", JsonPrimitive(RemoteActionCatalog.ACTION_SHIZUKU_PM_GRANT)) },
                params = buildJsonObject {
                    put("package", JsonPrimitive("ai.example.app"))
                    put("permission", JsonPrimitive("android.permission.INTERNET"))
                },
            ),
            ok = true,
            typed = "GRANT PERMISSION",
        )
        assertTrue(r.ok)
        assertEquals("ai.example.app" to "android.permission.INTERNET", shell.grants.single())
    }

    @Test
    fun recoveryWriteReturnsConfirmationRequiredAndDoesNotNeedABackend() = runTest {
        val names = listOf(
            "rootforge.recovery.flash",
            "rootforge.recovery.install",
            "rootforge.recovery.custom",
            "rootforge.customrecovery.install",
        )
        names.forEachIndexed { i, name ->
            val backend = RecordingBackend()
            val r = parseFleet(
                fleetExec(backend = backend).handle(
                    fleetJson(
                        op = FleetOp.REMOTE_COMMAND,
                        nonce = nonce(70 + i),
                        extra = buildJsonObject { put("actionRef", JsonPrimitive(name)) },
                    ),
                    CALLER,
                ),
            )
            assertEquals(name, FleetError.CONFIRMATION_REQUIRED, r.error?.code)
            assertTrue(backend.flashes.isEmpty())
        }
    }

    @Test
    fun shizukuExecIsCapabilityOff() = runTest {
        val r = parseFleet(
            fleetExec().handle(
                fleetJson(
                    op = FleetOp.REMOTE_COMMAND,
                    nonce = nonce(8),
                    extra = buildJsonObject { put("actionRef", JsonPrimitive(RemoteActionCatalog.ACTION_SHIZUKU_EXEC)) },
                ),
                CALLER,
            ),
        )
        assertEquals(FleetError.CAPABILITY_OFF, r.error?.code)
    }

    @Test
    fun unknownActionIsUnsupportedOp() = runTest {
        val r = parseFleet(
            fleetExec().handle(
                fleetJson(
                    op = FleetOp.REMOTE_COMMAND,
                    nonce = nonce(9),
                    extra = buildJsonObject { put("actionRef", JsonPrimitive("rootforge.secret.root")) },
                ),
                CALLER,
            ),
        )
        assertEquals(FleetError.UNSUPPORTED_OP, r.error?.code)
    }

    @Test
    fun wrongNodeIsNotFound() = runTest {
        val r = parseFleet(fleetExec().handle(fleetJson(nonce = nonce(10), target = "rf-other"), CALLER))
        assertEquals(FleetError.NOT_FOUND, r.error?.code)
    }

    @Test
    fun macroRunDryRunDoesNotTouchTheBackend() = runTest {
        val backend = RecordingBackend()
        val r = parseFleet(
            fleetExec(backend = backend).handle(
                fleetJson(
                    op = FleetOp.MACRO_RUN,
                    nonce = nonce(11),
                    extra = buildJsonObject {
                        put("macroId", JsonPrimitive("m1"))
                        put("dryRun", JsonPrimitive(true))
                        put("steps", buildJsonArray {
                            add(buildJsonObject { put("actionRef", JsonPrimitive(BridgeOp.DEVICES_LIST)) })
                            add(buildJsonObject { put("actionRef", JsonPrimitive(BridgeOp.FASTBOOT_FLASH)) })
                        })
                    },
                ),
                CALLER,
            ),
        )
        assertTrue(r.ok)
        assertTrue(r.result!!.getValue("dryRun").jsonPrimitive.content.toBoolean())
        assertTrue(backend.flashes.isEmpty())
        val steps = r.result!!.getValue("steps").jsonArray
        assertEquals(FleetKind.READ, steps[0].jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(FleetKind.DESTRUCTIVE, steps[1].jsonObject.getValue("kind").jsonPrimitive.content)
        assertTrue(steps[1].jsonObject.getValue("requiresConfirm").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun macroRunAbortStopsAfterAFailedStep() = runTest(UnconfinedTestDispatcher()) {
        val approvals = ApprovalCenter()
        val backend = RecordingBackend()
        val ex = fleetExec(approvals = approvals, backend = backend)
        val pending = async {
            parseFleet(
                ex.handle(
                    fleetJson(
                        op = FleetOp.MACRO_RUN,
                        nonce = nonce(12),
                        extra = buildJsonObject {
                            put("macroId", JsonPrimitive("m2"))
                            put("steps", buildJsonArray {
                                add(buildJsonObject {
                                    put("actionRef", JsonPrimitive(BridgeOp.ADB_SHELL))
                                    put("params", buildJsonObject { put("command", JsonPrimitive("id")) })
                                    put("onError", JsonPrimitive("abort"))
                                })
                                add(buildJsonObject { put("actionRef", JsonPrimitive(BridgeOp.DEVICES_LIST)) })
                            })
                        },
                    ),
                    CALLER,
                ),
            )
        }
        approvals.answer(approvals.pending.value.single().id, false)
        val r = pending.await()
        assertTrue(r.ok)
        val steps = r.result!!.getValue("steps").jsonArray
        assertEquals(1, steps.size)
        assertFalse(steps[0].jsonObject.getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals(FleetError.CONFIRMATION_REQUIRED, steps[0].jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertTrue(backend.shells.isEmpty())
    }

    @Test
    fun macroStepRunsOneAction() = runTest {
        val r = parseFleet(
            fleetExec().handle(
                fleetJson(
                    op = FleetOp.MACRO_STEP,
                    nonce = nonce(13),
                    extra = buildJsonObject {
                        put("actionRef", JsonPrimitive(BridgeOp.DEVICES_LIST))
                        put("macroId", JsonPrimitive("m3"))
                        put("stepIndex", JsonPrimitive(0))
                        put("onError", JsonPrimitive("abort"))
                    },
                ),
                CALLER,
            ),
        )
        assertTrue(r.ok)
        assertEquals(1, r.result!!.getValue("devices").jsonArray.size)
    }

    @Test
    fun ingressRoutesFleetAndLegacyEnvelopes() = runTest {
        val pairing = PairingStore(memoryStore()).also { it.approve(CALLER) }
        val backend = RecordingBackend()
        val approvals = ApprovalCenter()
        val ingress = BridgeIngress(
            BridgeExecutor(NODE, backend, approvals, pairing, { true }),
            FleetExecutor(NODE, backend, approvals, pairing, { true }, replay = ReplayGuard(clock = { NOW })),
        )
        val fleet = parseFleet(ingress.handle(fleetJson(nonce = nonce(14), now = NOW), CALLER))
        assertTrue(fleet.ok)
        assertEquals(1, fleet.protocolMinor)

        val legacyLine = bridgeJson.encodeToString(
            BridgeRequest.serializer(),
            BridgeRequest(BRIDGE_PROTOCOL_MAJOR, "dca-1", NODE, BridgeOp.DEVICES_LIST),
        )
        val legacy = bridgeJson.decodeFromString<BridgeResponse>(ingress.handle(legacyLine, CALLER))
        assertTrue(legacy.ok)
        assertEquals("ABC", legacy.result!!.getValue("devices").jsonArray[0].jsonObject.getValue("serial").jsonPrimitive.content)
    }

    @Test
    fun extraActionParametersAreRejected() = runTest {
        val r = parseFleet(
            fleetExec().handle(
                fleetJson(
                    op = FleetOp.REMOTE_COMMAND,
                    nonce = nonce(15),
                    extra = buildJsonObject { put("actionRef", JsonPrimitive(BridgeOp.DEVICES_LIST)) },
                    params = buildJsonObject { put("raise_tier", JsonPrimitive("destructive")) },
                ),
                CALLER,
            ),
        )
        assertEquals(FleetError.INVALID_PARAMETERS, r.error?.code)
    }

    @Test
    fun remoteCallerIsRefusedForConfigExportAndImport() = runTest {
        val store = MemoryNodeConfigStore().also {
            it.setPreference(ConfigPreferenceId.AUTO_CONNECT, "true")
            it.setPreference(ConfigPreferenceId.TERMINAL_APP, "com.termux")
        }
        val config = ConfigBackup(store, ApprovalCenter())
        val export = parseFleet(
            fleetExec(configBackup = config).handle(
                fleetJson(op = FleetOp.CONFIG_EXPORT, nonce = nonce(16)),
                CALLER,
            ),
        )
        assertEquals(FleetError.CAPABILITY_OFF, export.error?.code)
        assertEquals("true", store.getPreference(ConfigPreferenceId.AUTO_CONNECT))

        val importOp = parseFleet(
            fleetExec(configBackup = config).handle(
                fleetJson(
                    op = FleetOp.CONFIG_IMPORT,
                    nonce = nonce(17),
                    extra = buildJsonObject { put("dryRun", JsonPrimitive(true)) },
                    params = buildJsonObject { put("bundle", JsonPrimitive("{}")) },
                ),
                CALLER,
            ),
        )
        assertEquals(FleetError.CAPABILITY_OFF, importOp.error?.code)
        assertEquals("true", store.getPreference(ConfigPreferenceId.AUTO_CONNECT))
        assertEquals("com.termux", store.getPreference(ConfigPreferenceId.TERMINAL_APP))
        assertFalse(store.externalControlEnabled)
    }
}
