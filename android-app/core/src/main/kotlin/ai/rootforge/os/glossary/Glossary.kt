package ai.rootforge.os.glossary

data class GlossaryEntry(val term: String, val line: String)

/**
 * Everyday-words glossary. Shown in-app and copied into `docs/MANUAL.md`.
 * One short line per term; nothing here is a how-to or a download.
 */
object Glossary {
    val all: List<GlossaryEntry> = listOf(
        GlossaryEntry("Root", "Extra control over the phone so some apps can do things a normal app is not allowed to."),
        GlossaryEntry("su", "The small program that actually runs a command with that extra control. RootForge asks you before it uses it."),
        GlossaryEntry("Magisk", "A common way to get root by patching the boot image, plus an app that installs modules."),
        GlossaryEntry("KernelSU", "Root that lives in the phone's Linux kernel, with its own app."),
        GlossaryEntry("KernelSU Next", "A separate KernelSU-family project with its own manager, not the same app as KernelSU."),
        GlossaryEntry("APatch", "Another way to get root by patching the kernel, with its own app and a SuperKey you choose."),
        GlossaryEntry("Module", "A zip (or, for LSPosed, an APK) that changes the system after the phone is rooted. It runs with high privilege."),
        GlossaryEntry("Zygisk", "Magisk's built-in way of slipping extra code into apps as they start."),
        GlossaryEntry("Zygisk Next", "A stand-alone Zygisk that also works with KernelSU and APatch (Dr-TSNG). Later releases are not open source."),
        GlossaryEntry("ReZygisk", "An open-source fork of Zygisk Next. It is not an official successor."),
        GlossaryEntry("Xposed", "An older way to hook app code. People use LSPosed or Vector for that job now."),
        GlossaryEntry("LSPosed", "Today's Xposed-style framework. Its modules are APKs you enable inside LSPosed, not Magisk zips."),
        GlossaryEntry("Vector", "JingMatrix's maintained successor to LSPosed."),
        GlossaryEntry("Shizuku", "Lets apps use a few system APIs without full root, using wireless debugging or root."),
        GlossaryEntry("Sui", "Shizuku packaged as a Magisk module, for a phone that is already rooted."),
        GlossaryEntry("Dhizuku", "Shares Device Owner powers with other apps, in a Shizuku-like way."),
        GlossaryEntry("ADB", "Android Debug Bridge: a USB or Wi-Fi link so a computer or another phone can talk to this one."),
        GlossaryEntry("fastboot", "A USB mode in the bootloader for writing partitions before Android starts."),
        GlossaryEntry("EDL", "Emergency Download (Qualcomm 9008): a very low-level USB mode. The MSM / EDL guide explains it and links official pages; a retail phone only accepts its maker's programmer."),
        GlossaryEntry("Qualcomm 9008", "The USB id of a phone in Qualcomm EDL. RootForge identifies it and does not write from the MSM / EDL guide."),
        GlossaryEntry("MSM Download Tool", "An OEM Windows program that restores some Qualcomm phones over EDL. RootForge never ships it or any firmware; it only links the maker's official page."),
        GlossaryEntry("Serial", "The USB id of a phone. RootForge shows only the last four characters in the app and in logs."),
        GlossaryEntry("SoC", "The main chip inside the phone (the processor and related parts)."),
        GlossaryEntry("Slot", "On many phones, two copies of the system (A and B) so an update can roll back."),
        GlossaryEntry("USB mode", "Whether the cable is talking ADB, fastboot, or EDL right now."),
        GlossaryEntry("Download mode", "Samsung's USB mode for its PC flashing tool, not the same as Google fastboot."),
        GlossaryEntry("Odin", "Samsung's PC program for Download Mode. RootForge explains it and does not run it or flash a tar."),
        GlossaryEntry("Knox", "Samsung's tamper flag. Unlocking or unofficial images usually trip it for good."),
        GlossaryEntry("Mi Unlock", "Xiaomi's official unlock process. After you bind an account you often wait days; RootForge cannot skip that wait."),
        GlossaryEntry("Unlock portal", "The maker's official webpage for a bootloader unlock code. RootForge only opens it in the browser and never sends phone data there."),
        GlossaryEntry("ESP32 helper", "A small Espressif board used here only as a USB-serial bridge for phones you own. Not a screen-lock or FRP tool."),
        GlossaryEntry("Raspberry Pi helper", "A Pi is a Linux computer running the same host adb/fastboot path as the desktop app, for phones you own."),
        GlossaryEntry("Bootloader", "The tiny program that starts the phone before Android. Unlocking it usually wipes the phone."),
        GlossaryEntry("Recovery", "A small system you boot instead of Android to install updates or wipe data."),
        GlossaryEntry("TWRP", "Team Win Recovery Project: a well-known custom recovery."),
        GlossaryEntry("PitchBlack", "PitchBlack Recovery Project (PBRP): another custom recovery, based on TWRP."),
        GlossaryEntry("Boot image", "The boot.img or init_boot.img file that starts Android. Magisk patches this file."),
        GlossaryEntry("ROM", "The Android system the phone runs: the maker's build, or a custom one."),
        GlossaryEntry("Flash", "Write an image file onto a partition. Easy to brick a phone; RootForge asks you to type a confirmation."),
        GlossaryEntry("Dry run", "Show the command and change nothing. Dangerous actions in this app default to this."),
        GlossaryEntry("SHA-256 / checksum", "A fingerprint of a file's bytes. If it matches the published value, you have the file you expected."),
        GlossaryEntry("Bootloop", "The phone starts, then restarts, forever. Often from a bad flash or a bad module."),
    )
}
