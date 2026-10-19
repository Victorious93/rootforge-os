package ai.rootforge.os.shizuku

/**
 * Kotlin face for the bound Shizuku UserService. Implementations run every
 * call on a background dispatcher. Read-only on purpose.
 */
interface PrivilegedOps {
    suspend fun listPackages(thirdPartyOnly: Boolean = false): Result<String>
    suspend fun packageInfo(packageName: String): Result<String>
    suspend fun settingsGet(namespace: String, key: String): Result<String>
    suspend fun getprop(key: String): Result<String>
    suspend fun listBlockDevices(): Result<String>
    suspend fun deviceInfo(): Result<String>
}
