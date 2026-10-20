package ai.rootforge.os.device

import ai.rootforge.os.fastboot.FlashPlanner
import ai.rootforge.os.fastboot.TargetIdentity
import ai.rootforge.os.recovery.RecoveryIdentity
import ai.rootforge.os.root.RootManager
import ai.rootforge.os.usb.AttachedTarget
import ai.rootforge.os.usb.TargetMode
import ai.rootforge.os.usb.UsbClassifier

/**
 * Turns `getprop` / fastboot `getvar` / EDL Sahara identity into a
 * [DeviceIdentity]. Read-only: nothing here talks to a phone.
 */
object DeviceIdentityParser {
    fun parseGetpropDump(dump: String): Map<String, String> = RecoveryIdentity.parseGetprop(dump)

    fun parseGetvarDump(dump: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        dump.lineSequence().forEach { line ->
            FlashPlanner.parseVarLine(line.trim())?.let { (k, v) -> map.putIfAbsent(k, v) }
        }
        return map
    }

    fun managersFromPm(packageListOutput: String): List<RootManager> =
        RootManager.entries.filter { packageListOutput.contains("package:${it.packageName}") }

    fun fromAdb(
        props: Map<String, String>,
        packageListOutput: String = "",
        serialFallback: String? = null,
        mode: TargetMode = TargetMode.ADB,
    ): DeviceIdentity {
        val brand = clean(props["ro.product.brand"] ?: props["ro.product.manufacturer"])
        val model = clean(props["ro.product.model"])
        val serial = clean(props["ro.serialno"] ?: props["ro.boot.serialno"]) ?: clean(serialFallback)
        return DeviceIdentity(
            mode = mode,
            brand = brand,
            model = model,
            codename = clean(props["ro.product.device"]),
            product = clean(props["ro.product.name"]),
            soc = socFromAdb(props),
            androidVersion = clean(props["ro.build.version.release"]),
            sdk = clean(props["ro.build.version.sdk"]),
            securityPatch = clean(props["ro.build.version.security_patch"]),
            bootloader = bootloaderFromAdb(props),
            rootManagers = managersFromPm(packageListOutput),
            slot = TargetIdentity.normalizeSlot(props["ro.boot.slot_suffix"] ?: props["ro.boot.slot"]),
            serial = serial,
            modelNumber = model,
            fingerprint = clean(props["ro.build.fingerprint"]),
            verifiedBoot = clean(props["ro.boot.verifiedbootstate"]),
        )
    }

    fun fromAdb(props: Map<String, String>, packageListOutput: String, target: AttachedTarget): DeviceIdentity =
        fromAdb(props, packageListOutput, target.serial ?: target.deviceName, target.mode)

    fun fromFastboot(vars: Map<String, String>, serialFallback: String? = null): DeviceIdentity {
        val product = clean(vars["product"])
        val serial = clean(vars["serialno"]) ?: clean(serialFallback)
        return DeviceIdentity(
            mode = TargetMode.FASTBOOT,
            brand = clean(vars["oem"] ?: vars["brand"]),
            model = clean(vars["variant"] ?: vars["model"]) ?: product,
            codename = clean(vars["device"]) ?: product,
            product = product,
            soc = socFromFastboot(vars),
            androidVersion = null,
            sdk = null,
            securityPatch = null,
            bootloader = bootloaderFromFastboot(vars),
            rootManagers = emptyList(),
            slot = TargetIdentity.normalizeSlot(vars["current-slot"]),
            serial = serial,
            modelNumber = clean(vars["variant"] ?: vars["model"]),
            fingerprint = clean(vars["fingerprint"]),
            verifiedBoot = clean(vars["secure"]),
            note = "Fastboot identify uses getvar only; Android version and root manager need ADB.",
        )
    }

    fun fromFastboot(vars: Map<String, String>, target: AttachedTarget): DeviceIdentity {
        val id = fromFastboot(vars, target.serial ?: target.deviceName)
        val usbBrand = clean(target.manufacturer)
        val usbModel = clean(target.product)
        return id.copy(
            brand = id.brand ?: usbBrand,
            model = when {
                id.model != null && id.model != id.product -> id.model
                usbModel != null -> usbModel
                else -> id.model
            },
        )
    }

    /**
     * Qualcomm 9008 identity only. No Firehose, no programmer, no write.
     */
    fun fromEdl(
        vendorId: Int = UsbClassifier.QUALCOMM_VID,
        productId: Int = UsbClassifier.EDL_PID,
        chipSerial: String? = null,
        msmHwId: String? = null,
        serialFallback: String? = null,
    ): DeviceIdentity {
        val serial = clean(chipSerial) ?: clean(serialFallback)
        val vidPid = "%04x:%04x".format(vendorId, productId)
        return DeviceIdentity(
            mode = TargetMode.EDL,
            brand = "Qualcomm",
            model = "9008",
            codename = null,
            product = "9008",
            soc = clean(msmHwId)?.let { "MSM $it" } ?: "Qualcomm 9008",
            serial = serial,
            note = "EDL ($vidPid): identify only. RootForge does not write in EDL from Devices. " +
                "Reboot to ADB or fastboot to continue a root plan.",
        )
    }

    fun fromEdl(target: AttachedTarget, chipSerial: String? = null, msmHwId: String? = null): DeviceIdentity =
        fromEdl(target.vendorId, target.productId, chipSerial, msmHwId, target.serial ?: target.deviceName)

    fun stub(target: AttachedTarget, note: String? = null): DeviceIdentity = DeviceIdentity(
        mode = target.mode,
        brand = TargetIdentity.clean(target.manufacturer),
        model = TargetIdentity.clean(target.product),
        serial = TargetIdentity.clean(target.serial) ?: TargetIdentity.clean(target.deviceName),
        note = note,
    )

    fun bootloaderFromAdb(props: Map<String, String>): BootloaderLock {
        when (props["ro.boot.flash.locked"]?.trim()?.lowercase()) {
            "1", "true", "yes" -> return BootloaderLock.LOCKED
            "0", "false", "no" -> return BootloaderLock.UNLOCKED
        }
        when (props["ro.boot.vbmeta.device_state"]?.trim()?.lowercase()) {
            "locked" -> return BootloaderLock.LOCKED
            "unlocked" -> return BootloaderLock.UNLOCKED
        }
        return BootloaderLock.UNKNOWN
    }

    fun bootloaderFromFastboot(vars: Map<String, String>): BootloaderLock =
        when (vars["unlocked"]?.trim()?.lowercase()) {
            "yes", "true", "1" -> BootloaderLock.UNLOCKED
            "no", "false", "0" -> BootloaderLock.LOCKED
            else -> BootloaderLock.UNKNOWN
        }

    private fun socFromAdb(props: Map<String, String>): String? {
        val socModel = clean(props["ro.soc.model"])
        val socMfr = clean(props["ro.soc.manufacturer"])
        val named = listOfNotNull(socMfr, socModel).joinToString(" ").ifBlank { null }
        return named
            ?: clean(props["ro.board.platform"])
            ?: clean(props["ro.chipname"])
            ?: clean(props["ro.hardware.chipname"])
            ?: clean(props["ro.hardware"])
    }

    private fun socFromFastboot(vars: Map<String, String>): String? =
        clean(vars["soc-id"] ?: vars["soc"] ?: vars["hw-revision"] ?: vars["hardware"])

    private fun clean(raw: String?): String? = TargetIdentity.clean(raw)
}
