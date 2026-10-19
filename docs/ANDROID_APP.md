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

Dashboard (target + props), **Ask** (offline question box over curated ADB/root answers and the guide), **Root Guide** (offline rooting documentation with search; see `docs/ROOT_GUIDE.md`), **Flash Tools** (USB/OTG scan, connection guide, ADB tools, fastboot tools, image/APK inspector, persistent command log), ADB (shell, install, push/pull, sideload, reboot, logcat), Fastboot (getvar, flash, boot-once, lock/unlock, slot, erase), EDL, Root/Patching, Modules (module creator and repository links), Recovery, Logs/Terminal, Settings.

Flash Tools is a hub: buttons call the same `TargetController` methods as the dedicated screens. A flash is **refused** unless a sidecar `<image>.manifest.json` matches the target (product/codename, slot, and fingerprint *or* a declared product/slot claim). A mismatch is logged as `Refused: …` and nothing is written. Plan: `docs/FLASH_TOOLS_PLAN.md`.

Writes (flash, erase, unlock, lock, slot, Firehose program, EDL programmer upload) print a plan and require a **typed phrase**. There is no setting that skips that.

Patching is **not** reimplemented: the app pushes a boot image to `/sdcard/Download`, opens Magisk / KernelSU / APatch on the target, then pulls `magisk_patched-*.img` (etc.) and flashes it in fastboot. Use **Boot once** before a permanent write.

## Build and install

Published debug APK (v0.2.0, signed with a per-machine auto-generated Android debug key, **not** release-signed; see "Debug signing key" below before updating from it):
https://github.com/Victorious93/rootforge-os/releases/download/v0.2.0/rootforge-os-android-debug.apk

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

### Debug signing key

Debug builds (local and CI) are signed with the committed debug keystore `android-app/app/debug.keystore` (alias `androiddebugkey`, store and key password `android`). It is **for debug builds only**, is not a secret, and must never sign a release; release signing is separate (`ROOTFORGE_KEYSTORE_*`). No CI secret is needed. Certificate SHA-256:

```
77:7A:C7:00:87:05:A8:B4:B8:93:AC:EF:0E:03:E9:1C:7F:49:52:C9:52:FE:B2:6E:06:D7:90:B9:14:86:64:6F
```

Every debug build now carries the same signing certificate, so a new debug APK installs over an older one with `adb install -r`. **The earlier v0.2.0 debug APK was signed with a different (auto-generated, per-machine) debug key: uninstall it once (`adb uninstall ai.rootforge.os.debug`) before installing a build signed with this key.** App data is lost by that uninstall.

Check an APK: `apksigner verify --print-certs app-debug.apk` (SHA-256 digest) against `keytool -list -v -keystore app/debug.keystore -storepass android`.

Paparazzi goldens: `android-app/app/src/test/snapshots/`. Human-facing copies: `android-app/screenshots/`.

## Limits (honest)

- **Flash Tools** (and every other flash button) now requires a sidecar device claim. An image with no `<name>.manifest.json` is refused, including from the dedicated Fastboot / Recovery / Root screens. **Not run against a real host/target pair.**
- **EDL / Firehose is experimental.** Sahara identity and programmer upload are implemented and tested against a fake device. No 9008 hardware was available. Retail phones accept only the OEM-signed programmer for that exact model; this app ships none and cannot bypass Secure Boot. A later JNI path that reuses EDL-Commander Rust is planned only (`docs/FLASH_TOOLS_PLAN.md` Phase 4); it is not implemented.
- Sparse images that fit in one `download:` are flashed; a **raw** image larger than `max-download-size` is refused (use `img2simg` or a desktop fastboot).
- ADB uses classic `shell:` (exit status via an echo marker), not shell_v2. No `adb forward`.
- Sideload (`sideload-host`) seeks the library file so recovery can read the zip central directory, then the payload, then the package again. `DONEDONE` finishes the transfer; `FAILFAIL` is reported as a failure. **Not run against a phone in recovery.**
- Logcat holds the USB connection; other actions are blocked until you stop it.
- No internet permission. Opening TWRP/OrangeFox/LineageOS links uses the system browser.
- **Modules** has two tabs. *Create* builds a Magisk-format zip (also installed by KernelSU and APatch) with optional `boot-completed.sh` / `action.sh`, your own script text per file, and hedged per-manager notes. *Repositories* only links to module repositories (`ModuleRepos`; each entry says what was checked and when, or that it was not) and lets you import a downloaded zip to install it. RootForge does not fetch, index or vet modules. **Installing any module** (built or downloaded) now needs a typed `INSTALL MODULE` confirmation showing the zip's SHA-256. Not run on a phone or against a real manager; the Modules Paparazzi golden (`modules.png`) is stale because the screen gained a tab row and needs re-recording with the Android SDK.
- **Terminal app hand-off:** Settings → Terminal app stores a package name (default `com.termux`, editable, empty turns it off); ADB → Shell → Open full terminal launches that app. Launch only, nothing is passed to it. Not run on a phone. Desktop has no hand-off.
- **Ask** matches your question's keywords against curated answers (`AskTopics`) and the Root Guide. It is not an AI model, needs no network, cannot see the target and returns nothing when nothing matches. Not run on a phone; no Paparazzi golden yet.
- **Root Guide** is bundled text from `:core` resources, read-only, and never touches the target. Its pages are community-written reference material that RootForge has not verified on hardware. The `:app` build, a Paparazzi golden and a run on a phone have not been done for it (`docs/ROOT_GUIDE.md`).

## droidcommand-ai

RootForge is an **independent node**, not a companion APK. See `docs/DROIDCOMMAND_INTEGRATION.md`.

The debug APK (`ai.rootforge.os.debug`) exports `ai.rootforge.os.action.BRIDGE`. Pairing is Settings → External control: copy the node id, let DCA send `rootforge.capabilities.get`, then Pair the pending package+cert. The switch defaults off. EDL, sideload-zip and raw `fastboot oem` are GUI-only.

## License

The USB/protocol code and Compose UI in `:app` are GPL-3.0-or-later
(`LICENSE` at the repository root). Shared rules in `android-app/core`
(`:core`) are Apache-2.0 (`android-app/core/LICENSE`). See `NOTICE`.
