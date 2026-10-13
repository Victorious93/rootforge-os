package ai.rootforge.os.usb

/** What a USB device attached to the host phone is, as far as its descriptors say. */
enum class TargetMode(val label: String) {
    ADB("ADB"),
    FASTBOOT("Fastboot"),
    EDL("EDL (Qualcomm 9008)"),
    UNKNOWN("Unrecognised"),
}

data class UsbIfaceInfo(val id: Int, val usbClass: Int, val subclass: Int, val protocol: Int)

data class UsbClassification(val mode: TargetMode, val interfaceId: Int?)

/**
 * Pure descriptor matching, kept free of android.hardware.usb so it is unit
 * tested. ADB is vendor-class 0xFF/0x42/0x01, fastboot 0xFF/0x42/0x03; the
 * Qualcomm Emergency Download port enumerates as 05c6:9008 with a vendor
 * interface and is identified by VID/PID because its descriptors carry no
 * subclass/protocol marker.
 */
object UsbClassifier {
    const val CLASS_VENDOR = 0xFF
    const val SUBCLASS_ADB_FASTBOOT = 0x42
    const val PROTOCOL_ADB = 0x01
    const val PROTOCOL_FASTBOOT = 0x03
    const val QUALCOMM_VID = 0x05C6
    const val EDL_PID = 0x9008

    fun classify(vendorId: Int, productId: Int, interfaces: List<UsbIfaceInfo>): UsbClassification {
        if (vendorId == QUALCOMM_VID && productId == EDL_PID) {
            val iface = interfaces.firstOrNull { it.usbClass == CLASS_VENDOR } ?: interfaces.firstOrNull()
            return UsbClassification(TargetMode.EDL, iface?.id)
        }
        interfaces.firstOrNull { it.isAdb() }?.let { return UsbClassification(TargetMode.ADB, it.id) }
        interfaces.firstOrNull { it.isFastboot() }?.let { return UsbClassification(TargetMode.FASTBOOT, it.id) }
        return UsbClassification(TargetMode.UNKNOWN, null)
    }

    private fun UsbIfaceInfo.isAdb() =
        usbClass == CLASS_VENDOR && subclass == SUBCLASS_ADB_FASTBOOT && protocol == PROTOCOL_ADB

    private fun UsbIfaceInfo.isFastboot() =
        usbClass == CLASS_VENDOR && subclass == SUBCLASS_ADB_FASTBOOT && protocol == PROTOCOL_FASTBOOT
}
