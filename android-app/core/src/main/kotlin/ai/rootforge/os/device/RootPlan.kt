package ai.rootforge.os.device

import ai.rootforge.os.catalog.BuildKind
import ai.rootforge.os.catalog.BuildLookup
import ai.rootforge.os.catalog.BuildMatch
import ai.rootforge.os.catalog.CatalogRecoveryBridge
import ai.rootforge.os.catalog.LoadedCatalog
import ai.rootforge.os.usb.TargetMode

data class RootPlanStep(
    val id: String,
    val title: String,
    val detail: String,
    val done: Boolean,
)

data class RootPlan(
    val headline: String,
    val steps: List<RootPlanStep>,
    val roms: List<BuildMatch> = emptyList(),
    val recoveries: List<BuildMatch> = emptyList(),
    val roots: List<BuildMatch> = emptyList(),
    val guide: RootGuide? = null,
) {
    val writableRecovery: BuildMatch?
        get() = recoveries.firstOrNull { CatalogRecoveryBridge.prefill(it) != null }
}

/**
 * Recommended order for that exact phone: unlock bootloader → custom
 * recovery → root manager. Catalog rows are display/link only except a
 * matching TWRP/PitchBlack recovery, which can open Custom Recovery.
 */
object RootPlanner {
    fun plan(identity: DeviceIdentity?, catalog: LoadedCatalog?): RootPlan {
        if (identity == null) {
            return RootPlan(
                headline = "Waiting for a read-only identify",
                steps = listOf(
                    RootPlanStep(
                        "identify",
                        "Read identity",
                        "Plug the phone in and grant USB access. Identify never writes.",
                        done = false,
                    ),
                ),
                guide = null,
            )
        }
        if (identity.mode == TargetMode.EDL) {
            return RootPlan(
                headline = "EDL identify only",
                steps = listOf(
                    RootPlanStep(
                        "edl",
                        "Leave EDL",
                        identity.note ?: "Qualcomm 9008. RootForge only reads the chip identity here. " +
                            "Reboot to Android (ADB) or fastboot to continue. No EDL writes from Devices.",
                        done = false,
                    ),
                ),
                guide = null,
            )
        }

        val matches = catalog?.let {
            BuildLookup.forTarget(identity.toTargetIdentity(), it.catalog, it.freshness, identity.modelNumber)
        }.orEmpty()
        val roms = matches.filter { it.kind == BuildKind.ROM }
        val recoveries = matches.filter { it.kind == BuildKind.RECOVERY }
        val roots = matches.filter { it.kind == BuildKind.ROOT }
        val writable = recoveries.filter { CatalogRecoveryBridge.prefill(it) != null }

        val unlocked = identity.bootloader == BootloaderLock.UNLOCKED
        val locked = identity.bootloader == BootloaderLock.LOCKED
        val rooted = identity.rootManagers.isNotEmpty()

        val unlock = when {
            unlocked -> RootPlanStep("unlock", "Unlock bootloader", "Already unlocked.", done = true)
            locked && identity.mode == TargetMode.FASTBOOT -> RootPlanStep(
                "unlock",
                "Unlock bootloader",
                "This usually wipes the phone. Open Fastboot for this serial and type UNLOCK. Nothing is written until you confirm.",
                done = false,
            )
            locked -> RootPlanStep(
                "unlock",
                "Unlock bootloader",
                "The bootloader is locked. Reboot this phone to fastboot, then Unlock. Unlocking usually wipes user data.",
                done = false,
            )
            else -> RootPlanStep(
                "unlock",
                "Unlock bootloader",
                "Lock state unknown. Check in fastboot (getvar unlocked) before any write.",
                done = false,
            )
        }

        val recoveryDetail = when {
            writable.isNotEmpty() ->
                "Catalog lists ${writable.joinToString { it.entry.name }} for this codename. " +
                    "Open Custom Recovery to look up the official file from the probed phone. " +
                    "Dry-run stays on; a write needs typed FLASH RECOVERY."
            recoveries.isNotEmpty() ->
                "Catalog lists ${recoveries.joinToString { it.entry.name }} (link only). " +
                    "Official TWRP or PitchBlack can still be looked up on Custom Recovery."
            else ->
                "No catalog recovery for this codename. Open Custom Recovery to look up official TWRP or PitchBlack for the probed phone."
        }
        val recovery = RootPlanStep("recovery", "Custom recovery", recoveryDetail, done = false)

        val rootDetail = when {
            rooted ->
                "Detected ${identity.rootManagers.joinToString { it.label }}. " +
                    "Open Root / Patching or Modules (USB) for this serial. Install still needs typed INSTALL MODULE."
            roots.isNotEmpty() ->
                "Catalog lists ${roots.joinToString { it.entry.name }}. After recovery, install Magisk, KernelSU or APatch on Root / Patching. RootForge does not patch the boot image itself."
            else ->
                "No catalog root row for this codename. After recovery, install Magisk, KernelSU or APatch from Root / Patching."
        }
        val root = RootPlanStep("root", "Root manager", rootDetail, done = rooted)

        val headline = when {
            identity.mode == TargetMode.UNKNOWN -> "Unrecognised USB device"
            rooted && unlocked -> "Rooted; bootloader unlocked"
            locked -> "Locked — unlock first"
            else -> "Recommended: unlock → recovery → root manager"
        }

        val romNote = if (roms.isEmpty()) emptyList() else listOf(
            RootPlanStep(
                "rom",
                "Matching ROMs (links)",
                "Catalog lists ${roms.joinToString { it.entry.name }}. RootForge does not download or flash a ROM from the catalog.",
                done = false,
            ),
        )

        return RootPlan(
            headline,
            listOf(unlock, recovery, root) + romNote,
            roms,
            recoveries,
            roots,
            guide = RootGuides.forIdentity(identity),
        )
    }
}
