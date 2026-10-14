# RootForge OS Android app — host phone controls a target phone

The APK in `android-app/` runs on a **host** Android phone and drives a **target** Android phone plugged into it with a USB-C to USB-C cable (USB OTG / USB Host). It is a button-first toolkit: ADB, fastboot, EDL, Magisk/KernelSU/APatch patching workflows, a module zip builder, recovery install, and a secondary terminal/log panel. It is **not** a Termux fork and it does not embed a Linux userspace.

Status: **compiles, hermetic protocol tests pass, Paparazzi screenshots recorded. Not run on a real host/target pair.**

## Why USB Host + pure Kotlin (not bundled `adb`)

Three ways to talk to a phone from another phone:

| Approach | Verdict |
|---|---|
| Shell out to a bundled `adb`/`fastboot` binary | Those binaries expect a desktop USB stack (`/dev/bus/usb` + udev). On Android they need root, `libusb` hacks, or a VPN/ADB-over-network. Fragile, and a second copy of Google's adb is a supply-chain surface. |
| ADB over TCP (`adb connect IP`) | Needs the target already in TCP mode and a network path. Useless for fastboot, EDL, a target with no Wi-Fi, or a brick. |
| **USB Host API + the ADB/fastboot/Sahara wire protocols in Kotlin** | Matches how the cable actually works. `UsbManager` claims the interface the host OS already enumerated. The protocol code is unit-tested against an in-memory pipe, with no native code. |

The implementation lives in `android-app/app/src/main/kotlin/ai/rootforge/os/{adb,fastboot,edl,usb}/`. `BulkTransport` is the only USB seam; Android's `UsbDeviceConnection.bulkTransfer` is one implementation, tests use another.

## Cable setup

1. Use a **USB-C to USB-C data cable** (charge-only cables enumerate nothing).
2. Plug one end into the host (this app), the other into the target. The host must act as USB **host**. Most phones switch automatically when they see a device; if not, put a USB-C OTG adapter on the **host** end.
3. On the **target**:
   - **ADB:** Developer options → USB debugging on, USB mode = File transfer. Accept the RSA dialog the first time (`Settings` in this app shows the MD5 fingerprint the dialog displays).
   - **Fastboot:** reboot to bootloader (ADB screen → Bootloader, or the target's key combo).
   - **EDL:** Qualcomm 9008. `adb reboot edl` on some devices; otherwise the model's test-point / key combo. See the EDL screen.
4. On the **host**, accept Android's "allow this app to access the USB device?" prompt, then Dashboard → Connect.
5. If the target charges from the host and drains it, charge the host first or use a powered USB-C hub with pass-through.

The app declares `android.hardware.usb.host` as required. Tablets/phones without USB host hardware cannot install it.

## Screens

Dashboard (target + props), ADB (shell, install, push/pull, sideload, reboot, logcat), Fastboot (getvar, flash, boot-once, lock/unlock, slot, erase), EDL, Root/Patching, Module Builder, Recovery, Logs/Terminal, Settings.

Writes (flash, erase, unlock, lock, slot, Firehose program, EDL programmer upload) print a plan and require a **typed phrase**. There is no setting that skips that.

Patching is **not** reimplemented: the app pushes a boot image to `/sdcard/Download`, opens Magisk / KernelSU / APatch on the target, then pulls `magisk_patched-*.img` (etc.) and flashes it in fastboot. Use **Boot once** before a permanent write.

## Build and install

```bash
cd android-app
# SDK: ANDROID_HOME or a local.properties sdk.dir
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # protocol tests + Paparazzi
```

Install on the host:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK uses applicationId `ai.rootforge.os.debug`. A release APK is built in CI when `ROOTFORGE_KEYSTORE_BASE64` (and the matching password/alias secrets) are set; otherwise only debug.

Paparazzi goldens: `android-app/app/src/test/snapshots/`. Human-facing copies: `android-app/screenshots/`.

## Limits (honest)

- **EDL / Firehose is experimental.** Sahara identity and programmer upload are implemented and tested against a fake device. No 9008 hardware was available. Retail phones accept only the OEM-signed programmer for that exact model; this app ships none and cannot bypass Secure Boot.
- Sparse images that fit in one `download:` are flashed; a **raw** image larger than `max-download-size` is refused (use `img2simg` or a desktop fastboot).
- ADB uses classic `shell:` (exit status via an echo marker), not shell_v2. No `adb forward`.
- Sideload (`sideload-host`) expects the file to be read forward; a target that re-requests an earlier block is refused.
- Logcat holds the USB connection; other actions are blocked until you stop it.
- No internet permission. Opening TWRP/OrangeFox/LineageOS links uses the system browser.

## droidcommand-ai

RootForge is an **independent node**, not a companion APK. See `docs/DROIDCOMMAND_INTEGRATION.md`.

The debug APK (`ai.rootforge.os.debug`) exports `ai.rootforge.os.action.BRIDGE`. Pairing is Settings → External control: copy the node id, let DCA send `rootforge.capabilities.get`, then Pair the pending package+cert. The switch defaults off. EDL, sideload-zip and raw `fastboot oem` are GUI-only.
