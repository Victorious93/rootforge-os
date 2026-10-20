package ai.rootforge.os.device

import ai.rootforge.os.core.TargetInfo
import ai.rootforge.os.fastboot.TargetIdentity
import ai.rootforge.os.root.RootManager
import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode

enum class BootloaderLock {
    LOCKED,
    UNLOCKED,
    UNKNOWN,
    ;

    val lockedLabel: String
        get() = when (this) {
            LOCKED -> "yes"
            UNLOCKED -> "no"
            UNKNOWN -> "unknown"
        }

    val unlockedLabel: String
        get() = when (this) {
            LOCKED -> "no"
            UNLOCKED -> "yes"
            UNKNOWN -> "unknown"
        }
}

/**
 * Read-only picture of one attached phone. [serial] is the full USB/adb
 * serial for targeting; UI and logs must use [maskedSerial].
 */
data class DeviceIdentity(
    val mode: TargetMode,
    val brand: String? = null,
    val model: String? = null,
    val codename: String? = null,
    val product: String? = null,
    val soc: String? = null,
    val androidVersion: String? = null,
    val sdk: String? = null,
    val securityPatch: String? = null,
    val bootloader: BootloaderLock = BootloaderLock.UNKNOWN,
    val rootManagers: List<RootManager> = emptyList(),
    val slot: String? = null,
    val serial: String? = null,
    val modelNumber: String? = null,
    val fingerprint: String? = null,
    val verifiedBoot: String? = null,
    val note: String? = null,
) {
    val maskedSerial: String get() = SerialMask.mask(serial)

    val preferredRoot: RootManager?
        get() = RootManager.detect(rootManagers.map { it.packageName }).preferred

    fun rosterKey(fallback: String? = null): String =
        serial?.trim()?.takeIf { it.isNotEmpty() } ?: fallback.orEmpty()

    fun toTargetIdentity(): TargetIdentity = TargetIdentity(
        product = TargetIdentity.clean(product),
        codename = TargetIdentity.clean(codename),
        slot = TargetIdentity.normalizeSlot(slot),
        fingerprint = TargetIdentity.clean(fingerprint),
    )

    fun displayRows(): List<Pair<String, String>> = listOf(
        "Brand" to (brand ?: "unknown"),
        "Model" to (model ?: "unknown"),
        "Product" to (product ?: "unknown"),
        "Codename" to (codename ?: "unknown"),
        "SoC" to (soc ?: "unknown"),
        "Android" to androidLine(),
        "Security patch" to (securityPatch ?: "unknown"),
        "Build" to (fingerprint ?: "unknown"),
        "Serial" to maskedSerial,
        "Verified boot" to (verifiedBoot ?: "unknown"),
        "Bootloader locked" to bootloader.lockedLabel,
        "Bootloader unlocked" to bootloader.unlockedLabel,
        "Slot" to (slot ?: "none / unknown"),
        "Current slot" to (slot ?: "none / unknown"),
        "Root managers" to rootManagers.joinToString { it.label }.ifBlank { "none detected" },
        "USB mode" to mode.label,
    ) + listOfNotNull(note?.let { "Note" to it })

    fun toTargetInfo(): TargetInfo = TargetInfo(mode, displayRows(), modelNumber = modelNumber)

    private fun androidLine(): String {
        val release = androidVersion ?: "?"
        val sdkPart = sdk?.let { " (SDK $it)" } ?: ""
        return if (androidVersion == null && sdk == null) "unknown" else "$release$sdkPart"
    }
}

fun AttachedTarget.rosterKey(): String = serial?.trim()?.takeIf { it.isNotEmpty() } ?: deviceName

/** Label that never includes a full serial. */
fun AttachedTarget.publicLabel(): String = displayName
