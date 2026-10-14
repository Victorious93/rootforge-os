package ai.rootforge.os.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Who the caller is, as established by Android rather than by anything in
 * the request: the package and signing certificate behind the calling UID.
 */
data class CallerIdentity(val packageName: String, val certSha256: String)

@Serializable
data class PairedController(val packageName: String, val certSha256: String, val pairedAtMs: Long)

enum class PairingStatus { PAIRED, PENDING, UNKNOWN, CERT_CHANGED }

/**
 * The controllers the owner has approved. A controller is identified by
 * package name AND signing certificate hash: if either changes the pairing
 * does not carry over, so a different app reusing the package name is not
 * treated as the paired one. Pairing happens only from this app's own UI.
 */
/** Where the paired list is kept (SharedPreferences in the app, memory in tests). */
interface PairingPersistence {
    fun read(): String
    fun write(json: String)
}

class PairingStore(private val storage: PairingPersistence, private val clock: () -> Long = System::currentTimeMillis) {
    private val pairedState = MutableStateFlow(load())
    val paired: StateFlow<List<PairedController>> = pairedState.asStateFlow()
    private val pendingState = MutableStateFlow<List<CallerIdentity>>(emptyList())
    val pending: StateFlow<List<CallerIdentity>> = pendingState.asStateFlow()

    fun status(caller: CallerIdentity): PairingStatus {
        val byPackage = pairedState.value.firstOrNull { it.packageName == caller.packageName }
        return when {
            byPackage == null -> if (pendingState.value.contains(caller)) PairingStatus.PENDING else PairingStatus.UNKNOWN
            byPackage.certSha256.equals(caller.certSha256, ignoreCase = true) -> PairingStatus.PAIRED
            else -> PairingStatus.CERT_CHANGED
        }
    }

    /** Records an unpaired caller so the owner can see and approve it; never grants anything. */
    fun noteAttempt(caller: CallerIdentity) {
        if (status(caller) == PairingStatus.PAIRED) return
        pendingState.value = (pendingState.value.filterNot { it.packageName == caller.packageName } + caller).takeLast(8)
    }

    fun approve(caller: CallerIdentity) {
        val next = pairedState.value.filterNot { it.packageName == caller.packageName } +
            PairedController(caller.packageName, caller.certSha256.lowercase(), clock())
        pairedState.value = next
        pendingState.value = pendingState.value.filterNot { it.packageName == caller.packageName }
        save(next)
    }

    fun dismiss(caller: CallerIdentity) {
        pendingState.value = pendingState.value.filterNot { it == caller }
    }

    fun revoke(packageName: String) {
        val next = pairedState.value.filterNot { it.packageName == packageName }
        pairedState.value = next
        save(next)
    }

    private fun load(): List<PairedController> =
        runCatching { Json.decodeFromString<List<PairedController>>(storage.read()) }.getOrDefault(emptyList())

    private fun save(list: List<PairedController>) {
        storage.write(Json.encodeToString(list))
    }
}
