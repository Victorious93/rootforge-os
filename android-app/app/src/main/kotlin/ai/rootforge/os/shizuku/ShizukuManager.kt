package ai.rootforge.os.shizuku

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks Shizuku / Sui on this phone and exposes one of the four [ShizukuStatus] values.
 * All Shizuku API calls are swallowed: the rest of the app works if Shizuku is missing.
 */
class ShizukuManager(private val app: Application) {
    private val _status = MutableStateFlow(ShizukuStatus.NotInstalled)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val _result = MutableStateFlow("")
    val result: StateFlow<String> = _result.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val running = AtomicBoolean(false)

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun start() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Throwable) {
            // Provider not initialised yet; refresh() still reports NotInstalled / NotRunning.
        }
        refresh()
    }

    fun refresh() {
        _status.value = runCatching { ShizukuStateMapper.map(probe()) }
            .getOrElse { if (providerPresent()) ShizukuStatus.NotRunning else ShizukuStatus.NotInstalled }
    }

    fun requestPermission() {
        try {
            if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
                refresh()
                return
            }
            if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                refresh()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                refresh()
                return
            }
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
            refresh()
        }
    }

    fun probe(): ShizukuProbe {
        val present = providerPresent()
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val preV11 = alive && runCatching { Shizuku.isPreV11() }.getOrDefault(false)
        val granted = alive && !preV11 && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return ShizukuProbe(
            providerPresent = present,
            binderAlive = alive,
            permissionGranted = granted,
            preV11 = preV11,
        )
    }

    fun providerPresent(): Boolean = suiPresent() || shizukuPackageInstalled()

    fun suiPresent(): Boolean = runCatching { Sui.isSui() }.getOrDefault(false)

    fun shizukuPackageInstalled(): Boolean = runCatching {
        val pm = app.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(ShizukuLinks.PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ShizukuLinks.PACKAGE, 0)
        }
        true
    }.getOrDefault(false)

    /**
     * Runs a host-phone lookup and stores the text for the Settings panel.
     * Callers must not write [result] into the shared command log.
     */
    suspend fun run(label: String, block: suspend () -> Result<String>): Result<String> {
        if (!running.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("A Shizuku lookup is already running."))
        }
        _busy.value = true
        return try {
            val result = runCatching { block() }.getOrElse { Result.failure(it) }
            result.fold(
                onSuccess = { text -> _result.value = text },
                onFailure = { err -> _result.value = err.message?.ifBlank { null } ?: "Could not run $label." },
            )
            result
        } catch (t: Throwable) {
            _result.value = "Could not run $label."
            Result.failure(t)
        } finally {
            running.set(false)
            _busy.value = false
        }
    }

    private companion object {
        const val REQUEST_CODE = 23
    }
}
