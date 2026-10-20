<p align="center">
  <img src="images/manual/header.png" alt="RootForge OS how-to manual — emblem with a neon cyan-to-magenta ring" width="720">
</p>

# RootForge OS how-to manual

This is the full how-to for RootForge OS. It is written in everyday language. When a technical term is needed, it is explained in one short line the first time it appears.

The same text is bundled inside the Android app and the desktop client: open the drawer and tap **Help**, or open **Settings** and tap **Open the manual**. A live copy also lives on GitHub: <https://github.com/Victorious93/rootforge-os/blob/main/docs/MANUAL.md>

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
3. In the controller, bind `ai.rootforge.os.action.BRIDGE` on package `ai.rootforge.os.debug` (debug build) and send `rootforge.capabilities.get` with that node id. The first reply is `not_paired` or `bridge_disabled`. That is expected. The caller now appears under **Waiting for approval**.
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

The home screen. You see attached USB devices, the connected target, a short device info card, reboot shortcuts, your imported file library, and **Builds for this device**.

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

This card lists community ROMs and recoveries whose catalog name matches the connected phone. It is **display only**. It never decides whether a flash is allowed.

1. Tap **Download** to fetch the catalog over https (this is the only reason the app asks for Internet). Or tap **Import file** and pick a `catalog.json` / `freshness.json` you already have.
2. Open a download or forum link in the system browser if you want the file. RootForge does not download the ROM itself.
3. **Remove** forgets the stored catalog. The last good copy is kept if a new download does not parse.

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

Two tabs.

**Create a module zip**

1. Fill in id, name, version, author, description.
2. Optionally turn on `boot-completed.sh` or `action.sh`, and type your own script text.
3. Tap **Build zip**. The zip lands in the library. Compatibility notes are hedged: they are not a guarantee.

**Install a module on the target**

1. The target must already have a root manager.
2. Pick the zip (built here or imported).
3. Type `INSTALL MODULE`. The dialog shows the zip's SHA-256.
4. RootForge pushes the zip and asks the manager to install it.

**Repositories**

This tab only opens known module-repository websites in the browser. RootForge does not fetch, index or vet modules. Some sites were unreachable when the list was written and are marked "not checked".

### Recovery (USB fastboot path)

This screen writes a recovery **image** to a target that is already in the bootloader, over USB.

1. Import a `.img`.
2. Pick the partition: `recovery`, `vendor_boot` or `boot` (phones differ).
3. **Boot once** (`fastboot boot`) tries it without writing.
4. **Flash** writes it (`FLASH <partition>`) and still needs a sidecar.
5. Browser buttons open TWRP, OrangeFox, PitchBlack and LineageOS device lists. The app does **not** download those images.

### Custom Recovery installer — coming in the next update

A separate **Custom Recovery** drawer screen (open pull request #77, branch `feat/recovery-installer`) will install official **TWRP** or **PitchBlack** on a phone that **already has root**, using `su` and `dd`.

Planned behaviour, so you know what to expect:

- Detect this phone and/or the USB ADB target (codename, whether `su` works, A/B vs A-only, whether a real `recovery` partition exists).
- **Look up** and **Download** only when you tap. No images are bundled.
- Or import a local `.img` / `.zip`.
- **Dry-run is on by default.** A real write needs typed `FLASH RECOVERY`, backs up the current recovery into app storage, then writes only `recovery` / `recovery_a` / `recovery_b`. It never writes `boot` or `vendor_boot`. A zip is never written with `dd`.
- **Reboot to recovery** is a separate button. A write never reboots by itself.

Until that update lands, use **Recovery** (fastboot) and the Root Guide pages for TWRP / PitchBlack.

### Desktop mode via VictorSuite — coming in the next update

A Dashboard **Desktop mode** card (open pull request #76, branch `feat/desktop-mode-x11`) will start a Linux desktop (XFCE) on the host phone through **VictorSuite**, then run RootForge's desktop UI inside that session.

VictorSuite is a Termux-based companion. Its package id is `com.termux` (same as upstream Termux; the two cannot be installed together).

Planned behaviour:

1. Install VictorSuite. Without it the card is only a hint and the app stays up.
2. Tap **Pin this VictorSuite** (package + signing-certificate SHA-256, same idea as fleet pairing).
3. Grant "Run commands in Termux environment" if Android asks, and set `allow-external-apps=true` in VictorSuite's `termux.properties`.
4. **Set up environment**, then **Start desktop** / **Stop**. RootForge sends only `start`, `stop`, `setup` or `status` — never a free-form shell.

This has **not** been run on a phone. The dedicated VictorSuite Desktop-mode action is specified but not in VictorSuite yet; the fallback is Termux `RUN_COMMAND`.

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

**About.** Version, the one network use (catalog Download), USB stack note, Shizuku status. **Open the manual** opens this Help screen.

**Help** is also a drawer entry. It renders this bundled manual offline and offers **Open on GitHub**.

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
| **Internet** (`INTERNET`) | **Only** the Dashboard **Download** button on "Builds for this device": two https reads of the Custom RR catalog. Device work never uses the network. Import file is offline |
| **Shizuku API** (`moe.shizuku.manager.permission.API_V23`) | Optional. Requested through Shizuku's own dialog, not the usual Android permission screen. Lets read-only lookups run when you have granted Shizuku |
| **USB device access** (runtime prompt) | Android asks each time a new USB target appears until you allow this app to use that device |
| **Install unknown apps** | Only if you open the APK on the phone itself to install RootForge. Not requested by the app |

The app also *looks up* (does not request permission for) these other packages, so Android 11+ will let it see them:

- `ai.droidcommand.app` and `ai.droidcommand.app.debug` — so pairing can read the controller's signing certificate
- `moe.shizuku.privileged.api` and `rikka.sui` — so Settings can tell whether Shizuku or Sui is installed
- Any app with a launcher icon — so Settings can find the terminal app you named

The **Messenger bridge service** is exported on purpose (`ai.rootforge.os.action.BRIDGE`). Binding grants **nothing**. Pairing plus the off-by-default switch are the gates.

Backup of app data is **off** (`allowBackup="false"`) so the ADB key is not copied by Android backup.

Coming in the next update (#76): a `com.termux.permission.RUN_COMMAND` request so Desktop mode can ask VictorSuite to start or stop the Linux session. Not in the current release.

---

## Fleet integration

### What the controller can drive

After the switch is on **and** the caller is paired, these operations exist (JSON protocol major 1):

| Operation | Kind | What happens |
|---|---|---|
| `rootforge.capabilities.get` | read | Protocol version, node id, app version, allowed operations |
| `rootforge.devices.list` | read | USB targets RootForge can see |
| `rootforge.device.info` | read | Properties of the connected target |
| `rootforge.adb.shell` | control | Run one shell command — you tap **Allow** on the host |
| `rootforge.adb.reboot` | control | Reboot the target to a mode — you tap **Allow** |
| `rootforge.fastboot.reboot` | control | Reboot from fastboot — you tap **Allow** |
| `rootforge.module.build` | control | Build a module zip into the library (does not install it) |
| `rootforge.fastboot.flash` | destructive | Same sidecar + typed `FLASH <partition>` as the GUI |
| `rootforge.fastboot.slot.set` | destructive | Typed `SLOT a` or `SLOT b` |
| `rootforge.fastboot.unlock` | destructive | Typed `UNLOCK` |
| `rootforge.fastboot.lock` | destructive | Typed `LOCK` |

**Not** on the bridge (the controller gets `unknown_operation`): EDL, sideload of a zip, erase, boot-once, raw `fastboot oem`, APK install, push/pull, logcat, recovery install, Firehose.

Images named in a flash request are **library file names**, not paths. A request cannot raise its own privilege or skip a prompt.

Desktop and Docker use the same JSON over local HTTP with a Bearer token, not Messenger. That is a separate pipe.

### Remote-control switch: off by default

**Settings → External control → Allow external controllers** starts **off**.

A request while the switch is off still appears as pending, so you can see who asked, but the reply is `bridge_disabled`. Pairing does nothing useful until the switch is on.

### Safety gates and confirms

In this order, for every bridge request:

1. Well-formed JSON, size-capped.
2. `protocol_major` must be **1**.
3. `target_node_id` must be this install's node id.
4. Unknown callers are recorded as pending.
5. The switch must be on.
6. Caller package **and** certificate must match a paired row.
7. The operation must exist.
8. Only listed parameters are accepted.
9. **Read** runs. **Control** shows Allow / Cancel on the host. **Destructive** uses the same typed phrase as the on-screen button (`FLASH boot`, `UNLOCK`, …). Timeout or Cancel is `denied_by_user`.

The GUI uses the same gates for its own writes:

| Phrase | Used for |
|---|---|
| `FLASH <partition>` | Fastboot / Recovery / Root flash |
| `FLASH RECOVERY` | Custom Recovery installer (next update) |
| `SLOT a` / `SLOT b` | Active slot |
| `ERASE <partition>` | Fastboot erase |
| `UNLOCK` / `LOCK` | Bootloader |
| `EDL` | Upload an EDL programmer |
| `WRITE LUN n SECTOR n` | Firehose raw write |
| `INSTALL MODULE` | Install a module zip (SHA-256 shown) |

**Boot once** and sideload do not use a typed phrase. Flash of a permanent image always does.

The sidecar / device-match check runs before a flash and again after you confirm. Unknown product, slot or lock state blocks the write.

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

**Will Desktop mode or the TWRP installer work today?** Not on the current main app. They are coming in the next update (PRs #76 and #77). This Help screen already describes them so the steps are here when they land.

**Is anything signed for release?** Only debug APKs. The v0.2.0 asset is debug. ISO and Termux rootfs were not attached to that release.

**Where is a more technical write-up?** [docs/ANDROID_APP.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/ANDROID_APP.md), [docs/DROIDCOMMAND_INTEGRATION.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/DROIDCOMMAND_INTEGRATION.md), [docs/SECURITY_MODEL.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/SECURITY_MODEL.md), [docs/FLASH_TOOLS_PLAN.md](https://github.com/Victorious93/rootforge-os/blob/main/docs/FLASH_TOOLS_PLAN.md).

---

## Privacy and data handling

RootForge does **not** ship analytics, crash reporters or advertising SDKs.

### What stays on the device

| Data | Where | Notes |
|---|---|---|
| Settings (node id, switches, terminal package) | App preferences | Node id is random, generated once |
| Paired controllers | App preferences | Package + certificate hash |
| Pending pairing list | Memory only | Lost if the app process dies |
| ADB host key | Private no-backup folder | Not included in Android backup |
| Command log | `command-log.jsonl` in app files (desktop: `~/.rootforge/command-log.jsonl`) | Redacted; desktop API token is **not** this file |
| Library images / zips / APKs | App files `library/` (desktop: `~/.rootforge/library`) | Copies you imported |
| Build catalog | App files `build-catalog/` | Last good copy only |
| Desktop API token | `~/.rootforge/desktop.properties` | Created mode `0600`; a world-readable copy is tightened before it is read |

Shizuku lookup output is shown on the Settings card only and is **not** written to the command log.

### Network

- **Catalog Download:** two https GETs to raw.githubusercontent.com, only when you tap Download. 8 MiB cap. http is refused.
- **Browser links:** TWRP / OrangeFox / PitchBlack / LineageOS / module repos / Shizuku / Root Guide external links open the system browser. The app does not download those files.
- **Ask** and **Root Guide** and **Help** are offline.
- Device/USB work never uses the network.

Coming in the next update, Custom Recovery lookup will also use https (TWRP / PitchBlack hosts only) and only when you tap **Look up** or **Download**.

### What the controller can see

A paired controller with the switch on can list USB targets and read the connected target's properties, run an approved shell command, and request flashes of **library** images. It cannot read your desktop API token, your ADB private key, or files outside the library name list.

### What we do not collect

No accounts. No cloud. No phone-home. Uninstalling the app (or the one-time v0.2.0 uninstall) deletes its private data on that phone.

---

*This manual matches the code on the `docs/manual` branch. Desktop mode and the rooted TWRP / PitchBlack installer are marked coming next because they are still open pull requests. If a line here disagrees with the running app, the app is right — please file an issue.*
