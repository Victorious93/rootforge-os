package ai.rootforge.os.bridge

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetEnvelopeTest {
    @Test
    fun validCapabilitiesRequestParsesWithDefaults() {
        val parsed = parseFleetRequest(fleetJson(op = FleetOp.CAPABILITIES, nonce = nonce(1)))
        val req = (parsed as FleetParseResult.Ok).request
        assertEquals(FleetOp.CAPABILITIES, req.op)
        assertEquals("dca-1", req.requestId)
        assertEquals(NODE, req.targetNodeRef)
        assertEquals(nonce(1), req.nonce)
        assertEquals(FLEET_DEFAULT_TTL_MS, req.ttlMs)
        assertNull(req.trigger)
        assertNull(req.destructive)
        assertTrue(req.params.isEmpty())
    }

    @Test
    fun ttlMsIsAcceptedUpToTheMaxAndRejectedAbove() {
        val ok = parseFleetRequest(fleetJson(ttlMs = FLEET_MAX_TTL_MS, nonce = nonce(2)))
        assertTrue(ok is FleetParseResult.Ok)
        assertEquals(FLEET_MAX_TTL_MS, (ok as FleetParseResult.Ok).request.ttlMs)

        val bad = parseFleetRequest(fleetJson(ttlMs = FLEET_MAX_TTL_MS + 1, nonce = nonce(3))) as FleetParseResult.Err
        assertEquals(FleetError.INVALID_PARAMETERS, bad.code)
        assertTrue(bad.message.contains("ttlMs"))
    }

    @Test
    fun nonceMustBe128BitHex() {
        val short = parseFleetRequest(fleetJson(nonce = "abcd")) as FleetParseResult.Err
        assertEquals(FleetError.INVALID_PARAMETERS, short.code)
        val ok = parseFleetRequest(fleetJson(nonce = "AB".repeat(16)))
        assertEquals("ab".repeat(16), (ok as FleetParseResult.Ok).request.nonce)
        assertEquals(32, ok.request.nonce.length)
    }

    @Test
    fun unknownEnvelopeFieldIsInvalidParameters() {
        val raw = fleetJson(nonce = nonce(4)).dropLast(1) + """, "role": "admin"}"""
        val err = parseFleetRequest(raw) as FleetParseResult.Err
        assertEquals(FleetError.INVALID_PARAMETERS, err.code)
        assertTrue(err.message.contains("role"))
    }

    @Test
    fun triggerAndSourceNodeRefAreAcceptedAndIgnored() {
        val parsed = parseFleetRequest(
            fleetJson(
                nonce = nonce(5),
                extra = buildJsonObject {
                    put("trigger", buildJsonObject {
                        put("authorized", JsonPrimitive(true))
                        put("user", JsonPrimitive("root"))
                    })
                    put("sourceNodeRef", JsonPrimitive("spoofed-node"))
                },
            ),
        )
        val req = (parsed as FleetParseResult.Ok).request
        assertEquals(true, req.trigger!!["authorized"]?.let { (it as JsonPrimitive).content.toBoolean() })
        assertNull(req.actionRef)
    }

    @Test
    fun remoteCommandRequiresActionRef() {
        val err = parseFleetRequest(fleetJson(op = FleetOp.REMOTE_COMMAND, nonce = nonce(6))) as FleetParseResult.Err
        assertEquals(FleetError.INVALID_PARAMETERS, err.code)
        assertTrue(err.message.contains("actionRef"))
    }

    @Test
    fun macroStepRequiresMacroFields() {
        val err = parseFleetRequest(
            fleetJson(op = FleetOp.MACRO_STEP, nonce = nonce(7), extra = buildJsonObject {
                put("actionRef", JsonPrimitive(BridgeOp.DEVICES_LIST))
            }),
        ) as FleetParseResult.Err
        assertEquals(FleetError.INVALID_PARAMETERS, err.code)
        assertTrue(err.message.contains("macroId"))
    }

    @Test
    fun macroRunParsesStepsAndDryRun() {
        val parsed = parseFleetRequest(
            fleetJson(
                op = FleetOp.MACRO_RUN,
                nonce = nonce(8),
                extra = buildJsonObject {
                    put("macroId", JsonPrimitive("m1"))
                    put("dryRun", JsonPrimitive(true))
                    put("steps", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject {
                            put("actionRef", JsonPrimitive(BridgeOp.DEVICES_LIST))
                            put("onError", JsonPrimitive("continue"))
                        })
                    })
                },
            ),
        )
        val req = (parsed as FleetParseResult.Ok).request
        assertTrue(req.dryRun)
        assertEquals("m1", req.macroId)
        assertEquals(1, req.steps.size)
        assertEquals(FleetOnError.CONTINUE, req.steps[0].onError)
    }

    @Test
    fun nodeStatusGetParsesWithoutActionRef() {
        val parsed = parseFleetRequest(fleetJson(op = FleetOp.NODE_STATUS, nonce = nonce(90)))
        val req = (parsed as FleetParseResult.Ok).request
        assertEquals(FleetOp.NODE_STATUS, req.op)
        assertNull(req.actionRef)
        assertTrue(req.params.isEmpty())
    }

    @Test
    fun unknownOpIsUnsupported() {
        val err = parseFleetRequest(fleetJson(op = "root.secret", nonce = nonce(9))) as FleetParseResult.Err
        assertEquals(FleetError.UNSUPPORTED_OP, err.code)
    }

    @Test
    fun garbageIsInvalidParameters() {
        val err = parseFleetRequest("not-json") as FleetParseResult.Err
        assertEquals(FleetError.INVALID_PARAMETERS, err.code)
        assertNull(err.requestId)
    }

    @Test
    fun oversizedRequestIsInvalidParameters() {
        val err = parseFleetRequest("x".repeat(65 * 1024)) as FleetParseResult.Err
        assertEquals(FleetError.INVALID_PARAMETERS, err.code)
    }

    @Test
    fun wrongProtocolMajorIsUnsupported() {
        val err = parseFleetRequest(
            fleetJson(nonce = nonce(10), extra = buildJsonObject { put("protocolMajor", JsonPrimitive(99)) }),
        ) as FleetParseResult.Err
        assertEquals(FleetError.UNSUPPORTED_OP, err.code)
    }
}
