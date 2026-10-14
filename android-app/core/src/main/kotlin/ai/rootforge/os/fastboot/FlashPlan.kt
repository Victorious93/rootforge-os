package ai.rootforge.os.fastboot

import ai.rootforge.os.core.humanSize

enum class Risk(val label: String) { NORMAL("Standard"), ELEVATED("Elevated"), SEVERE("Severe: can brick the device") }

data class FlashPlan(
    val partition: String,
    val imageName: String,
    val imageSize: Long,
    val lines: List<String>,
    val blockers: List<String>,
    val risk: Risk,
) {
    val allowed: Boolean get() = blockers.isEmpty()
    val confirmationPhrase: String get() = "FLASH $partition"
}

/**
 * Builds the plan shown before any write, and decides what is refused
 * outright. This mirrors the shell-side contract (explicit target, printed
 * plan, typed confirmation) and, like it, treats "unknown" as unsafe: an
 * image that does not look like a flashable image is refused, not guessed at.
 */
object FlashPlanner {
    private val SEVERE = setOf(
        "bootloader", "xbl", "xbl_config", "abl", "aboot", "sbl1", "tz", "rpm", "hyp", "aop", "keymaster",
        "modem", "radio", "persist", "efs", "modemst1", "modemst2", "fsg", "devinfo", "ssd", "sec", "gpt", "pmic",
    )
    private val ELEVATED = setOf("vbmeta", "vbmeta_system", "vbmeta_vendor", "userdata", "metadata", "frp", "misc", "super", "dtbo")
    private val ANDROID_BOOT_PARTITIONS = setOf("boot", "init_boot", "recovery", "vendor_boot", "vendor_kernel_boot")

    fun plan(
        partition: String,
        slot: String?,
        imageName: String,
        imageSize: Long,
        imageHead: ByteArray,
        maxDownload: Long?,
        deviceUnlocked: Boolean?,
    ): FlashPlan {
        val blockers = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val base = partition.removeSuffix("_a").removeSuffix("_b")

        if (!Regex("[A-Za-z0-9_.-]{1,40}").matches(partition)) blockers += "'$partition' is not a plausible partition name."
        if (imageSize <= 0) blockers += "The image is empty."

        val sparse = imageHead.size >= 4 &&
            imageHead[0] == 0x3A.toByte() && imageHead[1] == 0xFF.toByte() && imageHead[2] == 0x26.toByte() && imageHead[3] == 0xED.toByte()
        if (base in ANDROID_BOOT_PARTITIONS && !sparse) {
            val magic = String(imageHead, 0, minOf(8, imageHead.size), Charsets.ISO_8859_1)
            val vendorBoot = magic.startsWith("VNDRBOOT")
            if (magic != "ANDROID!" && !vendorBoot) {
                blockers += "'$partition' expects an Android boot-format image (ANDROID! or VNDRBOOT header); this file starts with ${magic.filter { it.isLetterOrDigit() }.ifEmpty { "other data" }}."
            }
        }
        if (maxDownload != null && imageSize > maxDownload && !sparse) {
            blockers += "The image (${humanSize(imageSize)}) is larger than the bootloader's max-download-size " +
                "(${humanSize(maxDownload)}) and is not a sparse image. Splitting raw images is not implemented; " +
                "convert it with img2simg or flash it from a desktop fastboot."
        }
        if (deviceUnlocked == false) blockers += "The bootloader reports it is locked; fastboot will refuse the write."

        val risk = when {
            base in SEVERE -> Risk.SEVERE
            base in ELEVATED -> Risk.ELEVATED
            else -> Risk.NORMAL
        }
        lines += "Partition:  $partition" + (slot?.let { "  (current slot: $it)" } ?: "")
        lines += "Image:      $imageName (${humanSize(imageSize)})${if (sparse) ", sparse" else ""}"
        lines += "Bootloader: " + when (deviceUnlocked) { true -> "unlocked"; false -> "LOCKED"; null -> "lock state unknown" }
        lines += "Risk:       ${risk.label}"
        if (partition == "userdata") lines += "Flashing userdata erases all user data."
        if (risk == Risk.SEVERE) lines += "A wrong image here can permanently brick the device. Only continue with an image built for this exact model."
        if (slot == null && partition == base) lines += "Slot layout unknown; the bootloader will pick the partition as named."

        return FlashPlan(partition, imageName, imageSize, lines, blockers, risk)
    }
}

enum class RebootTarget(val label: String, val command: String) {
    SYSTEM("System", "reboot"),
    BOOTLOADER("Bootloader", "reboot-bootloader"),
    RECOVERY("Recovery", "reboot-recovery"),
    FASTBOOTD("Fastbootd", "reboot-fastboot"),
}
