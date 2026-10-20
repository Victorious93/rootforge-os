package ai.rootforge.os.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayGuardTest {
    @Test
    fun aFreshNonceWithinTtlIsAccepted() {
        var now = 1_000_000L
        val guard = ReplayGuard(clock = { now })
        assertEquals(ReplayVerdict.OK, guard.check("pkg:aa", nonce(1), now, FLEET_DEFAULT_TTL_MS))
    }

    @Test
    fun duplicateNonceForTheSameSourceIsReplay() {
        var now = 1_000_000L
        val guard = ReplayGuard(clock = { now })
        assertEquals(ReplayVerdict.OK, guard.check("pkg:aa", nonce(1), now, FLEET_DEFAULT_TTL_MS))
        now += 1_000
        assertEquals(ReplayVerdict.REPLAY, guard.check("pkg:aa", nonce(1), now, FLEET_DEFAULT_TTL_MS))
    }

    @Test
    fun theSameNonceFromAnotherSourceIsNotAReplay() {
        val now = 1_000_000L
        val guard = ReplayGuard(clock = { now })
        assertEquals(ReplayVerdict.OK, guard.check("pkg:aa", nonce(1), now, FLEET_DEFAULT_TTL_MS))
        assertEquals(ReplayVerdict.OK, guard.check("other:bb", nonce(1), now, FLEET_DEFAULT_TTL_MS))
    }

    @Test
    fun pastTtlIsExpired() {
        val now = 1_000_000L
        val guard = ReplayGuard(clock = { now })
        val timestamp = now - (FLEET_DEFAULT_TTL_MS + 1)
        // Also fails skew; either way it is expired, not OK.
        assertEquals(ReplayVerdict.EXPIRED, guard.check("pkg:aa", nonce(2), timestamp, FLEET_DEFAULT_TTL_MS))
    }

    @Test
    fun clockSkewOver120sIsExpiredEvenIfTtlWouldCoverIt() {
        val now = 1_000_000L
        val guard = ReplayGuard(clock = { now })
        val timestamp = now - (FLEET_MAX_SKEW_MS + 1)
        assertEquals(ReplayVerdict.EXPIRED, guard.check("pkg:aa", nonce(3), timestamp, FLEET_MAX_TTL_MS))
    }

    @Test
    fun futureSkewOver120sIsExpired() {
        val now = 1_000_000L
        val guard = ReplayGuard(clock = { now })
        assertEquals(ReplayVerdict.EXPIRED, guard.check("pkg:aa", nonce(4), now + FLEET_MAX_SKEW_MS + 1, FLEET_DEFAULT_TTL_MS))
    }

    @Test
    fun anExpiredNonceStillBlocksALaterReplayWithAFreshTimestamp() {
        var now = 1_000_000L
        val guard = ReplayGuard(clock = { now })
        assertEquals(ReplayVerdict.EXPIRED, guard.check("pkg:aa", nonce(5), now - FLEET_MAX_SKEW_MS - 1, FLEET_MAX_TTL_MS))
        now += 1_000
        assertEquals(ReplayVerdict.REPLAY, guard.check("pkg:aa", nonce(5), now, FLEET_DEFAULT_TTL_MS))
    }

    @Test
    fun seenNoncesAreKeptAtLeastTenMinutes() {
        var now = 1_000_000L
        val guard = ReplayGuard(clock = { now }, retainMs = FLEET_NONCE_RETAIN_MS)
        assertEquals(ReplayVerdict.OK, guard.check("pkg:aa", nonce(6), now, FLEET_DEFAULT_TTL_MS))
        now += FLEET_NONCE_RETAIN_MS
        assertEquals(ReplayVerdict.REPLAY, guard.check("pkg:aa", nonce(6), now, FLEET_DEFAULT_TTL_MS))
        now += 1
        assertEquals(ReplayVerdict.OK, guard.check("pkg:aa", nonce(6), now, FLEET_DEFAULT_TTL_MS))
    }
}
