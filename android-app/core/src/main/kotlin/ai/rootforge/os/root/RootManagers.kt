package ai.rootforge.os.root

/**
 * The root solutions RootForge drives. Patching is done by the manager
 * app on the target device; RootForge does not bundle or re-implement it.
 * [caveat] is non-null for managers RootForge cannot vouch for; the UI shows it.
 */
enum class RootManager(
    val label: String,
    val packageName: String,
    /** Where to get releases; null when no trustworthy source is known. */
    val homepage: String?,
    val patchSteps: List<String>,
    val moduleInstallCommand: String,
    val caveat: String? = null,
) {
    MAGISK(
        "Magisk", "com.topjohnwu.magisk", "https://github.com/topjohnwu/Magisk/releases",
        listOf(
            "Open Magisk on the target and tap Install, next to Magisk.",
            "Choose Select and Patch a File and pick the boot image RootForge pushed to Download.",
            "Wait for the log to finish. The patched file is saved as magisk_patched-*.img in Download.",
        ),
        "magisk --install-module",
    ),
    KERNELSU(
        "KernelSU", "me.weishu.kernelsu", "https://github.com/tiann/KernelSU/releases",
        listOf(
            "Open KernelSU on the target and tap Install.",
            "Choose Select a boot image (LKM mode) and pick the pushed boot image.",
            "Tap Install; the patched image is written to Download as kernelsu_patched_*.img.",
        ),
        "ksud module install",
    ),
    APATCH(
        "APatch", "me.bmax.apatch", "https://github.com/bmax121/APatch/releases",
        listOf(
            "Open APatch on the target and tap the Patch (rocket) button.",
            "Select the pushed boot image and set a SuperKey you will remember.",
            "Start patching; the result is written to Download as apatch_patched_*.img.",
        ),
        "apd module install",
    ),

    /**
     * Kitsune Mask (Magisk Delta), a Magisk fork. [UNSUPPORTED]: the package name was supplied by the
     * owner; the upstream repository appears gone, so there is no homepage, and the patched-file name and
     * module command are assumed to match Magisk and are unverified for this fork.
     */
    KITSUNE(
        "Kitsune Mask", "io.github.huskydg.magisk", null,
        listOf(
            "Open Kitsune Mask on the target and use its Install option to patch a file, as in Magisk.",
            "Pick the boot image RootForge pushed to Download.",
            "When it finishes, fetch the patched image. RootForge looks for magisk_patched*.img in Download, which is unverified for this fork.",
        ),
        "magisk --install-module",
        caveat = "[UNSUPPORTED] Kitsune Mask is an unmaintained Magisk fork. RootForge has no trusted download link for it, " +
            "and its patched-file name and module command are assumed to match Magisk. Prefer Magisk, KernelSU or APatch.",
    );

    /** Where the manager leaves the patched image, as a filename prefix under /sdcard/Download. */
    val patchedPrefix: String
        get() = when (this) {
            MAGISK -> "magisk_patched"
            KERNELSU -> "kernelsu_patched"
            APATCH -> "apatch_patched"
            KITSUNE -> "magisk_patched"
        }

    /**
     * Binary invoked as root to install a zip. Kitsune is assumed Magisk-like
     * and unverified. KernelSU/APatch live under `/data/adb` and are often
     * not on PATH.
     */
    val moduleBinary: String
        get() = when (this) {
            MAGISK, KITSUNE -> "magisk"
            KERNELSU -> "/data/adb/ksud"
            APATCH -> "/data/adb/apd"
        }

    companion object {
        /**
         * Which manager is installed / providing su. [packageNames] come from
         * PackageManager (no root). [markers] are names from `ls /data/adb`
         * (`magisk`, `ksud`, `apd`, …) when su is already granted. Markers
         * win because they are the su provider; otherwise the installed
         * package list is used. Manual override stays in the UI.
         */
        fun detect(packageNames: Collection<String>, markers: Collection<String> = emptyList()): RootDetection {
            val pkgs = packageNames.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val installed = entries.filter { it.packageName in pkgs }
            val marks = markers.map { it.trim().trimStart('/').lowercase() }.filter { it.isNotEmpty() }.toSet()
            val byMarker = listOf(KERNELSU, APATCH, MAGISK).firstOrNull { markersIndicate(it, marks) }
            val preferred = when {
                byMarker == KERNELSU -> KERNELSU
                byMarker == APATCH -> APATCH
                byMarker == MAGISK && MAGISK in installed -> MAGISK
                byMarker == MAGISK && KITSUNE in installed && MAGISK !in installed -> KITSUNE
                byMarker == MAGISK && installed.isEmpty() -> MAGISK
                installed.size == 1 -> installed.single()
                MAGISK in installed -> MAGISK
                KERNELSU in installed -> KERNELSU
                APATCH in installed -> APATCH
                KITSUNE in installed -> KITSUNE
                else -> null
            }
            val reason = when {
                preferred == null ->
                    "No Magisk, KernelSU, APatch or Kitsune Mask package was found on this phone."
                byMarker != null && (preferred == byMarker || (preferred == KITSUNE && byMarker == MAGISK)) ->
                    "Detected ${preferred.label} from /data/adb (${preferred.moduleBinary}). Override if this is wrong."
                installed.size == 1 ->
                    "Only ${preferred.label} is installed (${preferred.packageName}). Override if this is wrong."
                else ->
                    "${preferred.label} is installed. Multiple managers are present; override if this is wrong."
            }
            return RootDetection(installed, preferred, reason)
        }

        fun markersIndicate(manager: RootManager, markers: Collection<String>): Boolean {
            val marks = markers.map { it.trim().trimStart('/').lowercase() }
            return when (manager) {
                KERNELSU -> marks.any { it == "ksud" || it == "ksu" }
                APATCH -> marks.any { it == "apd" || it == "ap" }
                MAGISK, KITSUNE -> marks.any { it == "magisk" }
            }
        }
    }
}

/** Result of [RootManager.detect]. [preferred] is null when nothing was found. */
data class RootDetection(
    val installed: List<RootManager>,
    val preferred: RootManager?,
    val reason: String,
)

object RootPaths {
    const val DOWNLOAD_DIR = "/sdcard/Download"
    const val STAGING_NAME = "rootforge_boot.img"

    fun stagedBootPath() = "$DOWNLOAD_DIR/$STAGING_NAME"
}
