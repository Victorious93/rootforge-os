package ai.rootforge.os.ask

/**
 * One curated answer. [keywords] are matched as lowercase substrings of the question, so a stem such as
 * `unlock` also catches `unlocking`. [guide] lists Root Guide page ids (`guide:<id>`) worth reading next.
 */
data class AskTopic(
    val id: String,
    val title: String,
    val keywords: List<String>,
    val answer: String,
    val commands: List<String> = emptyList(),
    val guide: List<String> = emptyList(),
    /** What is device- or version-dependent about the answer; shown so it is not read as universal. */
    val caveat: String? = null,
)

/**
 * Hand-written ADB, fastboot and rooting answers. These are general Android Platform Tools behaviour as
 * the authors understand it; RootForge has not re-verified every line on every device, and each entry
 * says where it varies. Nothing here is generated at run time.
 */
object AskTopics {
    val all: List<AskTopic> = listOf(
        AskTopic(
            id = "adb-no-device",
            title = "adb devices shows nothing",
            keywords = listOf("no device", "not detected", "not showing", "not found", "doesn't show", "doesnt show", "empty", "not recognized", "not recognised", "adb devices", "cannot connect", "can't connect"),
            answer = "Work down the list: use a data cable (many charging cables carry no data), turn on Developer options → USB debugging, " +
                "set the USB mode to File transfer, unplug and replug, then accept the \"Allow USB debugging?\" prompt on the phone. " +
                "If the list is still empty restart the server. On Windows the OEM or Google USB driver must be installed; on Linux a missing udev rule is the usual cause.",
            commands = listOf("adb kill-server", "adb start-server", "adb devices"),
            guide = listOf("troubleshooting"),
        ),
        AskTopic(
            id = "adb-unauthorized",
            title = "Device shows \"unauthorized\"",
            keywords = listOf("unauthorized", "unauthorised", "allow usb debugging", "rsa", "authorization"),
            answer = "The phone has not accepted this computer's key. Unlock the screen and look for the \"Allow USB debugging?\" prompt. " +
                "If it never appears, Developer options → Revoke USB debugging authorizations, then replug. " +
                "Tick \"Always allow\" only on a computer you own.",
            commands = listOf("adb kill-server", "adb devices"),
        ),
        AskTopic(
            id = "enable-usb-debugging",
            title = "Turn on Developer options and USB debugging",
            keywords = listOf("developer option", "usb debugging", "enable debugging", "build number", "oem unlocking"),
            answer = "Settings → About phone → tap Build number seven times (on some skins it is under Software information). " +
                "Then Settings → System (or Additional settings) → Developer options: enable USB debugging, and OEM unlocking if you plan to unlock the bootloader. " +
                "OEM unlocking can be greyed out until the phone has been online for a while or, on some carriers' phones, can be locked entirely.",
            guide = listOf("unlock-bootloader"),
            caveat = "Menu names differ by manufacturer.",
        ),
        AskTopic(
            id = "reboot-modes",
            title = "Reboot to bootloader, recovery or system",
            keywords = listOf("reboot", "bootloader mode", "fastboot mode", "recovery mode", "enter recovery", "enter bootloader", "download mode", "restart into"),
            answer = "From a running phone with USB debugging on, adb can reboot straight into a mode. From fastboot, use fastboot to leave. " +
                "Samsung phones use Download (Odin) mode rather than fastboot; it is entered with a key combination, not an adb command that works everywhere.",
            commands = listOf("adb reboot bootloader", "adb reboot recovery", "adb reboot", "fastboot reboot", "fastboot reboot bootloader"),
            guide = listOf("samsung"),
            caveat = "Some devices ignore `adb reboot recovery`; a key combination is then needed.",
        ),
        AskTopic(
            id = "fastboot-no-device",
            title = "fastboot devices shows nothing",
            keywords = listOf("fastboot devices", "fastboot not", "waiting for device", "fastboot no", "fastboot empty"),
            answer = "The phone must actually be in bootloader (fastboot) mode, not recovery or Android. Use a data cable, preferably a direct port rather than a hub. " +
                "On Windows fastboot needs its own driver (Device Manager shows \"Android Bootloader Interface\" when it is right). " +
                "On Linux run it with the proper udev rule or as root.",
            commands = listOf("fastboot devices", "fastboot getvar all"),
            guide = listOf("troubleshooting"),
        ),
        AskTopic(
            id = "unlock-bootloader",
            title = "Unlock the bootloader",
            keywords = listOf("unlock", "bootloader unlock", "oem unlock", "flashing unlock", "unlocking bootloader"),
            answer = "Unlocking erases all user data and weakens the device's verified-boot guarantees. Back up first. " +
                "Enable OEM unlocking in Developer options, boot to the bootloader and run the unlock command, then confirm on the phone with the volume and power keys. " +
                "Xiaomi, Samsung, OnePlus, Motorola and others add their own requirements (waiting periods, accounts, tokens, carrier locks), so read the page for your brand.",
            commands = listOf("fastboot flashing unlock", "fastboot oem unlock"),
            guide = listOf("unlock-bootloader", "xiaomi", "samsung", "pixel", "oneplus", "motorola"),
            caveat = "`flashing unlock` is the modern form; `oem unlock` is for older devices. Some phones cannot be unlocked at all.",
        ),
        AskTopic(
            id = "root-how",
            title = "How do I root my phone?",
            keywords = listOf("root my", "how to root", "how do i root", "rooting", "get root", "become root", "root the phone", "root android"),
            answer = "The usual path is: unlock the bootloader, take the boot image (or init_boot on newer devices) from the exact firmware you are running, " +
                "patch it with a root manager (Magisk, KernelSU or APatch), and flash the patched image back with fastboot. " +
                "RootForge's Root / Patching screen follows that flow with a device-match check before any write. " +
                "Pick the framework first: they differ in module support and in how well they hide from apps.",
            guide = listOf("rooting-overview", "rootforge-workflow", "framework-comparison", "magisk", "kernelsu", "apatch"),
            caveat = "Rooting can void warranty, break banking or DRM apps and, if the wrong image is flashed, leave the phone unbootable.",
        ),
        AskTopic(
            id = "patch-boot-flash",
            title = "Flash a patched boot image",
            keywords = listOf("patched", "magisk_patched", "flash boot", "init_boot", "boot.img", "boot image", "flash the patched", "patch boot"),
            answer = "Flash the patched image to the partition it came from. Devices that launched with Android 13 or later commonly keep the ramdisk in init_boot, " +
                "so the image you patch and flash is init_boot.img, not boot.img; older ones use boot.img. The image must be extracted from the same build that is installed. " +
                "On A/B devices, check the active slot first.",
            commands = listOf("fastboot getvar current-slot", "fastboot flash boot magisk_patched.img", "fastboot flash init_boot magisk_patched.img", "fastboot reboot"),
            guide = listOf("magisk", "rootforge-workflow", "troubleshooting"),
            caveat = "Which partition applies is device-specific; flashing the wrong one can bootloop. RootForge refuses a write when product, slot or fingerprint do not match.",
        ),
        AskTopic(
            id = "boot-once",
            title = "Boot an image without flashing it",
            keywords = listOf("boot once", "fastboot boot", "temporary", "without flashing", "try before", "tethered"),
            answer = "`fastboot boot` loads an image into RAM for one boot and leaves flash untouched, which is the safest way to try a patched image or a recovery. " +
                "Not every bootloader supports it, and on some phones the result is not fully functional.",
            commands = listOf("fastboot boot patched.img"),
            guide = listOf("temporary-root", "custom-recovery"),
            caveat = "Unsupported on some devices (the command fails with \"unknown command\" or similar).",
        ),
        AskTopic(
            id = "ab-slots",
            title = "A/B slots",
            keywords = listOf("slot", "a/b", "current-slot", "active slot", "slot a", "slot b", "set_active"),
            answer = "A/B devices keep two copies of the system partitions. Read the active slot before flashing; images flashed to the inactive slot do not take effect until it is made active. " +
                "Switching slots can boot an older or mismatched build, so do it only on purpose.",
            commands = listOf("fastboot getvar current-slot", "fastboot --set-active=a", "fastboot --set-active=b"),
            guide = listOf("rootforge-workflow"),
        ),
        AskTopic(
            id = "check-root",
            title = "Is my phone rooted?",
            keywords = listOf("am i rooted", "is it rooted", "check root", "su not found", "root access", "verify root", "rooted yet", "uid=0"),
            answer = "Run `su -c id` from an adb shell. The phone's root manager will ask to grant the shell; once granted, the output should contain uid=0. " +
                "`su: inaccessible or not found` means no su binary is available to the shell. " +
                "A root manager app being installed does not by itself prove the phone is rooted.",
            commands = listOf("adb shell", "su -c id"),
            guide = listOf("troubleshooting"),
        ),
        AskTopic(
            id = "install-apk",
            title = "Install, push and pull files over ADB",
            keywords = listOf("install apk", "adb install", "sideload apk", "push", "pull", "copy file", "transfer file", "install app"),
            answer = "`adb install` installs an APK, `-r` replaces an existing app and keeps its data, `-d` allows a downgrade. " +
                "`adb push` and `adb pull` copy files; /sdcard is shared storage. RootForge's ADB screen offers the same operations.",
            commands = listOf("adb install -r app.apk", "adb push local.file /sdcard/Download/", "adb pull /sdcard/Download/file ."),
        ),
        AskTopic(
            id = "sideload-ota",
            title = "Sideload an OTA or recovery zip",
            keywords = listOf("sideload", "ota", "update zip", "install zip", "flash zip", "apply update from adb"),
            answer = "On the phone choose Apply update → Apply from ADB (the wording varies) in recovery, then send the zip from the computer. " +
                "A stock OTA must match the installed build, and an update applied over a rooted boot image removes root until it is re-patched.",
            commands = listOf("adb sideload update.zip"),
            guide = listOf("custom-recovery", "stop-auto-updates"),
            caveat = "Recovery must be running in sideload mode, not Android.",
        ),
        AskTopic(
            id = "wireless-adb",
            title = "ADB over Wi-Fi",
            keywords = listOf("wireless", "wifi", "wi-fi", "adb pair", "adb connect", "tcpip", "without cable", "over network"),
            answer = "On Android 11 and later: Developer options → Wireless debugging → Pair device with pairing code, then pair and connect from the computer using the addresses the phone shows. " +
                "The pairing port and the connection port are different. Older versions need a USB cable once to switch the daemon to TCP. " +
                "Only use it on a network you trust.",
            commands = listOf("adb pair <ip>:<pairing-port>", "adb connect <ip>:<port>", "adb tcpip 5555"),
        ),
        AskTopic(
            id = "logcat",
            title = "Read logs",
            keywords = listOf("logcat", "log ", "logs", "crash", "dmesg", "debug log", "why did it"),
            answer = "`logcat -d` dumps the buffer and exits; without `-d` it streams until stopped. After a bootloop, `adb logcat` may work early in boot, and the previous boot's kernel log is sometimes in /proc/last_kmsg or pstore.",
            commands = listOf("adb logcat -d", "adb logcat -b crash", "adb shell dmesg"),
            guide = listOf("troubleshooting"),
        ),
        AskTopic(
            id = "bootloop",
            title = "Stuck in a bootloop after rooting or flashing",
            keywords = listOf("bootloop", "boot loop", "stuck on logo", "won't boot", "wont boot", "soft brick", "brick", "bricked", "keeps restarting", "boot animation"),
            answer = "Stay calm and do not flash further images blindly. First, boot to the bootloader and see whether fastboot still responds; if so the phone is almost certainly recoverable. " +
                "Then restore the stock boot (or init_boot) image from the exact firmware you were running. If the loop began after installing a module, most root managers can disable modules from a safe mode or recovery; the method differs per manager.",
            commands = listOf("fastboot flash boot stock_boot.img", "fastboot flash init_boot stock_init_boot.img"),
            guide = listOf("troubleshooting", "rootforge-workflow"),
            caveat = "A hard brick (no fastboot, no recovery, no download mode) usually needs the maker's low-level tools (e.g. EDL).",
        ),
        AskTopic(
            id = "integrity",
            title = "Banking apps or Play Integrity fail on a rooted phone",
            keywords = listOf("play integrity", "safetynet", "banking", "bank app", "wallet", "gpay", "google pay", "root detection", "hide root", "denylist", "zygisk"),
            answer = "Apps can detect an unlocked bootloader and root through attestation, and stronger checks cannot always be defeated. " +
                "The guide covers the common mitigations and their limits; there is no guaranteed fix, and workarounds change as the checks change. " +
                "If the app is essential, consider whether root is worth it on that device.",
            guide = listOf("troubleshooting", "non-root-alternatives", "magisk"),
            caveat = "Fast-moving topic; the guide may lag current detection methods.",
        ),
        AskTopic(
            id = "locked-bootloader",
            title = "Root with a locked bootloader",
            keywords = listOf("locked bootloader", "bootloader locked", "cannot unlock", "can't unlock", "without unlocking", "no unlock"),
            answer = "On a locked bootloader fastboot refuses to flash a patched image, so ordinary rooting does not apply. " +
                "Some specific phones have exploit-based routes, which are device- and version-specific and carry real risk. Check whether your model can be unlocked first.",
            guide = listOf("root-locked-bootloader", "unlock-bootloader"),
        ),
        AskTopic(
            id = "relock",
            title = "Re-lock the bootloader",
            keywords = listOf("relock", "re-lock", "lock bootloader", "flashing lock", "oem lock"),
            answer = "Locking also wipes the phone, and locking while the installed images are not the manufacturer's signed ones can leave the phone unable to boot. " +
                "Flash the full stock firmware first, and only then lock.",
            commands = listOf("fastboot flashing lock", "fastboot oem lock"),
            guide = listOf("unlock-bootloader", "troubleshooting"),
            caveat = "Do not lock a bootloader on a phone running a custom ROM or a patched boot image.",
        ),
        AskTopic(
            id = "esp32-pi-helpers",
            title = "ESP32 or Raspberry Pi as an unlock helper",
            keywords = listOf("esp32", "raspberry pi", "rpi helper", "pi host", "usb serial bridge", "usb-serial"),
            answer = "A Raspberry Pi is a Linux computer: install adb/fastboot (or run the desktop client) and use the same dry-run, typed-confirm, backup-first, no-auto-reboot path as a PC. " +
                "An ESP32-S3 can be Espressif's USB Serial/JTAG CDC bridge so you have a serial console. " +
                "Open Links → ESP32 & Pi helpers for official https pages. Wait until the phone enumerates in fastboot before Unlock; the volume-key confirm on the phone is yours.",
            caveat = "These helpers are for phones you own. They do not bypass a screen lock or Factory Reset Protection.",
        ),
        AskTopic(
            id = "msm-edl-guide",
            title = "MSM Download Tool and Qualcomm EDL",
            keywords = listOf("msm", "edl", "9008", "qualcomm 9008", "download tool", "unbrick", "edl restore", "firehose", "qfil"),
            answer = "Qualcomm EDL (USB 05c6:9008) is a low-level restore port. MSM Download Tool is an OEM Windows program (often OnePlus, Oppo, realme) that writes factory firmware over that port. " +
                "Open Devices or MSM / EDL when the phone enumerates as 9008. RootForge only identifies the chip and opens the maker's official https support/firmware page. " +
                "It never ships MSM, QFIL, Rescue and Smart Assistant, Mi Flash, or firmware, and the guide never writes.",
            caveat = "An EDL restore that wipes Factory Reset Protection or a screen lock is refused. Use the maker's official account-recovery flow.",
        ),
        AskTopic(
            id = "frp-screen-lock-refused",
            title = "Screen lock and FRP are not supported",
            keywords = listOf("frp", "factory reset protection", "google account lock", "bypass screen lock", "remove screen lock", "unlock pattern", "forgot pin", "crack pin"),
            answer = "RootForge will not help bypass a screen lock or Factory Reset Protection. Those locks mean this app must not flash or unlock the phone. " +
                "Use the maker's official account-recovery flow. Bootloader unlock and flashing here are only for devices you already own, after OEM unlocking.",
        ),
        AskTopic(
            id = "kernelsu-apatch",
            title = "KernelSU, APatch or Magisk: which one?",
            keywords = listOf("kernelsu", "apatch", "magisk or", "which root", "best root", "difference between"),
            answer = "Magisk patches the boot image's ramdisk and is the most widely documented. KernelSU needs a kernel with support (a GKI kernel or a custom build) and its own manager. " +
                "APatch patches the kernel image directly. Which works depends on the device and kernel version; the framework comparison page lays out the trade-offs.",
            guide = listOf("framework-comparison", "magisk", "kernelsu", "apatch"),
        ),
    )
}
