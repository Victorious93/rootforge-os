package ai.rootforge.os.shizuku

import android.content.Context
import androidx.annotation.Keep
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Shizuku/Sui UserService — runs as the privileged user (ADB shell or Sui
 * root) in a separate process. Fleet AIDL: destroy=16777114, exec=1,
 * readDeviceState=2, pmGrant=3. All work is on a background executor.
 *
 * Does not log command output. Does not confirm: the app process must have
 * already passed `ApprovalCenter` before binding and calling into this
 * service. `pmGrant` exists for fleet interop only; RootForge does not
 * expose it in the UI and still requires a typed confirmation in the
 * app process.
 */
class PrivilegedUserService @Keep constructor() : IPrivilegedShell.Stub() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rootforge-privileged").apply { isDaemon = true }
    }

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    override fun destroy() {
        worker.shutdownNow()
        System.exit(0)
    }

    override fun exec(argv: Array<String>, timeoutMs: Long): String = onWorker(timeoutMs) {
        PrivilegedResultCodec.encode(PrivilegedProcessRunner.run(argv.toList(), timeoutMs))
    }

    override fun readDeviceState(): String = onWorker(30_000) {
        PrivilegedResultCodec.encode(PrivilegedProcessRunner.readDeviceState())
    }

    override fun pmGrant(packageName: String, permission: String): String = onWorker(20_000) {
        PrivilegedResultCodec.encode(PrivilegedProcessRunner.pmGrant(packageName, permission))
    }

    private fun onWorker(timeoutMs: Long, block: () -> String): String {
        return try {
            worker.submit(block).get(timeoutMs.coerceAtLeast(1_000L) + 5_000L, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            PrivilegedResultCodec.encode(PrivilegedResult.Failure("Privileged user service failed", t))
        }
    }
}
