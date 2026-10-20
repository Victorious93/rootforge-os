package ai.rootforge.os.shizuku

/**
 * Platform seam for [ShizukuManager]. The Android host talks to the real
 * Shizuku/Sui binder; tests inject a fake. The rest of the app never calls
 * Shizuku APIs directly — only this host (and the UserService bind client)
 * may.
 */
interface ShizukuHost {
    fun signals(): ShizukuSignals

    fun requestPermission(requestCode: Int)

    fun setListener(listener: ShizukuHostListener?)
}

interface ShizukuHostListener {
    fun onBinderChanged()

    fun onPermissionResult(requestCode: Int, granted: Boolean)
}
