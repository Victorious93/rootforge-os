package ai.rootforge.os.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingStoreTest {
    private val memory = object : PairingPersistence {
        var json = "[]"
        override fun read() = json
        override fun write(j: String) { json = j }
    }
    private val dca = CallerIdentity("ai.droidcommand.app", "aa".repeat(32))
    private val other = CallerIdentity("ai.other.app", "cc".repeat(32))

    @Test
    fun approvePersistsAndRevokeForgets() {
        val store = PairingStore(memory, clock = { 1000 })
        store.approve(dca)
        assertEquals(PairingStatus.PAIRED, store.status(dca))
        assertEquals(1, store.paired.value.size)
        assertTrue(memory.json.contains("ai.droidcommand.app"))

        val reloaded = PairingStore(memory)
        assertEquals(PairingStatus.PAIRED, reloaded.status(dca))
        assertEquals(1000, reloaded.paired.value.single().pairedAtMs)

        reloaded.revoke(dca.packageName)
        assertEquals(PairingStatus.UNKNOWN, reloaded.status(dca))
        assertEquals("[]", memory.json)
    }

    @Test
    fun approveReplacesAChangedCertificate() {
        val store = PairingStore(memory)
        store.approve(dca)
        val resigned = dca.copy(certSha256 = "bb".repeat(32))
        assertEquals(PairingStatus.CERT_CHANGED, store.status(resigned))
        store.noteAttempt(resigned)
        store.approve(resigned)
        assertEquals(PairingStatus.PAIRED, store.status(resigned))
        assertEquals(PairingStatus.CERT_CHANGED, store.status(dca))
        assertTrue(store.pending.value.isEmpty())
    }

    @Test
    fun noteAttemptDedupesByPackageAndCapsAtEight() {
        val store = PairingStore(memory)
        repeat(10) { i ->
            store.noteAttempt(CallerIdentity("pkg.$i", "dd".repeat(32)))
        }
        store.noteAttempt(CallerIdentity("pkg.9", "ee".repeat(32)))
        assertEquals(8, store.pending.value.size)
        assertEquals("pkg.9", store.pending.value.last().packageName)
        assertEquals("ee".repeat(32), store.pending.value.last().certSha256)
        assertEquals("pkg.2", store.pending.value.first().packageName)
    }

    @Test
    fun dismissRemovesOnlyThatPendingCaller() {
        val store = PairingStore(memory)
        store.noteAttempt(dca)
        store.noteAttempt(other)
        store.dismiss(dca)
        assertEquals(listOf(other), store.pending.value)
    }
}
