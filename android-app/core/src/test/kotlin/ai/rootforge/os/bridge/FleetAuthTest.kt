package ai.rootforge.os.bridge

import ai.rootforge.os.core.ApprovalCenter
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetAuthTest {
    @Test
    fun unpairedCallerIsRefusedAndQueued() = runTest {
        val pairing = PairingStore(memoryStore())
        val ex = fleetExec(pairing = pairing)
        val r = parseFleet(ex.handle(fleetJson(nonce = nonce(1), now = NOW), CALLER))
        assertEquals(false, r.ok)
        assertEquals(FleetError.NOT_PAIRED, r.error?.code)
        assertEquals(listOf(CALLER), pairing.pending.value)
    }

    @Test
    fun aChangedCertificateDoesNotInheritThePairing() = runTest {
        val pairing = PairingStore(memoryStore()).also { it.approve(CALLER) }
        val ex = fleetExec(pairing = pairing)
        val other = CALLER.copy(certSha256 = "bb".repeat(32))
        val r = parseFleet(ex.handle(fleetJson(nonce = nonce(2), now = NOW), other))
        assertEquals(FleetError.NOT_PAIRED, r.error?.code)
        assertTrue(r.error!!.message.contains("different signing certificate"))
    }

    @Test
    fun disabledSwitchIsExternalControlDisabledAndStillRecordsPending() = runTest {
        val pairing = PairingStore(memoryStore())
        val ex = fleetExec(pairing = pairing, enabled = false)
        val r = parseFleet(ex.handle(fleetJson(nonce = nonce(3), now = NOW), CALLER))
        assertEquals(FleetError.EXTERNAL_CONTROL_DISABLED, r.error?.code)
        assertEquals(listOf(CALLER), pairing.pending.value)
    }

    @Test
    fun aPairedCallerIsRefusedWhileTheSwitchIsOff() = runTest {
        val r = parseFleet(fleetExec(enabled = false).handle(fleetJson(nonce = nonce(4), now = NOW), CALLER))
        assertEquals(FleetError.EXTERNAL_CONTROL_DISABLED, r.error?.code)
    }

    @Test
    fun blankIdentityIsUnauthorized() = runTest {
        val r = parseFleet(fleetExec().handle(fleetJson(nonce = nonce(5), now = NOW), CallerIdentity("", "")))
        assertEquals(FleetError.UNAUTHORIZED, r.error?.code)
    }

    @Test
    fun triggerFieldsNeverPairOrAuthorize() = runTest {
        val pairing = PairingStore(memoryStore())
        val ex = fleetExec(pairing = pairing)
        val r = parseFleet(
            ex.handle(
                fleetJson(
                    nonce = nonce(6),
                    now = NOW,
                    extra = buildJsonObject {
                        put("trigger", buildJsonObject {
                            put("paired", JsonPrimitive(true))
                            put("role", JsonPrimitive("owner"))
                            put("authorized", JsonPrimitive(true))
                        })
                        put("sourceNodeRef", JsonPrimitive(CALLER.sourceNodeRef()))
                    },
                ),
                CALLER,
            ),
        )
        assertEquals(FleetError.NOT_PAIRED, r.error?.code)
        assertEquals(listOf(CALLER), pairing.pending.value)
    }

    @Test
    fun replayAndExpiryAreCheckedAfterPairing() = runTest {
        val pairing = PairingStore(memoryStore()).also { it.approve(CALLER) }
        val replay = ReplayGuard(clock = { NOW })
        val ex = FleetExecutor(NODE, RecordingBackend(), ApprovalCenter(), pairing, { true }, replay = replay)
        val first = parseFleet(ex.handle(fleetJson(nonce = nonce(7), now = NOW), CALLER))
        assertEquals(true, first.ok)
        val replayed = parseFleet(ex.handle(fleetJson(nonce = nonce(7), now = NOW), CALLER))
        assertEquals(FleetError.REPLAY_DETECTED, replayed.error?.code)
        val expired = parseFleet(ex.handle(fleetJson(nonce = nonce(8), now = NOW - FLEET_MAX_SKEW_MS - 5_000), CALLER))
        assertEquals(FleetError.EXPIRED, expired.error?.code)
    }
}
