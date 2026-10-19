# Rooting with RootForge OS

The guides in this section were written for a person sitting at a computer with `adb` and `fastboot`. RootForge OS lets a **host phone** do those jobs for a **target phone** over a USB-C cable, so most steps map onto a screen in this app. This page says which one, and where the app stops and you have to do something yourself.

::: warning Read this first
Unlocking a bootloader erases the target and can void its warranty. A bad flash can leave it unbootable. Back up first, use the guide for your exact model, and read the [Legal Disclaimer](guide:legal-disclaimer). This page was written by the RootForge OS project, not by the guide's upstream authors; it describes only what the app does.
:::

## Before you start

- You need a **USB-C to USB-C data cable** (charge-only cables show nothing). Plug the cable between the host and the target. See *Dashboard → Host-to-target cable setup* for the checklist.
- On the target, turn on Developer options and USB debugging, and accept the RSA prompt when it appears.
- Get the **stock boot image for the target's exact build** (`boot.img` or `init_boot.img`). The device guides explain where it comes from. Import it with *Import file* on the Dashboard; every screen then offers it from a picker.
- Charge both phones. The target may draw power from the host and drain it.

## Which screen does what

| Step in the guides | RootForge OS screen | Notes |
| :--- | :--- | :--- |
| `adb devices`, `getprop`, reboot to bootloader or recovery | **Dashboard**, **ADB** | Connect, then use *Reboot target*. |
| `fastboot getvar all` | **Fastboot → Variables** | Shows bootloader state and the active slot. |
| `fastboot flashing unlock` | **Fastboot → Bootloader lock** | Erases all data. Asks you to type `UNLOCK`. |
| Inspect a boot image, push it, patch with Magisk, KernelSU or APatch | **Root / Patching** steps 1 to 3 | The official manager app on the target does the patching. RootForge pushes the image, opens the manager and pulls the patched file back. |
| `fastboot boot patched.img` | **Root / Patching** or **Fastboot → Boot once** | Try this before a permanent write. |
| `fastboot flash boot patched.img` | **Root / Patching** step 4, **Fastboot → Flash partition** | Prints a plan and asks for a typed confirmation. Needs a matching `.manifest.json` beside the image (see below). |
| Switch A/B slot | **Fastboot → Active slot** | Asks you to type the slot. |
| Boot or flash TWRP, OrangeFox, PitchBlack or another recovery | **Recovery** | Also enters recovery from Android or from the bootloader. |
| `adb sideload update.zip` | **ADB → Sideload** | Target must be in recovery sideload mode. |
| Build your own Magisk-style module zip | **Modules → Create** | Optional lifecycle files, your own script text, hedged per-manager notes. Pushes and installs it on the target when ADB is ready. |
| Find and install a module from a repository | **Modules → Repositories** | Links only: you download in your browser, import the zip, and install it. Installing any module needs a typed `INSTALL MODULE` confirmation that shows the zip's SHA-256. |
| Qualcomm 9008 (EDL) | **EDL** | Experimental. Retail phones accept only the manufacturer's signed programmer, which this app does not ship. |

## The flash safety gate

Every flash button in the app refuses an image that has no sidecar file named `<image>.manifest.json` declaring which device it is for. If the manifest does not match the connected target (product, slot, and fingerprint or a declared claim), the app logs `Refused:` and writes nothing. This is deliberate and there is no setting that skips it. Writes also print a plan and require a typed phrase. See *Flash Tools* and `docs/FLASH_TOOLS_PLAN.md` in the project.

## A typical Magisk run

1. Read the guide for your device and the [Magisk guide](guide:magisk). Back up the target.
2. Connect the target and check *Dashboard → Device information*. Confirm the model and build match the boot image you have.
3. If the bootloader is locked, follow [Unlock the bootloader](guide:unlock-bootloader) for your brand first. Some brands need an account, a waiting period or a code from the manufacturer; the app cannot do that part. Unlocking wipes the target.
4. *Root / Patching*: choose Magisk, import the boot image, and use *Inspect header* to confirm it is a valid image.
5. Install the Magisk APK on the target, push the boot image, open Magisk, and patch it there. Then *Fetch patched image*.
6. Reboot to the bootloader. Use **Boot once** first. If the target boots and Magisk shows root, flash the same image to the same partition the stock image came from (`init_boot` on most devices launched with Android 13 or newer, `boot` on older ones).
7. Set up root hiding, Play Integrity and modules with [Troubleshooting](guide:troubleshooting) and the [Magisk guide](guide:magisk).

## Shells and terminals

- **ADB shell:** *ADB → Shell* runs one command on the target and shows the output. The **Logs / Terminal** panel takes the same thing as `adb shell <command>`, plus `adb reboot`, `fastboot getvar`, `devices`, `clear` and `help`. These are one command at a time. There is no interactive shell session, so programs that wait for input (`su`, `vi`, `top`) will not work.
- **Anything that writes** (flash, erase, unlock, lock, slot) is routed to the same confirmed path as the buttons. Typing a command never skips the typed confirmation.
- **A full terminal:** for anything the one-command shell cannot do, use a Termux-based terminal app such as VictorSuite (a separate app in the same fleet; Termux and PRoot work belongs there). On Android, *ADB → Shell* offers **Open full terminal**, which launches Termux (`com.termux`) by default. If your terminal app has another package name (shown in Android's app info), change it in *Settings → Terminal app*; clear the field to turn the button off. RootForge only launches the app. It passes no command, serial or file, so you type the commands there yourself. The desktop client has no hand-off. This launch has not been run on a phone.

## What the app does not do

- It does not bundle or reimplement Magisk, KernelSU or APatch, and it does not patch images itself.
- It does not flash `vbmeta` with verification disabled. Some devices need that; do it from a computer or with Fastboot → Flash partition and a matching manifest.
- It does not drive Samsung Odin or Download mode, Xiaomi's unlock tool or other vendor-specific flashers. Use the Samsung and Xiaomi guides with the vendor's tools.
- It does not hide root or fix Play Integrity. Those are modules and settings covered in the guides.
- The app has not been run against a physical host and target pair as of this writing. Treat the first run as a test and keep a way to recover the target.
