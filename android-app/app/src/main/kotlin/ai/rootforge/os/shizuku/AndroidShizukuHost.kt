package ai.rootforge.os.shizuku

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Real Shizuku/Sui binder. Every Shizuku API call is try/caught so a missing
 * or dead service never crashes the app. Sui uses the same `Shizuku.*` API.
 * `pingBinder()` is checked before any permission call.
 */
class AndroidShizukuHost(private val context: Context) : ShizukuHost {
    @Volatile
    private var hostListener: ShizukuHostListener? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { hostListener?.onBinderChanged() }
    private val binderDead = Shizuku.OnBinderDeadListener { hostListener?.onBinderChanged() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        hostListener?.onPermissionResult(requestCode, grantResult == PackageManager.PERMISSION_GRANTED)
    }

    override fun signals(): ShizukuSignals {
        val installed = ShizukuPackages.queryPackages.any { isInstalled(it) }
        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        val permissionGranted = if (!binderAlive) {
            false
        } else {
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        }
        return ShizukuSignals(installed, binderAlive, permissionGranted)
    }

    override fun requestPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (_: Throwable) {
            // Not running / not installed — UI already shows how to start Shizuku.
        }
    }

    override fun setListener(listener: ShizukuHostListener?) {
        val previous = this.hostListener
        this.hostListener = listener
        try {
            if (previous == null && listener != null) {
                Shizuku.addBinderReceivedListener(binderReceived)
                Shizuku.addBinderDeadListener(binderDead)
                Shizuku.addRequestPermissionResultListener(permissionResult)
            } else if (previous != null && listener == null) {
                Shizuku.removeBinderReceivedListener(binderReceived)
                Shizuku.removeBinderDeadListener(binderDead)
                Shizuku.removeRequestPermissionResultListener(permissionResult)
            }
        } catch (_: Throwable) {
            // Same graceful fallback as pingBinder: never crash the unprivileged app.
        }
    }

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: Throwable) {
        false
    }
}
