package ai.rootforge.os.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import ai.rootforge.os.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Binds the Shizuku UserService and forwards [PrivilegedOps] over AIDL.
 * Every call runs on [Dispatchers.IO]. Failures become [Result.failure]; nothing is thrown to the UI.
 */
class ShizukuPrivilegedOps(context: Context) : PrivilegedOps {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val args = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, PrivilegedUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("privileged")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
        .tag("rootforge-privileged-ops")

    @Volatile private var bound: IPrivilegedOps? = null
    @Volatile private var connection: ServiceConnection? = null

    override suspend fun listPackages(thirdPartyOnly: Boolean): Result<String> =
        invoke("list packages") { it.listPackages(thirdPartyOnly) }

    override suspend fun packageInfo(packageName: String): Result<String> =
        invoke("package info") { it.packageInfo(packageName) }

    override suspend fun settingsGet(namespace: String, key: String): Result<String> =
        invoke("settings get") { it.settingsGet(namespace, key) }

    override suspend fun getprop(key: String): Result<String> =
        invoke("getprop") { it.getprop(key) }

    override suspend fun listBlockDevices(): Result<String> =
        invoke("block devices") { it.listBlockDevices() }

    override suspend fun deviceInfo(): Result<String> =
        invoke("device info") { it.deviceInfo() }

    private suspend fun invoke(label: String, call: (IPrivilegedOps) -> String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                ensureReady()
                val api = obtain()
                Result.success(call(api).ifBlank { "(no output)" })
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Result.failure(IOException(t.message?.ifBlank { null } ?: "Shizuku $label failed."))
            }
        }

    private fun ensureReady() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val preV11 = runCatching { Shizuku.isPreV11() }.getOrDefault(true)
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        when (ShizukuStateMapper.map(ShizukuProbe(providerPresent = true, binderAlive = alive, permissionGranted = granted, preV11 = preV11))) {
            ShizukuStatus.Granted -> Unit
            ShizukuStatus.PermissionDenied -> throw IOException("Shizuku permission was not granted.")
            ShizukuStatus.NotRunning -> throw IOException("Shizuku is not running.")
            ShizukuStatus.NotInstalled -> throw IOException("Shizuku is not installed.")
        }
    }

    private suspend fun obtain(): IPrivilegedOps = mutex.withLock {
        val existing = bound
        if (existing != null && runCatching { existing.asBinder().pingBinder() }.getOrDefault(false)) {
            return@withLock existing
        }
        withTimeout(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (service == null || !service.pingBinder()) {
                            if (cont.isActive) cont.resumeWithException(IOException("Shizuku user service returned no binder."))
                            return
                        }
                        val api = IPrivilegedOps.Stub.asInterface(service)
                        bound = api
                        connection = this
                        if (cont.isActive) cont.resume(api)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        bound = null
                    }
                }
                try {
                    Shizuku.bindUserService(args, conn)
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(IOException(t.message ?: "Could not bind the Shizuku user service."))
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    runCatching { Shizuku.unbindUserService(args, conn, true) }
                    bound = null
                }
            }
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 15_000L
    }
}
