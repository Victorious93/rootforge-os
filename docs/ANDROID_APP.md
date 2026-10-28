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

Dashboard (target + props, Custom RR builds for the target — TWRP/PitchBlack rows can open Custom Recovery; see `docs/CUSTOM_RR_CATALOG.md` — **Desktop mode** via VictorSuite), **Devices** (every attached phone at once: read-only identity from `BrandCatalog`, masked serial, catalog Root plan with official unlock portal + unlock-data step, brand Root wizard from `RootGuides` with Compose step diagrams; per-serial lock; EDL identify-only; Samsung Odin is explanation/link-only; never auto-sends device data to a site), **Ask** (offline question box over curated ADB/root answers and the guide), **Root Guide** (offline rooting documentation with search; see `docs/ROOT_GUIDE.md`), **Help** (the bundled how-to, `docs/MANUAL.md`; also from Settings → Open the manual), **Links** (official https directory for Magisk/KernelSU/APatch, module repos including Xposed / LSPosed / Vector, Shizuku apps including Sui, Bootloader unlock portals, Official firmware and EDL tools, and ESP32 & Pi helpers; browser only; also from Modules, Settings, and Help), **Glossary** (everyday-words definitions; also from Help and Links), **Flash Tools** (USB/OTG scan, connection guide, ADB tools, fastboot tools, image/APK inspector, persistent command log), ADB (shell, install, push/pull, sideload, reboot, logcat), Fastboot (getvar, flash, boot-once, lock/unlock, slot, erase), **MSM / EDL** (Qualcomm 9008 guide: MSM Download Tool explained, official firmware links, never ships the tool; experimental Sahara/Firehose still typed-`EDL`), Root/Patching, Modules (module creator and repository links), Recovery (USB fastboot), **Custom Recovery** (rooted `su`/`dd` installer for official TWRP/PitchBlack; see `docs/CUSTOM_RECOVERY_INSTALLER.md`), Logs/Terminal, Settings (optional Shizuku / Sui status and read-only lookups on this phone).

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

The launcher icon is the emblem from `assets/logo/rootforge-os-logo.jpg` (fist, anvil and rings; no text). Adaptive layers live under `app/src/main/res/` (`drawable-xxxhdpi/ic_launcher_foreground.png`, `ic_launcher_monochrome.png`, `@color/ic_launcher_background` = `#0C1D36`); legacy mipmaps are `ic_launcher` and `ic_launcher_round` at mdpi–xxxhdpi. Regenerate with `python3 android-app/tools/generate_launcher_icons.py` (needs Pillow). The previous orange anvil vector is gone.

## Limits (honest)

- **Flash Tools** (and every other flash button) now requires a sidecar device claim. An image with no `<name>.manifest.json` is refused, including from the dedicated Fastboot / Recovery / Root screens. **Not run against a real host/target pair.**
- **EDL / Firehose is experimental.** Sahara identity and programmer upload are implemented and tested against a fake device. No 9008 hardware was available. Retail phones accept only the OEM-signed programmer for that exact model; this app ships none and cannot bypass Secure Boot. A later JNI path that reuses EDL-Commander Rust is planned only (`docs/FLASH_TOOLS_PLAN.md` Phase 4); it is not implemented.
- Sparse images that fit in one `download:` are flashed; a **raw** image larger than `max-download-size` is refused (use `img2simg` or a desktop fastboot).
- ADB uses classic `shell:` (exit status via an echo marker), not shell_v2. No `adb forward`.
- Sideload (`sideload-host`) seeks the library file so recovery can read the zip central directory, then the payload, then the package again. `DONEDONE` finishes the transfer; `FAILFAIL` is reported as a failure. **Not run against a phone in recovery.**
- Logcat holds the USB connection; other actions are blocked until you stop it.
- The `INTERNET` permission is used only when you tap a download/lookup button: **Download** on the Dashboard's "Builds for this device" card (two https GETs to raw.githubusercontent.com) and **Custom Recovery** lookup/download of official TWRP (`twrp.me` / `dl.twrp.me`) and PitchBlack (`pitchblackrecovery.com`, `pitchblack.tech`, GitHub, SourceForge) files. **Import file** still uses no network. The Custom RR card does not feed `DeviceMatchGate`. A matching TWRP or PitchBlack row can open Custom Recovery already aimed at that project; ROMs, root solutions, OrangeFox, SHRP and RedWolf stay browser links (`docs/CUSTOM_RR_CATALOG.md`). Custom Recovery writes are a separate rooted `su`/`dd` path (`docs/CUSTOM_RECOVERY_INSTALLER.md`) and never run unless you turn dry-run off and type `FLASH RECOVERY`. Opening TWRP/OrangeFox/LineageOS links on the Recovery screen still uses the system browser.
- **Links** is a drawer screen (`:core` `DirectoryLinks`) of official https pages: Magisk, KernelSU, KernelSU Next, APatch; Magisk-Modules-Alt-Repo, Magisk-Modules-Repo, MMRL, Xposed-Modules-Repo, LSPosed, Vector, Zygisk Next, ReZygisk; Shizuku, Sui, App Ops, Ice Box, Hail, SystemUI Tuner, Canta, Inure, aShell, aShell You, Dhizuku; plus **Bootloader unlock portals** (`UnlockPortals`: Motorola, Sony Open Devices, Xiaomi Mi Unlock, HTCdev, OnePlus, Fairphone live https pages; Pixel / Nothing have no token page; Samsung / ASUS / LG / Huawei closed); plus **ESP32 & Pi helpers** (`BoardHelpers`: Raspberry Pi as Linux host, ESP32 USB-serial bridge, platform-tools timing; owner unlock/flash only — screen-lock and FRP bypass are refused). Reachable from Modules, Settings → About, and Help. Opens the system browser only (`AppActions.openUrl` refuses non-https). Nothing is downloaded or bundled, and serial / IMEI / unlock data are never sent to a site. `:core` `DirectoryLinksTest` requires every URL (including extras) to be unique https, and requires Xposed, LSPosed, Vector and Sui to stay on the list. **Not run on a phone.**
- **Glossary** is a drawer screen (`:core` `Glossary`) of one short everyday-words line per term, including Download mode, Odin, Knox and Mi Unlock. Reachable from Help and Links. The same text is the **Glossary** section of `docs/MANUAL.md` (bundled in Help). Reading only; official pages stay on Links. `:core` `GlossaryTest` requires every entry to be non-empty and unique. **Not run on a phone.**
- **Modules** has three tabs. *Create* builds a Magisk-format zip (also installed by KernelSU and APatch) with optional `boot-completed.sh` / `action.sh`, your own script text per file, and hedged per-manager notes. *This phone* (Android APK) lists `/data/adb/modules` read-only and can install a library zip on the phone running the app via `su` (`magisk --install-module`, `ksud module install`, `apd module install`), auto-detecting the manager with a manual override. Dry-run is the default and shows the command; a real install needs typed `INSTALL MODULE` and the zip SHA-256; RootForge never reboots (separate Reboot button). *Repositories* only links to module repositories (`ModuleRepos`; each entry says what was checked and when, or that it was not) and lets you import a downloaded zip to install it on a USB target or on this phone. RootForge does not fetch, index or vet modules. A **Links directory** button opens the fuller official-URL list. **Installing any module** (built or downloaded, USB or this phone) needs a typed `INSTALL MODULE` confirmation showing the zip's SHA-256. LSPosed modules stay APKs (ADB → Install APK). Desktop has no this-phone su path. **Not run on a phone.**
- **Terminal app hand-off:** Settings → Terminal app stores a package name (default `com.termux`, editable, empty turns it off); ADB → Shell → Open full terminal launches that app. Launch only, nothing is passed to it. Not run on a phone. Desktop has no hand-off.
- **Desktop mode:** Dashboard card that talks to VictorSuite (`com.termux`, confirmed 2026-10-20) by package + signing certificate, never a shared key. After a pin, Start/Setup/Status/Stop send either the dedicated `ai.victorsuite.action.DESKTOP_MODE` intent (specified in `docs/DESKTOP_MODE.md`; VictorSuite does not export it yet) or Termux `RUN_COMMAND` with only `rootforge-desktop-mode <op>`. Missing VictorSuite shows a hint and does not crash. Toolkit scripts: `setup_desktop_mode.sh`, `rootforge_desktop_mode.sh`, `termux/desktop-mode-entry.sh`. **Not run on a phone.** The desktop JVM client hides the card.
- **Fleet theme (read-only):** Android app applies VictorLaunch's `FLEET_THEME` palette (`schemaVersion` 1) from `content://com.victorious.launch.theme/current`. A `ContentObserver` plus an exported receiver for `com.victorious.launch.action.FLEET_THEME_CHANGED` re-query and re-apply live. The provider and the broadcast sender are checked by package (`com.victorious.launch`) plus the published debug cert SHA-256 (not a signature permission). On API 34+ `sentFromPackage` must belong to `sentFromUid`. `schemaVersion != 1` is ignored. Palette keys apply one at a time (`#RRGGBB` / `#AARRGGBB`); a bad or missing key keeps that role's RootForge default. A truncated `json` column can still use the colour columns. Overlapping refreshes keep the latest read. Missing provider or `SecurityException` keeps RootForge's own theme; Settings → About shows a quiet "not available" / "not pinned" line. RootForge never writes the provider. Desktop does not query. **Not run on a phone.**
- **Ask** matches your question's keywords against curated answers (`AskTopics`) and the Root Guide. It is not an AI model, needs no network, cannot see the target and returns nothing when nothing matches. Not run on a phone; no Paparazzi golden yet.
- **Shizuku / Sui (this phone, optional):** Settings shows one of four fleet states (`GRANTED` / `DENIED` / `NOT_INSTALLED` / `NOT_RUNNING`). Sui counts as present when the manager package is visible **or** the binder is alive. When `GRANTED`, read-only lookups on **this** phone go through `PrivilegedShell` (bound UserService `IPrivilegedShell`: `exec`, `readDeviceState`; `pmGrant` exists for interop only and is not shown). Every privileged call asks for confirmation first; `pmGrant` also needs a typed `GRANT PERMISSION`. The rest of the app works if Shizuku is missing. **No flash, unlock, wipe or write is exposed in the UI.** Command output is shown on the Settings card only and is never logged. Not run on a phone.
- **Root Guide** is bundled text from `:core` resources, read-only, and never touches the target. Its pages are community-written reference material that RootForge has not verified on hardware. The `:app` build, a Paparazzi golden and a run on a phone have not been done for it (`docs/ROOT_GUIDE.md`).

## droidcommand-ai

RootForge is an **independent node**, not a companion APK. See `docs/DROIDCOMMAND_INTEGRATION.md`.

The debug APK (`ai.rootforge.os.debug`) exports `ai.rootforge.os.action.BRIDGE`. Pairing is Settings → External control: copy the node id, let DCA send `rootforge.capabilities.get` (legacy) or `capabilities.get` (protocol 1.1 fleet envelope), then Pair the pending package+cert. The switch defaults off. EDL, sideload-zip and raw `fastboot oem` are GUI-only. Fleet 1.1 (`capabilities.get`, `remote.command`, `macro.step`, `macro.run`, `node.status.get`): `docs/MANUAL.md`.

## License

The USB/protocol code and Compose UI in `:app` are GPL-3.0-or-later
(`LICENSE` at the repository root). Shared rules in `android-app/core`
(`:core`) are Apache-2.0 (`android-app/core/LICENSE`). See `NOTICE`.
