<p align="center">
  <img src="images/manual/header.png" alt="RootForge OS how-to manual — emblem with a neon cyan-to-magenta ring" width="720">
</p>

# RootForge OS how-to manual

This is the full how-to for RootForge OS. It is written in everyday language. When a technical term is needed, it is explained in one short line the first time it appears.

The same text is bundled inside the Android app and the desktop client: open the drawer and tap **Help**, or open **Settings** and tap **Open the manual**. Everyday-words definitions are in [Glossary](#glossary) and in the in-app **Glossary** screen (from Help and Links). A live copy also lives on GitHub: <https://github.com/Victorious93/rootforge-os/blob/main/docs/MANUAL.md>

::: warning Honest status
The code and tests exist. **None of this has been run end to end on a real phone, a real computer-and-phone pair, or a booted live disc.** Treat every flash, unlock and write as something that can brick a phone. Keep a known-good backup image before you write anything.
:::

**Contents**

1. [What RootForge OS is, and its place in the fleet](#what-rootforge-os-is-and-its-place-in-the-fleet)
2. [Install and first-run setup](#install-and-first-run-setup)
3. [Every feature, step by step](#every-feature-step-by-step)
4. [Every permission, and why](#every-permission-and-why)
5. [Fleet integration](#fleet-integration)
6. [Troubleshooting and FAQ](#troubleshooting-and-faq)
7. [Privacy and data handling](#privacy-and-data-handling)
8. [Glossary](#glossary)

---

## What RootForge OS is, and its place in the fleet

RootForge OS is a toolbox for people who work on Android phones that they own and are allowed to change. It helps you:

- talk to a second phone over a USB cable
- flash images (write a file onto a named storage area of the phone)
- build small add-on packages called **root modules**
- look up community rooting notes offline

**Rooting** means getting administrator-level access to the phone's system. A **root manager** (Magisk, KernelSU, APatch, or Kitsune) is the app that keeps that access after you reboot.

RootForge is **not** one unlock button for every brand. If it cannot tell which phone it is talking to, it stops.

### The four ways you can use it

| Way | What you get |
|---|---|
| **Host-phone app** | An Android app on one phone (the *host*) that controls a second phone (the *target*) over USB |
| **Desktop app** | The same screens on a Linux or Windows computer, talking through that computer's `adb` and `fastboot` programs |
| **Command-line tool** | The `rootforge` program, from a git checkout on Linux or Termux |
| **Docker toolkit** | The command-line tool plus `adb`/`fastboot` in a container |

A Debian live disc (an ISO) is in the repository but has **not** been built or booted here, and no prebuilt disc is published.

### Words used in this manual

| Term | Meaning |
|---|---|
| **Host** | The phone or computer that is running RootForge |
| **Target** | The phone you are working on, plugged into the host |
| **ADB** | Android Debug Bridge — the usual way a computer talks to a running Android phone |
| **Fastboot** | The phone's bootloader mode, used to write partitions and unlock the bootloader |
| **Bootloader** | The small program that starts the phone before Android |
| **Partition** | A named slice of the phone's storage, such as `boot` or `recovery` |
| **EDL** | Emergency Download, a low-level Qualcomm recovery mode (USB id 9008) |
| **Sideload** | Sending a zip to a phone that is already in recovery's "apply update from ADB" mode |
| **USB Host / OTG** | Lets a phone act as the computer end of a USB cable |
| **Typed confirmation** | You must type an exact phrase (for example `FLASH boot`) before a write happens |
| **Sidecar manifest** | A small `something.manifest.json` file next to an image that says which phone it is for |
| **SHA-256** | A fingerprint of a file's bytes. If it does not match, the file is not the one you thought |
| **Messenger bridge** | Android's simple message pipe between two apps on the same phone |
| **Node id** | This install's identity, `rf-` plus 32 hex characters, generated once |

A fuller list of Android-root terms, in the same everyday words as the in-app **Glossary** screen, is in [Glossary](#glossary).

### Fleet role: EXTERNAL_NODE

RootForge belongs to the Victorious set of Android tools. The controller app is **droidcommand-AI** (DCA, <https://github.com/Victorious93/droidcommand-AI>).

DCA sorts known apps into classes. RootForge is an **`EXTERNAL_NODE`**: an independent product that DCA *may* talk to, not a companion that dies if DCA is missing.

What that means for you:

- **RootForge works without DCA.** Never pairing, or uninstalling DCA, turns off no RootForge feature.
- **They talk over a Messenger bridge.** On the phone, DCA binds to RootForge's service (`ai.rootforge.os.action.BRIDGE`) and sends one JSON line per request. RootForge answers with one JSON line.
- **JSON protocol major 1.** Every request includes `protocol_major: 1`. A different number is rejected, not guessed.
- **Pairing is package + certificate.** RootForge trusts a controller by its Android **package name** plus the **SHA-256 of its signing certificate**. That identity comes from Android, never from a field the caller writes into the JSON. If the controller is rebuilt with a new signing key, it must be paired again.
- **Remote control is off by default.** Settings → External control → **Allow external controllers** starts **off**.

A live pairing of DCA and RootForge on one phone has **not** been run. DCA's current code has an SSH transport for RootForge, not yet an Android Messenger client. The steps below are what the RootForge side implements and unit-tests.

---

## Install and first-run setup

### What you need

- A **host** phone with USB Host hardware and Android 8.0 or newer, **or** a Linux/Windows computer with `adb` and `fastboot` installed
- A **target** phone you are allowed to change
- A USB-C **data** cable (charge-only cables show nothing). If the host does not switch into USB-host mode, put a USB-C OTG adapter on the **host** end

The published app is a **debug** build. Package name: `ai.rootforge.os.debug`. Version name: `0.1.0-debug`. There is no release-signed APK yet.

### Install the Android app

1. Download the v0.2.0 debug APK from <https://github.com/Victorious93/rootforge-os/releases/tag/v0.2.0>
2. Check the file: `sha256sum rootforge-os-android-debug.apk` should be `faf743faba1b14aa5288f69883a0a1e2c284e43a77d7b32575ea3aaed8045e2f`
3. On a computer with USB debugging on the host phone:

```bash
adb install -r rootforge-os-android-debug.apk
```

Or copy the APK onto the host phone, open it, and allow "install unknown apps" when Android asks. That on-phone install path has not been tested for this project.

### One-time uninstall if you already have v0.2.0

v0.2.0 was signed with a per-machine debug key. Later builds use one fixed committed debug key. Android will **refuse** to update over a different signature.

Uninstall **once**:

```bash
adb uninstall ai.rootforge.os.debug
```

Or uninstall from the phone's app settings. **This deletes the app's data** (paired controllers, imported files, settings). After that, newer debug builds update in place with `adb install -r`.

### First run on the host phone

There is no setup wizard. The first screen is **Dashboard**.

1. Grant **notifications** if Android 13+ asks. That is only so RootForge can tell you a controller is waiting, or that a remote command needs approval.
2. Plug the **target** into the host with a data cable.
3. On the **target**, turn on USB debugging (Settings → Developer options) and set USB mode to File transfer. Or reboot the target to the bootloader for fastboot work.
4. On the **host**, tap **Grant USB access** if asked, then **Connect**.
5. The first time ADB is used, the **target** shows an RSA key dialog. Accept it. Settings shows the fingerprint that dialog displays.

Defaults on first launch:

- Connect automatically: **on**
- Allow external controllers: **off**
- Terminal app: `com.termux` (Termux). Clear the field to turn the hand-off off.

### Pairing with the controller (droidcommand-AI)

Do this only if you want DCA (or another controller) to send commands. You can skip it forever.

1. Open RootForge → **Settings** → **External control**.
2. Copy the **Node id** (`rf-` plus 32 hex characters).
3. In the controller, bind `ai.rootforge.os.action.BRIDGE` on package `ai.rootforge.os.debug` (debug build) and send `capabilities.get` (protocol 1.1) or `rootforge.capabilities.get` (legacy) with that node id. The first reply is `not_paired`, `external_control_disabled` (1.1), or `bridge_disabled` (legacy). That is expected. The caller now appears under **Waiting for approval**.
4. Turn **Allow external controllers** **on**.
5. Tap **Pair** on that package and certificate.

Reads then run. Commands that change the target still ask you on this phone. Writes still need the same typed phrase as the on-screen buttons.

If the controller is re-signed, it appears as pending again with a "different signing certificate" note. Pair replaces the old certificate. **Revoke** forgets the package.

### Desktop, command line, Docker

**Desktop.** No prebuilt package is published. Build with Gradle (`:desktop:run`, or package a `.deb` / AppImage / `.exe`). The computer needs `adb` and `fastboot` on its PATH. On Linux, your user should be in `plugdev` and have udev rules for Android USB. On Windows you must install the Google USB driver yourself (and a Qualcomm 9008 driver for EDL). Desktop does not launch a terminal app and does not use Shizuku.

**Command line** from a git clone (Linux or Termux):

```bash
git clone https://github.com/Victorious93/rootforge-os.git
cd rootforge-os
export PYTHONPATH="$PWD/config/includes.chroot/usr/local/lib"
python3 -m rootforge.core.cli doctor
```

**Docker.** Build the image locally (`docker build -f docker/Dockerfile`). USB passthrough and a local API token are documented in `docker/README.md`. Not verified against a phone.

---

## Every feature, step by step

Open the menu (☰) to move between screens. A log panel sits at the bottom of most screens. Dangerous actions always show a dialog; if it asks you to type a phrase, type it **exactly**.

Screenshots below are Paparazzi renders with sample data. **They are not captures from a real phone.**

### Dashboard

![Dashboard](../android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_dashboard.png)

The home screen. You see attached USB devices, the connected target, a short device info card, reboot shortcuts, your imported file library, **Builds for this device**, and (Android only) **Desktop mode**. For every plugged-in phone at once, use **Devices**.

**Connect a target**

1. Plug the cable in. If the list is empty, tap **Refresh**.
2. If the row says it needs permission, tap **Grant USB access** and accept Android's prompt.
3. Tap **Connect**.
4. Use **Disconnect** when you are done.

**Import a file into the library**

1. Tap **Import file**.
2. Pick an image, zip or APK. RootForge copies it into its own library (it does not keep a live link to the original).
3. Delete a library file from the same card when you no longer want it.

**Builds for this device** (Custom RR catalog)

This card lists community ROMs, recoveries and root solutions whose catalog name matches the connected phone. It never decides whether a flash is allowed (`DeviceMatchGate` does not read it).

1. Tap **Download** to fetch the catalog over https (one of two tap-only Internet uses; the other is Custom Recovery lookup/download). Or tap **Import file** and pick a `catalog.json` / `freshness.json` you already have.
2. Open a download or forum link in the system browser if you want the file. **ROMs and root solutions stay links.** RootForge does not download the ROM itself.
3. On a matching **TWRP** or **PitchBlack** recovery row, tap **Install with Custom Recovery**. That opens the **Custom Recovery** screen already aimed at that project. Official files are looked up from the **probed** phone, not from the catalog row. Downloads still have to pass the installer https host allow-list. Dry-run stays on. A write still needs typed `FLASH RECOVERY`. OrangeFox, SHRP and RedWolf stay links.
4. **Remove** forgets the stored catalog. The last good copy is kept if a new download does not parse.

**Desktop mode** (VictorSuite, Android only)

See [Desktop mode via VictorSuite](#desktop-mode-via-victorsuite) below. The Linux/Windows desktop client hides this card.

### Devices

![Devices](../android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_devices.png)

Lists **every** phone plugged into this host at the same time (ADB, fastboot, and Qualcomm EDL 9008). Plug-in is picked up from USB attach and from a short poll of the ADB/fastboot device lists (on a computer) or the USB device list (on the Android app).

Each card is a read-only identity plus a **Root plan** for that exact phone:

1. Brand, model, codename (`ro.product.device`), SoC/platform, Android version, security patch, bootloader lock, current root manager, A/B slot, USB mode.
2. In fastboot the same fields come from `getvar` (`product`, `unlocked`, `current-slot`, and the rest the bootloader reports).
3. Serial numbers are shown as `••••` plus the last four characters, in the app and in the command log.
4. The Root plan recommends **unlock bootloader → custom recovery → root manager**. Matching Custom RR ROM/recovery/root rows for that codename are listed. A matching TWRP or PitchBlack row can open **Custom Recovery** already aimed at that project. ROMs stay links.
5. Buttons open the existing ADB, Fastboot, EDL, Root / Patching, Modules or Custom Recovery screens **for that serial**.

**EDL is identify only.** Qualcomm `05c6:9008` is recognised so you can see the chip. Devices never starts a Firehose write.

Safety is unchanged: dry-run stays the default where it already was, typed confirm still gates writes, a recovery write still backs up first, nothing reboots by itself, and each serial has its own lock so two phones can work at the same time without sharing one queue.

1. Open **Devices**.
2. Plug the phones in. Grant USB access if a row asks.
3. Tap **Identify attached** if a card is still empty.
4. Read the Root plan. Open the tool you need for that phone.

### Ask

Type a short question such as "adb devices shows nothing" or "how do I unlock the bootloader?".

Ask is **keyword matching**, not an AI. It works offline, sends nothing, and cannot see the connected phone. If nothing matches, it says so instead of guessing.

1. Open **Ask**.
2. Type a question or tap an example.
3. Read the curated answer. Tap a Root Guide hit to jump to that page.

### Root Guide

Offline rooting notes (bootloader unlock, Magisk / KernelSU / APatch, device pages, FAQ). Most pages are adapted from Awesome Android Root (MIT). They are community reference. RootForge has **not** checked them on hardware.

1. Open **Root Guide**.
2. Search, or open a section.
3. Follow internal links inside the guide. https links open in the browser.
4. Nothing here talks to the phone.

### Flash Tools

![Flash Tools](../android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_flashTools.png)

A hub. The buttons call the **same** code as the dedicated ADB / Fastboot screens. There is no second USB stack.

| Pane | What to do |
|---|---|
| **Guide** | Read the cable, OTG, USB debugging, bootloader key-combo and first-RSA notes |
| **Scan** | Refresh USB devices; grant permission; connect |
| **ADB** | Device check, shell, packages, push/pull, screen info, screenshot, logcat |
| **Fastboot** | Partition info, unlock (`UNLOCK`), flash a library image (`FLASH <partition>`), reboot |
| **Inspect** | See file kind, size, SHA-256 and sidecar claim. Type an expected hash to verify. Does not talk to the phone |
| **Log** | The same saved command log as the bottom panel |

**Flash a partition from Flash Tools (or Fastboot)**

1. Import the image into the library.
2. Put a sidecar next to it named `<image>.manifest.json` (or `<stem>.manifest.json`) that names the product or codename, the slot if it matters, and either the build fingerprint or a declared product/slot match. If the sidecar includes `sha256`, the file must match.
3. Connect the target in **fastboot**.
4. Choose the partition and the library file. Read the printed plan.
5. Type `FLASH <partition>` exactly (for example `FLASH boot`) and tap **Proceed**.
6. RootForge re-reads the phone (lock state, slot, serial) and runs the sidecar check again. A mismatch aborts. Nothing is written if any check fails.

There is **no setting** that turns the sidecar check or the typed phrase off.

::: danger A flash without a sidecar is refused
The log says `Refused: …`. Unknown is treated as unsafe.
:::

### ADB

![ADB](../android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_adb.png)

Needs the target in Android with USB debugging on, or in recovery sideload mode.

**Run a shell command**

1. Connect in ADB.
2. Type a command, or tap a quick chip, then **Run**.
3. Output appears in the log.

**Open a full terminal** (Android only)

1. Settings → Terminal app should name a package (`com.termux` by default).
2. On the ADB screen tap **Open full terminal**.
3. RootForge only launches that app. It passes no command, serial or file.

**Install an APK**

1. Import the APK.
2. Tap **Install APK** and pick it.

**Push or pull a file**

1. **Push:** pick a library file and a path on the target.
2. **Pull:** type the remote path and a name to save as.

**Sideload a zip**

**Sideload** means sending an update zip to a phone that is already in recovery's apply-from-ADB mode.

1. On the target, reboot to recovery and start "Apply update from ADB" (or use **Reboot → sideload** from this screen if the target supports it).
2. Import the zip.
3. Tap **Sideload** and pick it.
4. RootForge seeks the file as recovery asks for blocks (recovery reads the end of the zip first). `FAILFAIL` is a failure. There is no typed phrase for sideload.

**Logcat**

Tap **Start** to stream the target's log. Other USB actions are blocked until you **Stop**. Logcat holds the cable.

**Reboot**

Pick system, bootloader, recovery, sideload, fastboot or EDL. This only asks the phone to reboot. It does not write an image.

### Fastboot

![Fastboot](../android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_fastboot.png)

Needs the target in the bootloader.

| Action | How | Typed phrase |
|---|---|---|
| Read variables | **Read all** or **Get** a name | none |
| Flash a partition | pick partition + library file | `FLASH <partition>` |
| Boot once | pick an image — loads it for this boot only, does not write it | none |
| Unlock bootloader | **Unlock** — factory reset, may trip Knox / Widevine / banking apps | `UNLOCK` |
| Lock bootloader | **Lock** | `LOCK` |
| Switch slot | A or B on A/B phones | `SLOT a` or `SLOT b` |
| Erase a partition | **Erase** | `ERASE <partition>` |
| Reboot | system, bootloader, recovery, fastbootd | none |

**Boot once** is the safer way to try a patched boot image: if it does not boot, a reboot returns to what was already written.

Unlock also shows a large warning. A USB device appearing is never treated as permission to write.

### EDL (experimental)

**EDL** is Qualcomm's emergency download mode. The screen talks Sahara then Firehose. It has only been tested against a fake device.

1. Put the target in EDL (`adb reboot edl` on some phones, or that model's test points / key combo).
2. **Upload programmer** — you must supply the maker-signed programmer for *that* model. RootForge ships none. Type `EDL`.
3. **Configure** memory type, then **Storage info**.
4. **Write raw sectors** needs `WRITE LUN <lun> SECTOR <start>`.
5. **Reset** leaves EDL.

Retail phones accept only the OEM-signed programmer. A planned native (JNI) reuse of EDL-Commander is **not implemented**.

### Root / Patching

RootForge does **not** patch a boot image itself. It hands the image to a root manager on the **target**.

Managers you can pick: Magisk, KernelSU, APatch, and **Kitsune** (marked unsupported; package supplied by the owner and unverified; no homepage).

1. Connect in ADB. Import a stock `boot.img` (or the matching boot image for that phone).
2. **Inspect boot** to confirm it looks like a boot image.
3. Install the manager APK on the target if it is not there, or open its download page.
4. **Stage boot** — pushes the image to the target's Download folder.
5. **Open manager** on the target. Patch the image there.
6. **Fetch patched** pulls `magisk_patched-*.img` (or the matching prefix) back into the library.
7. Reboot the target to fastboot. **Boot once** to try it. Only then **Flash** (`FLASH boot`), with a sidecar.

### Modules

![Modules](../android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_modules.png)

Three tabs.

**Create a module zip**

1. Fill in id, name, version, author, description.
2. Optionally turn on `boot-completed.sh` or `action.sh`, and type your own script text.
3. Tap **Build zip**. The zip lands in the library. Compatibility notes are hedged: they are not a guarantee.

**Install a module on the USB target**

1. The target must already have a root manager.
2. Pick the zip (built here or imported).
3. Type `INSTALL MODULE`. The dialog shows the zip's SHA-256.
4. RootForge pushes the zip and asks the manager to install it.

**Install a module on this phone** (Android APK, This phone tab)

1. This phone must already be rooted (Magisk, KernelSU or APatch). RootForge auto-detects the manager; override the dropdown if it is wrong.
2. **List installed modules** is read-only (`/data/adb/modules`). It asks for root once.
3. Pick a zip. The SHA-256 and the `su` command are shown. Dry-run is on by default: the tap only logs the command.
4. Turn dry-run off and type `INSTALL MODULE` to run `magisk --install-module`, `ksud module install` or `apd module install`. RootForge does **not** reboot.
5. **Reboot this phone** is a separate button. LSPosed modules are APKs: use ADB → Install APK.

**Repositories**

This tab only opens known module-repository websites in the browser. RootForge does not fetch, index or vet modules. Some sites were unreachable when the list was written and are marked "not checked". **Links directory** (button on this screen) opens the fuller list.

### Links directory

![Links](../android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_links.png)

Drawer entry **Links**, also from Modules → **Links directory**, Settings → About, and Help. Official https pages for Magisk / KernelSU / APatch, module repositories (Alt-Repo, MMRL, Xposed-Modules-Repo, LSPosed / Vector, Zygisk Next), and Shizuku apps (Shizuku, Sui, App Ops, Ice Box, Hail, SystemUI Tuner, Canta, Inure, aShell / aShell You, Dhizuku). Each tap opens the system browser. Nothing is downloaded or bundled. URLs that could not be verified as official https pages are omitted. **Glossary** on this screen (and a drawer entry) opens the everyday-words list.

### Recovery (USB fastboot path)

This screen writes a recovery **image** to a target that is already in the bootloader, over USB.

1. Import a `.img`.
2. Pick the partition: `recovery`, `vendor_boot` or `boot` (phones differ).
3. **Boot once** (`fastboot boot`) tries it without writing.
4. **Flash** writes it (`FLASH <partition>`) and still needs a sidecar.
5. Browser buttons open TWRP, OrangeFox, PitchBlack and LineageOS device lists. The app does **not** download those images.

### Custom Recovery installer

![Custom Recovery](../android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_customRecovery.png)

A separate **Custom Recovery** drawer screen (landed in #77 / `fabc649`) installs official **TWRP** or **PitchBlack** on a phone that **already has root**, using `su` and `dd`. It does **not** replace **Recovery**, which is still the USB fastboot path (`fastboot boot` / `fastboot flash`) to a target in the bootloader.

This has **not** been run on a phone. No recovery images are bundled. The full contract is [docs/CUSTOM_RECOVERY_INSTALLER.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/CUSTOM_RECOVERY_INSTALLER.md).

You can also open this screen from Dashboard **Builds for this device** → **Install with Custom Recovery** on a matching TWRP or PitchBlack row. The **From Custom RR** card shows the catalog project; lookup still uses this phone's codename.

**Steps**

1. Open **Custom Recovery** (drawer, or **Install with Custom Recovery** on the catalog card). The screen detects **this phone** (Android app) and, if connected, the **USB ADB target**. Tap **Detect** to read `ro.product.device` / `ro.build.product`, whether `su -c id` reports `uid=0`, A/B vs A-only, and whether `/dev/block/by-name` has `recovery` / `recovery_a` / `recovery_b`.
2. Pick **This phone** or **USB target**.
3. Tap **Look up TWRP & PitchBlack** (or **Look up TWRP** / **Look up PitchBlack** when opened from the catalog) to list official files for that codename (https only, official hosts only). Or pick a local `.img` / `.zip` from the library.
4. **Download** copies a listed file into the library. The SHA-256 is always shown. When TWRP published a `.img.md5`, a mismatch refuses the write/download. PGP is **not** verified (the app does not ship a keyring).
5. **Dry-run is on by default.** **Run dry-run** prints the `dd` commands and stats the partition. It does not write.
6. Turn dry-run off and tap **Write recovery**. Type `FLASH RECOVERY`. RootForge backs up the current recovery partition into app storage first, then writes only `recovery` / `recovery_a` / `recovery_b`.
7. **Reboot to recovery now** is a separate button (`su reboot recovery` or `adb reboot recovery`). A write never reboots by itself.

**What it will not do**

- Write `boot`, `vendor_boot`, or any other partition. Devices whose recovery lives in boot or vendor_boot get the official installer zip path and a pointer to **Recovery → Boot once**.
- Guess an A/B slot. If `recovery_a` / `recovery_b` exist and the current slot is unknown, it refuses to write.
- `dd` a zip. Sideload the zip from ADB once the phone is in recovery, or install it from the recovery UI.
- Follow `http://`, or download from hosts outside TWRP's `dl.twrp.me` / `twrp.me` and PitchBlack's site (`pitchblackrecovery.com`, `pitchblack.tech`) / GitHub / SourceForge. Custom RR's OrangeFox, SHRP and RedWolf pages stay browser links.
- Skip the typed phrase. There is no setting that turns it off.

### Desktop mode via VictorSuite

![Desktop mode](../android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_desktopMode.png)

On the Android host-phone app, Dashboard has a **Desktop mode** card (landed in #76 / `b8e7708`). It asks **VictorSuite** to start a Linux desktop (XFCE) on this phone, then run RootForge's own desktop UI and VS Code inside that session. The Linux/Windows desktop client hides the card — that process already *is* the desktop UI.

This has **not** been run on a phone. Treat the buttons as untested on hardware. The full contract is [docs/DESKTOP_MODE.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/DESKTOP_MODE.md).

**VictorSuite** is a Termux-based companion (<https://github.com/Victorious93/VictorSuite>). Its package id is **`com.termux`** (the same as upstream Termux; the two cannot be installed together). They do **not** share a signing key. Identity is package name plus certificate hash, the same rule as fleet pairing, in the opposite direction (RootForge is the caller).

**What the card shows**

| VictorSuite | What you see |
|---|---|
| Not installed | A hint. The app stays up. |
| Installed, but no `RUN_COMMAND` and no Desktop-mode action | "Not supported yet", with a pointer at `docs/DESKTOP_MODE.md` |
| Installed | You must **pin** it the first time, and again if the certificate changes |
| Pinned | **Start desktop**, **Set up environment**, **Status**, **Stop** |

**Steps**

1. Install VictorSuite. Uninstall upstream Termux first if it is present. Without VictorSuite the card is only a hint.
2. Open RootForge → Dashboard. The card should name `com.termux`.
3. Tap **Pin this VictorSuite**. RootForge stores that package plus its signing-certificate SHA-256.
4. Grant "Run commands in Termux environment" if Android asks.
5. In VictorSuite, set `allow-external-apps=true` in `~/.termux/termux.properties`, and start its X11 path (internal or Termux:X11).
6. Install a RootForge Debian rootfs (PRoot or chroot). Inside it run `setup_desktop_mode.sh`, or tap **Set up environment**.
7. Tap **Start desktop**. VictorSuite should open a session running `rootforge-desktop-mode start`. XFCE appears on the X11 view; VS Code and RootForge's desktop UI start only if those binaries were installed.
8. **Status** asks for the current session. **Stop** ends it. Clear app data to revoke the pin, or re-pin after a VictorSuite rebuild with a new certificate.

RootForge sends only `start`, `stop`, `setup` or `status` as `rootforge-desktop-mode <op>`. The UI never splices typed text into that command.

**How the apps talk**

Today the only live path is Termux `RUN_COMMAND` (permission `com.termux.permission.RUN_COMMAND`). A narrower dedicated action (`ai.victorsuite.action.DESKTOP_MODE`, permission `ai.victorsuite.permission.DESKTOP_MODE`) is specified in `docs/DESKTOP_MODE.md` but is **not** in VictorSuite yet. Until it exists, the card says it is using `RUN_COMMAND`.

**Honest limits**

- Not run on a phone.
- VictorSuite does not implement the dedicated action yet.
- `RUN_COMMAND` still needs `allow-external-apps` and a granted permission.
- RootForge's Compose desktop UI inside the rootfs needs a Linux aarch64 `:desktop` build copied to `/opt/rootforge-os` or `$HOME/rootforge/desktop`. This repo does not fetch one.
- `code-oss` is often absent from stock Debian apt. code-server is installed only when both a URL and a SHA-256 pin are set.
- VNC fallback listens on localhost. How you view it on the phone is not implemented here.

Scripts in this repository (`setup_desktop_mode.sh`, `rootforge_desktop_mode.sh`, `termux/desktop-mode-entry.sh`) run **inside** the Debian rootfs that VictorSuite hosts.

### Logs / Terminal

A full-height log and a small command line.

Useful commands: `help`, `adb shell …`, a limited set of `fastboot` reads. The parser **refuses** `fastboot flash`, sideload, push/pull and EDL from this box and points you at the dedicated screens. Erase / unlock / lock / slot still go through the same approval dialogs.

**Share** sends the log (Android share sheet, or the clipboard on desktop). **Clear** empties it.

### Settings

![Settings](../android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_settings.png)

**Connection.** Switch **Connect automatically** (default on). Connects only when USB access is already granted.

**Shizuku / Sui** (Android only, optional). Four states: not installed, not running, permission needed, connected. See the Shizuku steps below.

**Terminal app** (Android only). Package name to launch. Default `com.termux`. Empty disables the button.

**ADB identity.** Shows this host's RSA fingerprint. **Generate a new key** makes every target ask again. The key lives in the app's private no-backup storage.

**External control.** The remote-control switch, node id, pending and paired controllers. See [Fleet integration](#fleet-integration).

**About.** Version, the one network use (catalog Download), USB stack note, Shizuku status. **Open the manual** opens this Help screen. **Links directory** opens the official-URL list.

**Help** is also a drawer entry. It renders this bundled manual offline and offers **Open on GitHub**, **Links directory**, and **Glossary**.

![Help](../android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_help.png)

### Shizuku / Sui (this phone, optional)

**Shizuku** is an app that lets another app run a few privileged *read* commands without full root, using wireless debugging. **Sui** is the Magisk-module form of the same API.

RootForge works without it. When connected, Settings can run read-only lookups **on the host phone** (not the USB target): device info, package list, package info, `settings get`, `getprop`, block-device listing.

1. Install Shizuku (<https://shizuku.rikka.app/download/>) or Sui if you already use Magisk.
2. Start Shizuku (wireless debugging) or Sui.
3. In RootForge Settings tap **Request Shizuku permission**.
4. Each lookup still asks for confirmation. Output stays on the Settings card and is **not** copied into the shared command log.

This path never flashes, unlocks, wipes or writes.

### Toolkit command line (short)

From a checkout, after setting `PYTHONPATH` as in [Install](#install-and-first-run-setup):

| Command | What it does |
|---|---|
| `rootforge doctor` | Checks that tools are installed |
| `rootforge devices` | Lists adb/fastboot devices |
| `rootforge device info` | Shows the connected phone |
| `rootforge device check` | Go/no-go before a write (exit 3 = blocked) |
| `rootforge flash` | Flash a patched boot (typed confirm on a real terminal) |
| `rootforge backup` | create / list / verify / restore |
| `rootforge module` | scaffold / lint / build |
| `rootforge boot` | patch, inspect, unpack, flash-last |
| `rootforge ota` | inspect / extract an OTA payload |
| `rootforge avd` | emulator helpers (needs a computer with KVM) |

Device writes from the CLI still print a plan and need a typed confirmation on the terminal. `ROOTFORGE_ASSUME_YES=1` bypasses those prompts — do not set it unless you understand that.

---

## Every permission, and why

Read from the app's `AndroidManifest.xml`. Android may also show a USB-access prompt that is **not** a manifest permission.

| Permission or feature | Why RootForge asks |
|---|---|
| **USB Host** (`android.hardware.usb.host`, required) | The host phone must be able to talk to another USB device. Phones without this hardware cannot install the app |
| **Notifications** (`POST_NOTIFICATIONS`) | Only to tell you a controller is waiting for pairing, or that a remote command needs approval, and only when the app is not on screen. Android 13+ asks once at first launch |
| **Internet** (`INTERNET`) | Two tap-only uses: Dashboard **Download** on "Builds for this device" (two https reads of the Custom RR catalog), and **Custom Recovery** **Look up** / **Download** of official TWRP (`twrp.me` / `dl.twrp.me`) and PitchBlack (`pitchblackrecovery.com`, `pitchblack.tech`, GitHub, SourceForge) files. **Install with Custom Recovery** can start that lookup. Device work never uses the network. Import file is offline |
| **Shizuku API** (`moe.shizuku.manager.permission.API_V23`) | Optional. Requested through Shizuku's own dialog, not the usual Android permission screen. Lets read-only lookups run when you have granted Shizuku |
| **Run commands in Termux** (`com.termux.permission.RUN_COMMAND`) | Desktop mode: after you pin VictorSuite, Start / Set up / Status / Stop send one documented `rootforge-desktop-mode` command. Android may ask once |
| **Desktop mode** (`ai.victorsuite.permission.DESKTOP_MODE`) | Declared for the dedicated VictorSuite action. VictorSuite does not define this permission yet, so Android will not prompt for it today |
| **USB device access** (runtime prompt) | Android asks each time a new USB target appears until you allow this app to use that device |
| **Install unknown apps** | Only if you open the APK on the phone itself to install RootForge. Not requested by the app |

The app also *looks up* (does not request permission for) these other packages, so Android 11+ will let it see them:

- `ai.droidcommand.app` and `ai.droidcommand.app.debug` — so pairing can read the controller's signing certificate
- `moe.shizuku.privileged.api` and `rikka.sui` — so Settings can tell whether Shizuku or Sui is installed
- `com.termux`, `ai.victorsuite.app` and `ai.victorsuite.app.debug` — so Desktop mode can see VictorSuite and read its signing certificate
- The `com.termux.RUN_COMMAND` and `ai.victorsuite.action.DESKTOP_MODE` actions — so the card can tell which transport exists
- Any app with a launcher icon — so Settings can find the terminal app you named

The **Messenger bridge service** is exported on purpose (`ai.rootforge.os.action.BRIDGE`). Binding grants **nothing**. Pairing plus the off-by-default switch are the gates.

Backup of app data is **off** (`allowBackup="false"`) so the ADB key is not copied by Android backup.

---

## Fleet integration

RootForge is an **EXTERNAL_NODE**. This section is the frozen **protocol 1.1** remote-control contract (`BRIDGE_PROTOCOL_MAJOR = 1`, `BRIDGE_PROTOCOL_MINOR = 1`). The older snake_case envelope (`protocol_major: 1`, `operation: rootforge.devices.list`, …) still works on the same Messenger service. Pairing steps are in [Install](#install-and-first-run-setup). Technical notes: [docs/DROIDCOMMAND_INTEGRATION.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/DROIDCOMMAND_INTEGRATION.md).

### Transport

JSON over Android Messenger. The host-phone app exports a bound service for action `ai.rootforge.os.action.BRIDGE` (debug package `ai.rootforge.os.debug`; the action string does not change).

| Direction | `Message.what` | Bundle key |
|---|---|---|
| Request | 1 | `request` (JSON string) |
| Reply | 2 | `response` (JSON string) |

Desktop / Docker reuse the same JSON on `POST /v1/bridge` (loopback bearer token). That is not the Messenger path.

### Envelope (flat fields)

```json
{
  "op": "capabilities.get",
  "requestId": "dca-…",
  "targetNodeRef": "rf-…32 hex…",
  "actionRef": "rootforge.devices.list",
  "params": {},
  "nonce": "0123456789abcdef0123456789abcdef",
  "timestampMs": 1700000000000,
  "ttlMs": 120000,
  "destructive": false,
  "trigger": {}
}
```

| Field | Rule |
|---|---|
| `op` | `capabilities.get` \| `remote.command` \| `macro.step` \| `macro.run` \| `node.status.get` \| `config.export` \| `config.import` |
| `requestId` | Echoed on the reply |
| `targetNodeRef` | This install's node id (Settings). Mismatch → `not_found` |
| `actionRef` | Required for `remote.command` and `macro.step` |
| `params` | Object, default `{}` |
| `nonce` | Unique 128-bit value, 32 hex characters |
| `timestampMs` | Epoch milliseconds |
| `ttlMs` | Default 120000, max 300000 |
| `destructive` | Advisory boolean only. Never lowers a tier or skips a confirm |
| `trigger` | Optional object. **Informational only** — never used to authorize, pair, or auto-approve |

`macro.step` also requires `macroId`, `stepIndex` (≥ 0), `onError` (`abort` \| `continue`).

`macro.run` also requires `macroId`, `steps` (array of `{actionRef, params?, onError?}`), and optional `dryRun` (boolean, default false). A dry run returns the plan and does not execute or prompt.

Optional `protocolMajor` / `protocolMinor` / `sourceNodeRef` may be present. `sourceNodeRef` is ignored for auth and for the replay cache.

### Authorization

Authorize **only** by the calling app's package name plus the SHA-256 of its signing certificate, taken from `Message.sendingUid` (fallback `Binder.getCallingUid`) and `PackageManager` signing info. Nothing in the JSON — including `trigger.*` and `sourceNodeRef` — can pair a caller or skip a gate.

Pairing is an allow-list the owner manages in **Settings → External control**. The first request from an unknown caller is recorded as pending and refused (`not_paired`). A re-signed package shows as pending again.

### Remote-control switch: off by default

**Settings → External control → Allow external controllers** starts **off**. Off → `external_control_disabled` (protocol 1.1) or `bridge_disabled` (legacy). Pending callers are still recorded so you can pair them after turning the switch on.

### Replay and expiry

A request is `expired` when `now > timestampMs + ttlMs` **or** `|now - timestampMs| > 120000` (clock skew). A nonce already seen for the same authenticated source (`package:certSha256`) is `replay_detected`. The seen-nonce cache is kept **at least 10 minutes**. An expired nonce is still remembered so a later replay with a fresh timestamp is refused.

### `capabilities.get`

Returns `protocolMajor`, `protocolMinor`, `fleetClass: external_node`, node identity, then:

- `ops[]` — envelope ops (`capabilities.get`, `remote.command`, `macro.step`, `macro.run`, `node.status.get`, `config.export`, `config.import`)
- `actions[]` — `remote.command` action refs that are remotely allowed

Each entry is `{name, kind, remoteAllowed, requiresConfirm}` where `kind` is `read` \| `action` \| `destructive`.

### `node.status.get`

Read. Same pairing, default-off switch, and nonce/ttl/replay as the other protocol 1.1 ops. No confirm. Returns **one** `NodeStatusSnapshot` for this node (the frozen STATUS-TRAY schema from droidcommand-AI `docs/specs/STATUS_TRAY.md`). RootForge is a node, not the tray aggregator, so the reply is the snapshot object itself, not `{ "snapshots": […] }`.

```json
{
  "nodeId": "rf-…",
  "packageName": "ai.rootforge.os.debug",
  "connectionClass": "EXTERNAL_NODE",
  "updatedAtMs": 1700000000000,
  "domains": [
    { "name": "node_health", "state": "ok", "detail": "process alive" },
    { "name": "other", "state": "ok", "detail": "su available; Magisk detected" }
  ]
}
```

`packageName` is this app's applicationId on Android and is omitted on the desktop client. `connectionClass` is always `EXTERNAL_NODE`. Domains always include `node_health`. On the Android app an `other` domain is added for root: whether a `su` binary is **present** (looked up on disk, never executed, so no Superuser prompt) and which manager APK was detected (`RootManager.detect`). `detail` is at most 200 characters and contains no secrets; a snapshot that would leak a forbidden name (`password`, `token`, `secret`, `signingKey`, …) is refused as `invalid_parameters`, not stripped. Optional `params.nodeId` must be this node or the reply is `not_found`. Optional `params.snapshot` is ignored.

### What a remote caller may run

Only actions that already exist on this node:

| `actionRef` | kind | Confirm |
|---|---|---|
| `rootforge.devices.list` | read | no |
| `rootforge.device.info` | read | no |
| `rootforge.adb.shell` | action | on-device prompt |
| `rootforge.adb.reboot` | action | on-device prompt |
| `rootforge.fastboot.reboot` | action | on-device prompt |
| `rootforge.module.build` | action | on-device prompt |
| `rootforge.fastboot.flash` | destructive | typed phrase on the device (`ApprovalCenter`) |
| `rootforge.fastboot.slot.set` | destructive | typed phrase |
| `rootforge.fastboot.unlock` | destructive | typed phrase |
| `rootforge.fastboot.lock` | destructive | typed phrase |
| `shizuku.readDeviceState` | action | on-device prompt |
| `shizuku.pmGrant` | destructive | typed `GRANT PERMISSION` |

`shizuku.exec` exists on the host but is **not** remotely allowed (`capability_off`). EDL, recovery writes (including Custom Recovery `su`/`dd` / `FLASH RECOVERY`), sideload, `fastboot oem` / erase / boot-once, APK install and push/pull are **not** fleet actions; naming them returns `confirmation_required` and nothing is written. A remote `trigger` never auto-approves a destructive op.

### `config.export` / `config.import`

Standalone protocol 1.1 ops (not `remote.command` action refs). Advertised as:

| `op` | kind | `remoteAllowed` | `requiresConfirm` |
|---|---|---|---|
| `config.export` | action | **false** | false |
| `config.import` | action | **false** | **true** |

A remote caller (any other app on the Messenger / fleet path, after the usual package+certificate pairing) is refused (`capability_off`). Local Settings can still export and import. The payload is this node's UNIFIED_BACKUP entry (`schemaVersion` 1, `wrapping=none` + `clearPayload` in local-dev / tests). Export is **non-secret preferences only**: `autoConnect` and `terminalApp`. It never includes Magisk / KernelSU / APatch tokens, root passwords, signing material, pairing keys, the controller allow-list, the VictorSuite pin, or the desktop API token. Import accepts `dryRun` (envelope or params) and `conflictPolicy` `skip` (default) \| `overwrite` \| `merge-by-id`. A real import needs on-device confirm through `ApprovalCenter` and **never** turns on `remote.command` / Allow external controllers (the switch stays as-is). Unknown bundle/payload versions or keys, and oversized payloads, are `invalid_parameters`.

The legacy snake_case envelope (`protocol_major: 1`, `operation: rootforge.*`) still lists the same USB actions (without the Shizuku names). Unknown legacy ops return `unknown_operation`. Images named in a flash request are **library file names**, not paths.

### Error codes

Exactly these `error.code` values on protocol 1.1 (message is for humans):

`invalid_parameters`, `not_paired`, `not_found`, `internal`, `unauthorized`, `replay_detected`, `expired`, `external_control_disabled`, `confirmation_required`, `unsupported_op`, `capability_off`.

Success:

```json
{"ok":true,"protocolMajor":1,"protocolMinor":1,"requestId":"dca-…","nodeRef":"rf-…","result":{}}
```

Failure:

```json
{"ok":false,"protocolMajor":1,"protocolMinor":1,"requestId":"dca-…","nodeRef":"rf-…","error":{"code":"not_paired","message":"…"}}
```

### Safety gates and confirms

In this order, for every protocol 1.1 request:

1. Well-formed JSON with the required envelope fields.
2. Caller identity from Binder / `Message.sendingUid` (never from the JSON).
3. Unknown callers are recorded as pending (`not_paired`).
4. The switch must be on (`external_control_disabled` if not).
5. `targetNodeRef` must be this install's node id (`not_found` if not).
6. Nonce / timestamp replay checks.
7. The `op` and `actionRef` must exist.
8. **Read** runs. **Action** shows Allow / Cancel on the host. **Destructive** uses the same typed phrase as the on-screen button (`FLASH boot`, `UNLOCK`, `GRANT PERMISSION`, …). Timeout or Cancel is `confirmation_required`. Nothing is auto-approved from a remote trigger.

The GUI uses the same typed phrases for its own writes:

| Phrase | Used for |
|---|---|
| `FLASH <partition>` | Fastboot / Recovery / Root flash |
| `FLASH RECOVERY` | Custom Recovery installer (`su` / `dd`) |
| `SLOT a` / `SLOT b` | Active slot |
| `ERASE <partition>` | Fastboot erase |
| `UNLOCK` / `LOCK` | Bootloader |
| `EDL` | Upload an EDL programmer |
| `WRITE LUN n SECTOR n` | Firehose raw write |
| `INSTALL MODULE` | Install a module zip (SHA-256 shown) |
| `GRANT PERMISSION` | Shizuku `pmGrant` (fleet only; no Settings button) |

**Boot once** and sideload do not use a typed phrase. Flash of a permanent image always does.

The sidecar / device-match check runs before a flash and again after you confirm. Unknown product, slot or lock state blocks the write.

### Pairing on a phone (unverified here)

1. Install RootForge `app-debug.apk` (`ai.rootforge.os.debug`). Open Settings → External control; copy **Node id**.
2. Install a debug droidcommand-AI build. Bind `ai.rootforge.os.action.BRIDGE` and send `capabilities.get` with that `targetNodeRef`, a unique 32-hex `nonce`, and `timestampMs` = now.
3. The first reply is `not_paired` or `external_control_disabled`. RootForge Settings shows the pending package + cert prefix.
4. Turn **Allow external controllers** on, then **Pair**.
5. Repeat `capabilities.get`. Reads (`capabilities.get`, `node.status.get`, `rootforge.devices.list`, …) run; action ops prompt on the phone; destructive ops need the same typed phrase the GUI uses.

This path has **not** been run on a phone in this repository.

### Code

- Envelope: `android-app/core/.../bridge/FleetProtocol.kt`, `FleetEnvelope.kt`
- Replay: `ReplayGuard.kt`
- Executor: `FleetExecutor.kt`, `RemoteActionCatalog.kt`
- STATUS-TRAY snapshot: `NodeStatusSnapshot.kt`, `NodeStatusSource.kt`
- Ingress (fleet vs legacy): `BridgeIngress.kt`
- Messenger: `android-app/app/.../bridge/RootForgeBridgeService.kt`
- Tests: `FleetEnvelopeTest`, `ReplayGuardTest`, `FleetAuthTest`, `FleetCapabilitiesTest`, `FleetExecutorTest`, `NodeStatusSnapshotTest`, `ConfigBackupTest`

---

## Troubleshooting and FAQ

**The target does not appear.** Use a data cable, not charge-only. Put an OTG adapter on the **host** if the host stays in device mode. On the target, USB debugging on and USB mode = File transfer. Tap Refresh. Charge-only gadgets and keyboards are ignored on purpose.

**Android never asks for USB access.** Unplug and replug. Grant from the Dashboard row. Some hubs need a powered port.

**The target says unauthorized.** Accept the RSA dialog *on the target*. Settings shows the host fingerprint. If you generated a new ADB key, every target asks again.

**`adb install -r` says signatures do not match.** You still have the original v0.2.0 APK. Uninstall `ai.rootforge.os.debug` once, then install again. Data is lost.

**Flash says Refused.** The image has no sidecar, the sidecar does not match this phone, or the SHA-256 does not match. Fix the sidecar. There is no override.

**I typed the phrase and nothing happened.** The phrase is case-sensitive and must match exactly, including the partition name. A 120-second timeout counts as Cancel.

**Unlock would factory-reset the target.** Yes. That is Android, not RootForge. Back up first. Knox / Widevine / banking apps / warranty can be affected. RootForge shows that warning on purpose.

**Sideload stopped early on an older build.** Current builds seek the zip when recovery asks for an earlier block. The target must already be in sideload mode.

**EDL does nothing useful.** You need that model's OEM-signed programmer. RootForge does not ship one and cannot bypass Secure Boot. The screen is experimental.

**Shizuku stays "not installed" even though I use Sui.** Pull to Refresh. Sui is counted when its binder is alive, even if the package is hidden. If the binder is down, the state is "not running".

**Ask gives no answer.** It only matches keywords it knows. Try a shorter phrase, or search the Root Guide.

**A controller cannot pair.** Confirm the node id, protocol major 1, and that the controller's package is visible (`ai.droidcommand.app` or `.debug`). A different package name needs a `<queries>` update. Re-signed controllers must be paired again.

**Desktop cannot see the phone.** Install platform-tools, udev/`plugdev` on Linux, Google USB drivers on Windows. WSL2 USB is unverified.

**Desktop mode does nothing.** The card is in the current app (Dashboard, Android only). It has **not** been run on a phone. Install VictorSuite (`com.termux`), pin it, grant `RUN_COMMAND`, set `allow-external-apps=true`, and install the rootfs wrapper. The dedicated Desktop-mode action is not in VictorSuite yet; `RUN_COMMAND` is the only live path. Missing VictorSuite is a hint, not a crash. See [docs/DESKTOP_MODE.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/DESKTOP_MODE.md).

**Custom Recovery will not write.** The phone must already have root (`su`). Dry-run is on by default. A real write needs typed `FLASH RECOVERY`. Recovery-in-boot devices are refused (use **Recovery → Boot once** or an installer zip). An unknown A/B slot is refused. A zip is never written with `dd`. This has **not** been run on a phone. See [docs/CUSTOM_RECOVERY_INSTALLER.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/CUSTOM_RECOVERY_INSTALLER.md).

**Is anything signed for release?** Only debug APKs. The v0.2.0 asset is debug. ISO and Termux rootfs were not attached to that release.

**Where is a more technical write-up?** [docs/ANDROID_APP.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/ANDROID_APP.md), [docs/DESKTOP_MODE.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/DESKTOP_MODE.md), [docs/CUSTOM_RR_CATALOG.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/CUSTOM_RR_CATALOG.md), [docs/CUSTOM_RECOVERY_INSTALLER.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/CUSTOM_RECOVERY_INSTALLER.md), [docs/DROIDCOMMAND_INTEGRATION.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/DROIDCOMMAND_INTEGRATION.md), [docs/SECURITY_MODEL.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/SECURITY_MODEL.md), [docs/FLASH_TOOLS_PLAN.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/FLASH_TOOLS_PLAN.md).

---

## Privacy and data handling

RootForge does **not** ship analytics, crash reporters or advertising SDKs.

### What stays on the device

| Data | Where | Notes |
|---|---|---|
| Settings (node id, switches, terminal package) | App preferences | Node id is random, generated once |
| VictorSuite pin | App preferences (`victorsuite_pin`) | Package + certificate hash. Cleared with app data |
| Paired controllers | App preferences | Package + certificate hash |
| Pending pairing list | Memory only | Lost if the app process dies |
| ADB host key | Private no-backup folder | Not included in Android backup |
| Command log | `command-log.jsonl` in app files (desktop: `~/.rootforge/command-log.jsonl`) | Redacted; desktop API token is **not** this file |
| Library images / zips / APKs | App files `library/` (desktop: `~/.rootforge/library`) | Copies you imported, plus Custom Recovery backups before a real `dd` |
| Build catalog | App files `build-catalog/` | Last good copy only |
| Desktop API token | `~/.rootforge/desktop.properties` | Created mode `0600`; a world-readable copy is tightened before it is read |

Shizuku lookup output is shown on the Settings card only and is **not** written to the command log.

### Network

- **Catalog Download:** two https GETs to raw.githubusercontent.com, only when you tap Download. 8 MiB cap. http is refused.
- **Custom Recovery lookup/download:** https GETs to official TWRP (`twrp.me`, `dl.twrp.me`) and PitchBlack (`pitchblackrecovery.com`, `pitchblack.tech`, GitHub, SourceForge) hosts, only when you tap **Look up**, **Download**, or **Install with Custom Recovery** (which then looks up).
- **Browser links:** TWRP / OrangeFox / PitchBlack / LineageOS / module repos / Shizuku / Root Guide external links open the system browser. Those buttons do not download the files.
- **Ask** and **Root Guide** and **Help** are offline.
- Device/USB work never uses the network.

### What the controller can see

A paired controller with the switch on can list USB targets and read the connected target's properties, run an approved shell command, and request flashes of **library** images. It cannot read your desktop API token, your ADB private key, or files outside the library name list. `config.export` / `config.import` are advertised but **not** remotely allowed.

### What we do not collect

No accounts. No cloud. No phone-home. Uninstalling the app (or the one-time v0.2.0 uninstall) deletes its private data on that phone.

---

## Glossary

![Glossary](../android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_glossary.png)

Drawer entry **Glossary**, also from Help and from Links. One short line per term, in everyday words. Official Magisk, LSPosed, Vector, Shizuku and Sui pages stay on **Links**. Nothing here is a how-to or a download.

**Root.** Extra control over the phone so some apps can do things a normal app is not allowed to.

**su.** The small program that actually runs a command with that extra control. RootForge asks you before it uses it.

**Magisk.** A common way to get root by patching the boot image, plus an app that installs modules.

**KernelSU.** Root that lives in the phone's Linux kernel, with its own app.

**KernelSU Next.** A separate KernelSU-family project with its own manager, not the same app as KernelSU.

**APatch.** Another way to get root by patching the kernel, with its own app and a SuperKey you choose.

**Module.** A zip (or, for LSPosed, an APK) that changes the system after the phone is rooted. It runs with high privilege.

**Zygisk.** Magisk's built-in way of slipping extra code into apps as they start.

**Zygisk Next.** A stand-alone Zygisk that also works with KernelSU and APatch (Dr-TSNG). Later releases are not open source.

**ReZygisk.** An open-source fork of Zygisk Next. It is not an official successor.

**Xposed.** An older way to hook app code. People use LSPosed or Vector for that job now.

**LSPosed.** Today's Xposed-style framework. Its modules are APKs you enable inside LSPosed, not Magisk zips.

**Vector.** JingMatrix's maintained successor to LSPosed.

**Shizuku.** Lets apps use a few system APIs without full root, using wireless debugging or root.

**Sui.** Shizuku packaged as a Magisk module, for a phone that is already rooted.

**Dhizuku.** Shares Device Owner powers with other apps, in a Shizuku-like way.

**ADB.** Android Debug Bridge: a USB or Wi-Fi link so a computer or another phone can talk to this one.

**fastboot.** A USB mode in the bootloader for writing partitions before Android starts.

**EDL.** Emergency Download (Qualcomm 9008): a very low-level USB mode. Experimental here; a retail phone only accepts its maker's programmer.

**Serial.** The USB id of a phone. RootForge shows only the last four characters in the app and in logs.

**SoC.** The main chip inside the phone (the processor and related parts).

**Slot.** On many phones, two copies of the system (A and B) so an update can roll back.

**USB mode.** Whether the cable is talking ADB, fastboot, or EDL right now.

**Bootloader.** The tiny program that starts the phone before Android. Unlocking it usually wipes the phone.

**Recovery.** A small system you boot instead of Android to install updates or wipe data.

**TWRP.** Team Win Recovery Project: a well-known custom recovery.

**PitchBlack.** PitchBlack Recovery Project (PBRP): another custom recovery, based on TWRP.

**Boot image.** The boot.img or init_boot.img file that starts Android. Magisk patches this file.

**ROM.** The Android system the phone runs: the maker's build, or a custom one.

**Flash.** Write an image file onto a partition. Easy to brick a phone; RootForge asks you to type a confirmation.

**Dry run.** Show the command and change nothing. Dangerous actions in this app default to this.

**SHA-256 / checksum.** A fingerprint of a file's bytes. If it matches the published value, you have the file you expected.

**Bootloop.** The phone starts, then restarts, forever. Often from a bad flash or a bad module.

---

*This manual matches the code including Desktop mode from #76 (`b8e7708`), the Custom Recovery installer from #77 (`fabc649`), the Help screen from #78 (`44716de`), fleet protocol 1.1 from #79 (`988a941`), **Install with Custom Recovery** on matching Custom RR TWRP/PitchBlack rows, the in-app Glossary, and the **Devices** multi-phone identify screen. If a line here disagrees with the running app, the app is right — please file an issue.*
