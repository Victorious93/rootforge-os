package ai.rootforge.os.recovery

/**
 * How this phone stores recovery, from `getprop` plus `ls` of
 * `/dev/block/by-name` (and the bootdevice copy). Detection never
 * implies a write is allowed: [writePath] is set only for a dedicated
 * recovery partition whose slot, if A/B, is known.
 */
enum class SlotScheme { A_ONLY, A_B, UNKNOWN }

enum class RecoveryKind {
    /** A real `recovery` / `recovery_a` / `recovery_b` block device. */
    DEDICATED,
    /** Recovery lives in `vendor_boot` ramdisk. */
    IN_VENDOR_BOOT,
    /** Recovery lives in `boot` ramdisk (typical A/B without recovery). */
    IN_BOOT,
    UNKNOWN,
}

enum class InstallFlow {
    /** `dd` a recovery.img onto the dedicated recovery partition. */
    FLASH_IMAGE,
    /** Official installer zip, flashed from an already-running recovery (sideload). */
    INSTALLER_ZIP,
    /** `fastboot boot recovery.img` once; nothing is written. */
    TEMPORARY_BOOT,
}

data class RecoveryLayout(
    val slotScheme: SlotScheme,
    val kind: RecoveryKind,
    val currentSlot: String?,
    val byNameDir: String?,
    val partitionNames: Set<String>,
    /** `recovery`, `recovery_a` or `recovery_b` when a write is allowed. */
    val writePartition: String?,
    /** Absolute `/dev/block/.../recovery[_a|_b]` when a write is allowed. */
    val writePath: String?,
    val allowedFlows: Set<InstallFlow>,
    val notes: List<String>,
) {
    val canWriteImage: Boolean get() = writePath != null && InstallFlow.FLASH_IMAGE in allowedFlows
}

object RecoveryLayoutDetector {
    const val BY_NAME = "/dev/block/by-name"
    const val BOOTDEVICE_BY_NAME = "/dev/block/bootdevice/by-name"
    val ALLOWED_DIRS = setOf(BY_NAME, BOOTDEVICE_BY_NAME)

    /** Shell snippet whose stdout [parseListings] understands. */
    const val LIST_SCRIPT =
        "echo 'DIR $BY_NAME'; ls -1 $BY_NAME 2>/dev/null; " +
            "echo 'DIR $BOOTDEVICE_BY_NAME'; ls -1 $BOOTDEVICE_BY_NAME 2>/dev/null"

    private val NAME = Regex("""^[A-Za-z0-9._-]+$""")

    fun detect(identity: RecoveryIdentity, listing: String): RecoveryLayout {
        val dirs = parseListings(listing)
        val preferredDir = when {
            dirs[BY_NAME]?.isNotEmpty() == true -> BY_NAME
            dirs[BOOTDEVICE_BY_NAME]?.isNotEmpty() == true -> BOOTDEVICE_BY_NAME
            else -> null
        }
        val names = preferredDir?.let { dirs[it] }.orEmpty()
        val notes = mutableListOf<String>()

        val hasRecovery = "recovery" in names
        val hasRecoveryA = "recovery_a" in names
        val hasRecoveryB = "recovery_b" in names
        val hasVendorBoot = names.any { it == "vendor_boot" || it.startsWith("vendor_boot_") }
        val hasBoot = names.any { it == "boot" || it == "boot_a" || it == "boot_b" }
        val slotFromNames = names.any { it.endsWith("_a") || it.endsWith("_b") }

        val slotScheme = when {
            identity.abUpdate == true || identity.slot != null || hasRecoveryA || hasRecoveryB || slotFromNames -> SlotScheme.A_B
            identity.abUpdate == false && !slotFromNames -> SlotScheme.A_ONLY
            names.isEmpty() -> SlotScheme.UNKNOWN
            else -> SlotScheme.A_ONLY
        }

        val (writePartition, writeNote) = writeTarget(
            slot = identity.slot,
            hasRecovery = hasRecovery,
            hasRecoveryA = hasRecoveryA,
            hasRecoveryB = hasRecoveryB,
        )
        writeNote?.let { notes += it }

        val kind = when {
            writePartition != null -> RecoveryKind.DEDICATED
            hasRecoveryA || hasRecoveryB || hasRecovery -> RecoveryKind.DEDICATED
            hasVendorBoot -> RecoveryKind.IN_VENDOR_BOOT
            hasBoot -> RecoveryKind.IN_BOOT
            else -> RecoveryKind.UNKNOWN
        }

        if (kind == RecoveryKind.IN_VENDOR_BOOT) {
            notes += "Recovery lives in vendor_boot on this device. RootForge will not write boot or vendor_boot."
        }
        if (kind == RecoveryKind.IN_BOOT) {
            notes += "Recovery lives in the boot ramdisk on this device. RootForge will not write boot."
        }
        if (kind == RecoveryKind.UNKNOWN) {
            notes += "No recovery, vendor_boot or boot partition name was found. Unknown is not treated as safe to write."
        }
        if (preferredDir == null) {
            notes += "Could not list $BY_NAME or $BOOTDEVICE_BY_NAME."
        }

        val writePath = if (writePartition != null && preferredDir != null) "$preferredDir/$writePartition" else null

        val flows = mutableSetOf<InstallFlow>()
        if (writePath != null) flows += InstallFlow.FLASH_IMAGE
        flows += InstallFlow.INSTALLER_ZIP
        flows += InstallFlow.TEMPORARY_BOOT
        if (writePath == null) {
            notes += "Without a dedicated recovery partition the official installer zip (from an already-running recovery) or a one-shot fastboot boot is the remaining path."
        }

        return RecoveryLayout(
            slotScheme = slotScheme,
            kind = kind,
            currentSlot = identity.slot,
            byNameDir = preferredDir,
            partitionNames = names,
            writePartition = writePartition,
            writePath = writePath,
            allowedFlows = flows,
            notes = notes,
        )
    }

    /**
     * `DIR <path>` then names until the next `DIR` or EOF. `ls` errors
     * (`No such file`, `Permission denied`) are skipped.
     */
    fun parseListings(raw: String): Map<String, Set<String>> {
        val out = linkedMapOf<String, MutableSet<String>>()
        var current: String? = null
        for (line in raw.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("DIR ")) {
                val dir = t.removePrefix("DIR ").trim()
                current = dir.takeIf { it in ALLOWED_DIRS }
                if (current != null) out.putIfAbsent(current, linkedSetOf())
                continue
            }
            val dir = current ?: continue
            if (t.startsWith("ls:") || t.startsWith("ls :") || t.contains("No such file") ||
                t.contains("Permission denied") || t.contains("Not a directory")
            ) continue
            // `ls -1` prints a single name per line. Reject paths, `..`, and metacharacters.
            if (NAME.matches(t)) out.getOrPut(dir) { linkedSetOf() }.add(t)
        }
        return out
    }

    internal fun writeTarget(
        slot: String?,
        hasRecovery: Boolean,
        hasRecoveryA: Boolean,
        hasRecoveryB: Boolean,
    ): Pair<String?, String?> {
        if (slot == "a" && hasRecoveryA) return "recovery_a" to null
        if (slot == "b" && hasRecoveryB) return "recovery_b" to null
        if (hasRecoveryA || hasRecoveryB) {
            return if (slot == null) {
                null to "This device has recovery_a/recovery_b but the current slot is unknown; will not guess which one to write."
            } else {
                null to "Slot $slot has no recovery_$slot partition (found " +
                    listOfNotNull("recovery_a".takeIf { hasRecoveryA }, "recovery_b".takeIf { hasRecoveryB }).joinToString("/") +
                    ")."
            }
        }
        if (hasRecovery) return "recovery" to null
        return null to null
    }
}
