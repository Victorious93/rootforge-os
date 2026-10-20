package ai.rootforge.os.bridge

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NodeStatusSnapshotTest {
    private val sample = NodeStatusBuilder.build(
        nodeId = NODE,
        nowMs = NOW,
        packageName = BRIDGE_PACKAGE_DEBUG,
        root = RootPulse(suAvailable = true, managerLabel = "Magisk"),
    )

    @Test
    fun snapshotShapeIsTheFrozenWireObject() {
        val json = NodeStatusSnapshotCodec.encode(sample)
        assertEquals(NODE, json.getValue("nodeId").jsonPrimitive.content)
        assertEquals(BRIDGE_PACKAGE_DEBUG, json.getValue("packageName").jsonPrimitive.content)
        assertEquals(FleetConnectionClass.EXTERNAL_NODE, json.getValue("connectionClass").jsonPrimitive.content)
        assertEquals(NOW, json.getValue("updatedAtMs").jsonPrimitive.content.toLong())
        val domains = json.getValue("domains").jsonArray.map { it.jsonObject }
        assertEquals(2, domains.size)
        assertEquals("node_health", domains[0].getValue("name").jsonPrimitive.content)
        assertEquals("ok", domains[0].getValue("state").jsonPrimitive.content)
        assertEquals(NodeStatusBuilder.HEALTH_DETAIL, domains[0].getValue("detail").jsonPrimitive.content)
        assertEquals("other", domains[1].getValue("name").jsonPrimitive.content)
        assertEquals("ok", domains[1].getValue("state").jsonPrimitive.content)
        assertEquals("su available; Magisk detected", domains[1].getValue("detail").jsonPrimitive.content)
        assertFalse(json.containsKey("capability"))
        assertFalse(json.containsKey("actionRef"))
        assertFalse(json.containsKey("payload"))
        assertFalse(json.containsKey("sourceNodeRef"))

        val roundTrip = NodeStatusSnapshotCodec.decode(json)
        assertEquals(sample.nodeId, roundTrip.nodeId)
        assertEquals(sample.packageName, roundTrip.packageName)
        assertEquals(sample.connectionClass, roundTrip.connectionClass)
        assertEquals(sample.updatedAtMs, roundTrip.updatedAtMs)
        assertEquals(sample.domains.map { Triple(it.wireName(), it.state, it.detail) }, roundTrip.domains.map { Triple(it.wireName(), it.state, it.detail) })
    }

    @Test
    fun detailLongerThan200CharactersIsRefused() {
        val over = "x".repeat(StatusTrayDomainStatus.MAX_DETAIL_CHARS + 1)
        try {
            StatusTrayDomainStatus(StatusTrayDomain.NODE_HEALTH, StatusTrayState.OK, over)
            fail("expected detail length to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("200"))
        }
    }

    @Test
    fun encodeRefusesSecretsInDetail() {
        val dirty = sample.copy(
            domains = listOf(
                StatusTrayDomainStatus(StatusTrayDomain.NODE_HEALTH, StatusTrayState.OK, "codeWord=abc"),
            ),
        )
        try {
            NodeStatusSnapshotCodec.encode(dirty)
            fail("expected secret detail to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("codeword") || e.message!!.contains("codeWord"))
        }
    }

    @Test
    fun encodeRefusesSecretPayloadKeys() {
        val dirty = sample.copy(payload = mapOf("signingKey" to "nope"))
        try {
            NodeStatusSnapshotCodec.encode(dirty)
            fail("expected secret payload key to be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("signingKey"))
        }
    }

    @Test
    fun unknownDomainNameDecodesAsOther() {
        val raw = """
            {"nodeId":"n1","connectionClass":"TERMUX_IPC","updatedAtMs":9,
             "domains":[{"name":"battery","state":"ok","detail":"82%"}]}
        """.trimIndent()
        val decoded = NodeStatusSnapshotCodec.decode(raw)
        assertEquals(StatusTrayDomain.OTHER, decoded.domains.single().name)
        assertEquals("battery", decoded.domains.single().originalName)
        assertEquals("battery", decoded.domains.single().wireName())
        val again = NodeStatusSnapshotCodec.decode(NodeStatusSnapshotCodec.encode(decoded))
        assertEquals("battery", again.domains.single().wireName())
    }

    @Test
    fun defaultSourceOmitsRootDomain() {
        val snap = NodeStatusSource.Default.snapshot(NODE, NOW)
        assertEquals(listOf("node_health"), snap.domains.map { it.wireName() })
        assertNull(snap.packageName)
    }

    @Test
    fun probingSourceReportsSuAndManagerOnOther() {
        val source = ProbingNodeStatusSource(
            packageName = BRIDGE_PACKAGE_DEBUG,
            installedManagers = { listOf("com.topjohnwu.magisk") },
            suPresent = { true },
        )
        val snap = source.snapshot(NODE, NOW)
        assertEquals(BRIDGE_PACKAGE_DEBUG, snap.packageName)
        assertEquals(FleetConnectionClass.EXTERNAL_NODE, snap.connectionClass)
        val other = snap.domains.single { it.name == StatusTrayDomain.OTHER }
        assertEquals(StatusTrayState.OK, other.state)
        assertEquals("su available; Magisk detected", other.detail)
    }

    @Test
    fun probingSourceWithoutSuOrManagerIsOff() {
        val source = ProbingNodeStatusSource(
            packageName = BRIDGE_PACKAGE,
            installedManagers = { emptyList() },
            suPresent = { false },
        )
        val other = source.snapshot(NODE, NOW).domains.single { it.name == StatusTrayDomain.OTHER }
        assertEquals(StatusTrayState.OFF, other.state)
        assertEquals("su not available", other.detail)
    }

    @Test
    fun suPresenceLooksOnlyAtCandidatePaths() {
        val seen = mutableListOf<String>()
        val present = RootPulse.suBinaryPresent { path ->
            seen += path
            path == "/sbin/su"
        }
        assertTrue(present)
        assertTrue(seen.isNotEmpty())
        assertTrue(RootPulse.SU_PATHS.containsAll(seen) || seen.all { it in RootPulse.SU_PATHS })
        assertFalse(RootPulse.suBinaryPresent { false })
    }
}

class NodeStatusFleetOpTest {
    @Test
    fun nodeStatusGetReturnsTheSnapshotWithoutAPrompt() = runTest {
        val approvals = ai.rootforge.os.core.ApprovalCenter()
        val source = ProbingNodeStatusSource(
            packageName = BRIDGE_PACKAGE_DEBUG,
            installedManagers = { listOf("me.weishu.kernelsu") },
            suPresent = { true },
        )
        val r = parseFleet(
            fleetExec(approvals = approvals, statusSource = source).handle(
                fleetJson(op = FleetOp.NODE_STATUS, nonce = nonce(20), now = NOW),
                CALLER,
            ),
        )
        assertTrue(r.ok)
        assertTrue(approvals.pending.value.isEmpty())
        val result = r.result!!
        assertEquals(NODE, result.getValue("nodeId").jsonPrimitive.content)
        assertEquals(BRIDGE_PACKAGE_DEBUG, result.getValue("packageName").jsonPrimitive.content)
        assertEquals(FleetConnectionClass.EXTERNAL_NODE, result.getValue("connectionClass").jsonPrimitive.content)
        assertEquals(NOW, result.getValue("updatedAtMs").jsonPrimitive.content.toLong())
        val names = result.getValue("domains").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(listOf("node_health", "other"), names)
        val other = result.getValue("domains").jsonArray[1].jsonObject
        assertTrue(other.getValue("detail").jsonPrimitive.content.contains("KernelSU"))
        result.getValue("domains").jsonArray.forEach { row ->
            val detail = row.jsonObject["detail"]?.jsonPrimitive?.content
            if (detail != null) {
                assertTrue(detail.length <= StatusTrayDomainStatus.MAX_DETAIL_CHARS)
                StatusTrayPrivacy.FORBIDDEN_FRAGMENTS.forEach { fragment ->
                    assertFalse(detail.lowercase().contains(fragment))
                }
            }
        }
    }

    @Test
    fun wrongArgsNodeIdIsNotFound() = runTest {
        val r = parseFleet(
            fleetExec().handle(
                fleetJson(
                    op = FleetOp.NODE_STATUS,
                    nonce = nonce(21),
                    params = buildJsonObject { put("nodeId", JsonPrimitive("rf-other")) },
                ),
                CALLER,
            ),
        )
        assertEquals(FleetError.NOT_FOUND, r.error?.code)
    }

    @Test
    fun extraStatusParametersAreRejected() = runTest {
        val r = parseFleet(
            fleetExec().handle(
                fleetJson(
                    op = FleetOp.NODE_STATUS,
                    nonce = nonce(22),
                    params = buildJsonObject { put("raise_tier", JsonPrimitive("destructive")) },
                ),
                CALLER,
            ),
        )
        assertEquals(FleetError.INVALID_PARAMETERS, r.error?.code)
    }

    @Test
    fun secretDetailFromTheSourceIsInvalidParameters() = runTest {
        val source = NodeStatusSource { id, now ->
            NodeStatusSnapshot(
                nodeId = id,
                updatedAtMs = now,
                domains = listOf(
                    StatusTrayDomainStatus(StatusTrayDomain.NODE_HEALTH, StatusTrayState.OK, "api_token=leak"),
                ),
            )
        }
        val r = parseFleet(
            fleetExec(statusSource = source).handle(
                fleetJson(op = FleetOp.NODE_STATUS, nonce = nonce(23)),
                CALLER,
            ),
        )
        assertEquals(FleetError.INVALID_PARAMETERS, r.error?.code)
        assertTrue(r.error!!.message.contains("token"))
    }

    @Test
    fun unpairedCallerIsRefusedForNodeStatusGet() = runTest {
        val pairing = PairingStore(memoryStore())
        val r = parseFleet(
            fleetExec(pairing = pairing).handle(
                fleetJson(op = FleetOp.NODE_STATUS, nonce = nonce(24), now = NOW),
                CALLER,
            ),
        )
        assertEquals(false, r.ok)
        assertEquals(FleetError.NOT_PAIRED, r.error?.code)
        assertEquals(listOf(CALLER), pairing.pending.value)
    }

    @Test
    fun advisoryDestructiveDoesNotChangeTheRead() = runTest {
        val r = parseFleet(
            fleetExec().handle(
                fleetJson(
                    op = FleetOp.NODE_STATUS,
                    nonce = nonce(25),
                    extra = buildJsonObject { put("destructive", JsonPrimitive(true)) },
                ),
                CALLER,
            ),
        )
        assertTrue(r.ok)
        assertEquals("node_health", r.result!!.getValue("domains").jsonArray[0].jsonObject.getValue("name").jsonPrimitive.content)
    }
}
