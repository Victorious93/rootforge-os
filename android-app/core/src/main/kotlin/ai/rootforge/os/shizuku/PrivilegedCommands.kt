package ai.rootforge.os.shizuku

/**
 * Argv builders for the read-only host-phone ops the Shizuku UserService may run.
 * Never produces a shell string: callers must exec the list as a process argv.
 * Rejects anything that is not a plausible package, property or settings key.
 */
object PrivilegedCommands {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val TOKEN = Regex("^[A-Za-z][A-Za-z0-9_./-]{0,127}$")
    private val NAMESPACES = setOf("system", "secure", "global")

    val DEVICE_INFO_KEYS: List<String> = listOf(
        "ro.product.manufacturer",
        "ro.product.model",
        "ro.product.name",
        "ro.product.device",
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.build.version.security_patch",
        "ro.build.fingerprint",
        "ro.product.cpu.abi",
        "ro.boot.slot_suffix",
        "ro.boot.verifiedbootstate",
        "ro.boot.flash.locked",
        "ro.serialno",
    )

    fun listPackages(thirdPartyOnly: Boolean): List<String> =
        buildList {
            add("pm")
            add("list")
            add("packages")
            if (thirdPartyOnly) add("-3")
        }

    fun packageInfo(packageName: String): List<String>? {
        if (!isPackage(packageName)) return null
        return listOf("dumpsys", "package", packageName)
    }

    fun settingsGet(namespace: String, key: String): List<String>? {
        if (namespace !in NAMESPACES) return null
        if (!TOKEN.matches(key)) return null
        return listOf("settings", "get", namespace, key)
    }

    fun getprop(key: String): List<String>? {
        if (!TOKEN.matches(key)) return null
        return listOf("getprop", key)
    }

    /** Read-only listings only: /proc/partitions and /dev/block names. */
    fun blockDeviceCommands(): List<List<String>> = listOf(
        listOf("cat", "/proc/partitions"),
        listOf("ls", "-l", "/dev/block/by-name"),
        listOf("ls", "-l", "/dev/block"),
    )

    fun isPackage(name: String): Boolean = name.length in 3..255 && PACKAGE.matches(name)

    fun isSettingsNamespace(name: String): Boolean = name in NAMESPACES
}
