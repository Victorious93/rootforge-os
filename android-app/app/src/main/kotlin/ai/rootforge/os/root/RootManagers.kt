package ai.rootforge.os.root

/**
 * The three root solutions RootForge drives. Patching is done by the manager
 * app on the target device; RootForge does not bundle or re-implement it.
 */
enum class RootManager(
    val label: String,
    val packageName: String,
    val homepage: String,
    val patchSteps: List<String>,
    val moduleInstallCommand: String,
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
    );

    /** Where the manager leaves the patched image, as a filename prefix under /sdcard/Download. */
    val patchedPrefix: String
        get() = when (this) {
            MAGISK -> "magisk_patched"
            KERNELSU -> "kernelsu_patched"
            APATCH -> "apatch_patched"
        }
}

object RootPaths {
    const val DOWNLOAD_DIR = "/sdcard/Download"
    const val STAGING_NAME = "rootforge_boot.img"

    fun stagedBootPath() = "$DOWNLOAD_DIR/$STAGING_NAME"
}
