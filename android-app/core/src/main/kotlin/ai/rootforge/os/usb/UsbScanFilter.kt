package ai.rootforge.os.usb

/**
 * Splits the USB enumeration into devices Flash Tools can talk to and
 * everything else (HID, charge-only cables, unknown gadgets). Classification
 * is [UsbClassifier]; this only partitions the result.
 */
data class UsbScan(
    val flashTargets: List<AttachedTarget>,
    val ignored: List<AttachedTarget>,
) {
    val isEmpty: Boolean get() = flashTargets.isEmpty()
}

object UsbScanFilter {
    fun isFlashTarget(mode: TargetMode): Boolean = mode != TargetMode.UNKNOWN

    fun isFlashTarget(vendorId: Int, productId: Int, interfaces: List<UsbIfaceInfo>): Boolean =
        isFlashTarget(UsbClassifier.classify(vendorId, productId, interfaces).mode)

    fun partition(devices: List<AttachedTarget>): UsbScan {
        val flash = devices.filter { isFlashTarget(it.mode) }
        val ignored = devices.filter { !isFlashTarget(it.mode) }
        return UsbScan(flash, ignored)
    }

    fun describe(scan: UsbScan): List<String> {
        val lines = mutableListOf<String>()
        if (scan.flashTargets.isEmpty()) {
            lines += "No ADB, fastboot or EDL device attached."
        } else {
            scan.flashTargets.forEach { t ->
                val perm = if (t.hasPermission) "" else "  (needs USB permission)"
                lines += "${t.vidPid}  ${t.mode.label}  ${t.displayName}$perm"
            }
        }
        scan.ignored.forEach { t ->
            lines += "Ignored ${t.vidPid} (${t.product ?: t.manufacturer ?: "unrecognised USB device"})"
        }
        return lines
    }
}
