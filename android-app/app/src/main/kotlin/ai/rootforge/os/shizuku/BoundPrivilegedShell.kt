package ai.rootforge.os.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * App-process [PrivilegedShell]: binds [PrivilegedUserService] through
 * Shizuku only after [ShizukuManager] reports [ShizukuState.GRANTED].
 * Binding is not a command. The first privileged op is what starts the
 * user service, and that op has already gone through `ApprovalCenter`.
 *
 * Never logs encoded or decoded command output.
 */
class BoundPrivilegedShell(
    context: Context,
    private val manager: ShizukuManager,
) : PrivilegedShell {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "shizuku-client").apply { isDaemon = true }
    }

    @Volatile
    private var remote: IPrivilegedShell? = null

    @Volatile
    private var connection: ServiceConnection? = null

    override fun isAvailable(): Boolean = manager.state() == ShizukuState.GRANTED

    override fun exec(argv: List<String>, timeoutMs: Long): PrivilegedResult = offMain {
        call { it.exec(argv.toTypedArray(), timeoutMs) }
    }

    override fun readDeviceState(): PrivilegedResult = offMain {
        call { it.readDeviceState() }
    }

    override fun pmGrant(packageName: String, permission: String): PrivilegedResult = offMain {
        call { it.pmGrant(packageName, permission) }
    }

    private fun call(invoke: (IPrivilegedShell) -> String): PrivilegedResult {
        val service = connected() ?: return PrivilegedResult.Failure(ShizukuCopy.UNAVAILABLE_FAILURE)
        return try {
            PrivilegedResultCodec.decode(invoke(service))
        } catch (t: Throwable) {
            PrivilegedResult.Failure("Shizuku user service call failed", t)
        }
    }

    private fun connected(): IPrivilegedShell? {
        synchronized(lock) {
            remote?.asBinder()?.let { if (it.isBinderAlive) return remote }
            remote = null
            if (manager.state() != ShizukuState.GRANTED) return null
            val latch = CountDownLatch(1)
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    remote = IPrivilegedShell.Stub.asInterface(service)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    remote = null
                }
            }
            connection = conn
            return try {
                Shizuku.bindUserService(userServiceArgs(), conn)
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    remote = null
                    null
                } else {
                    remote
                }
            } catch (_: Throwable) {
                remote = null
                null
            }
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, PrivilegedUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("privileged")
            .debuggable(false)
            .version(USER_SERVICE_VERSION)

    private fun <T> offMain(block: () -> T): T {
        if (Looper.getMainLooper().thread != Thread.currentThread()) return block()
        return worker.submit(block).get(90, TimeUnit.SECONDS)
    }

    private companion object {
        const val USER_SERVICE_VERSION = 1
    }
}
