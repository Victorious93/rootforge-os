package ai.rootforge.os.host

import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode
import ai.rootforge.os.usb.UsbClassifier

/**
 * Parses `adb devices -l` / `fastboot devices` text into [AttachedTarget]s.
 * Used by the desktop app and covered without talking to a device.
 */
object HostDevices {
    private val ADB_LINE = Regex(
        """^(\S+)\s+(device|unauthorized|offline|recovery|sideload|bootloader)(?:\s+(.*))?$""",
    )
    private val FASTBOOT_LINE = Regex("""^(\S+)\s+(fastboot|fastbootd)\s*$""")
    private val PROP = Regex("""(\w+):(\S+)""")

    fun parseAdb(output: String): List<AttachedTarget> {
        val out = mutableListOf<AttachedTarget>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("List of devices")) continue
            val m = ADB_LINE.matchEntire(line) ?: continue
            val serial = m.groupValues[1]
            val state = m.groupValues[2]
            val rest = m.groupValues.getOrNull(3).orEmpty()
            val props = PROP.findAll(rest).associate { it.groupValues[1] to it.groupValues[2].replace('_', ' ') }
            val mode = when (state) {
                "bootloader" -> TargetMode.FASTBOOT
                else -> TargetMode.ADB
            }
            out += AttachedTarget(
                deviceName = serial,
                vendorId = 0,
                productId = 0,
                mode = mode,
                interfaceId = null,
                hasPermission = state != "unauthorized" && state != "offline",
                serial = serial,
                product = props["model"] ?: props["product"],
                manufacturer = null,
            )
        }
        return out
    }

    fun parseFastboot(output: String): List<AttachedTarget> {
        val out = mutableListOf<AttachedTarget>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = FASTBOOT_LINE.matchEntire(line) ?: continue
            val serial = m.groupValues[1]
            out += AttachedTarget(
                deviceName = serial,
                vendorId = 0,
                productId = 0,
                mode = TargetMode.FASTBOOT,
                interfaceId = null,
                hasPermission = true,
                serial = serial,
                product = null,
                manufacturer = null,
            )
        }
        return out
    }

    /** `lsusb` lines containing Qualcomm 9008. */
    fun parseLsusbEdl(output: String): List<AttachedTarget> {
        val needle = "%04x:%04x".format(UsbClassifier.QUALCOMM_VID, UsbClassifier.EDL_PID)
        val out = mutableListOf<AttachedTarget>()
        for (raw in output.lineSequence()) {
            if (!raw.contains(needle, ignoreCase = true)) continue
            val busDev = Regex("""Bus (\d+) Device (\d+)""").find(raw)
            val name = if (busDev != null) {
                "/dev/bus/usb/%03d/%03d".format(busDev.groupValues[1].toInt(), busDev.groupValues[2].toInt())
            } else {
                "edl-${out.size + 1}"
            }
            out += AttachedTarget(
                deviceName = name,
                vendorId = UsbClassifier.QUALCOMM_VID,
                productId = UsbClassifier.EDL_PID,
                mode = TargetMode.EDL,
                interfaceId = null,
                hasPermission = true,
                serial = name,
                product = "9008",
                manufacturer = "Qualcomm",
            )
        }
        return out
    }

    fun merge(adb: List<AttachedTarget>, fastboot: List<AttachedTarget>, edl: List<AttachedTarget>): List<AttachedTarget> {
        val seen = LinkedHashMap<String, AttachedTarget>()
        (fastboot + adb + edl).forEach { t ->
            val key = t.serial ?: t.deviceName
            val existing = seen[key]
            if (existing == null || existing.mode == TargetMode.UNKNOWN) seen[key] = t
        }
        return seen.values.toList()
    }
}
