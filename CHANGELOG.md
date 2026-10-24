# Changelog

All notable changes to RootForge OS. First published GitHub Release is **v0.2.0**
(tag on `e1f84ea`). Older tags `v0.1.0` and `v0.1.1` have no Release.
Nothing here has been validated on real hardware, a booted ISO, a VM or a phone; see
`docs/PLATFORM_SUPPORT.md`.

## Unreleased

### Secret redaction: names glued to non-ASCII text, and the quadratic Python rule (2026-10-24)
Both were reported by the bug sweep and held for the owner's decision (they touch secret redaction); the owner said go ahead.
- **The leak.** Under a UTF-8 locale `redact_text` and `rf_redact` left `密码password=hunter2`, `éapi_key=abc` and `клюapi_key=abc` as they were: the `NAME=value` rule needed a `\b` before the name, and a Unicode-aware `\b` does not see a boundary between a non-ASCII letter and an ASCII one. Python's own locale coercion is enough to trigger it for the sed child process, so the parity test reproduced it with no setup. The Python rule now starts a match only where a `[A-Za-z0-9_.-]` run starts; `rf_redact` runs sed with `LC_ALL=C`, so the result no longer depends on the caller's locale (UTF-8 text and invalid bytes pass through unchanged).
- **The hang.** The old Python rule started at every word boundary inside a long `a-a-a-…` run and re-scanned the run each time: 12 KB took 5.3 s, 40 KB 63 s, 100 KB 395 s. It now takes 6 ms for 40 KB and 14 ms for 100 KB. One lookahead per run decides whether the run holds a secret word, and the run is taken whole.
- Checked: the new rule matched the old one's output on 250,000 random ASCII inputs (`hypothesis`, scratch) and on 20,000 seeded ones inside `tests/test_log.py`, which keeps the old pattern as the reference; the fixed Python and sed engines agreed on 90,000 random lines (ASCII and non-ASCII neighbours, scratch).
- **Not changed, measured:** the private-key block rules are quadratic on odd input too (Python: 5,000 unterminated `-----BEGIN PRIVATE KEY-----` markers take 3.2 s; sed: one such line followed by 1.6 MB of log takes 6.5 s, so a 64 MiB log would take hours). A fix is proposed in `docs/REVIEW.md` and waits for a go-ahead.
- Tests: 6 new sample rows and a locale test in `tests/test_redaction_parity.py`; 3 tests in `tests/test_log.py` (linear time, the non-ASCII names, agreement with the old rule on ASCII). Against the old code the new rows failed in both engines (Python; sed in the default environment and under `C.UTF-8`), the non-ASCII test in `tests/test_log.py` failed, and the timing guard failed (5.7 s against a 1 s limit); the agreement test passes either way by design. `bash tests/run-tests.sh` **1057/0** (Python unittest 419, was 415), `bash tests/lint.sh` clean. `:core`, `:desktop` and `:app` are unaffected (no Kotlin change). **Not run on a phone or a desktop window.**

### Custom Recovery lookup matches the live TWRP and PitchBlack sites (2026-10-24)
With `twrp.me`, `dl.twrp.me`, `pitchblackrecovery.com` and `sourceforge.net` reachable, the lookup was run against the real pages. The old parsers returned **no offer for any device tried**: the unit fixtures had been written to layouts the sites do not use. Each item was reproduced on the saved pages first.
- **TWRP directory.** `dl.twrp.me/<codename>/` links every file as a root-relative `/<codename>/<file>.html` page, and `parseTwrpDirectory` dropped every root-relative link. It now reads those rows, builds the image URL beside the page, and uses the file's own page as the offer's page and its Referer (the image host returns that page's HTML to a request with none). Odin `.img.tar` rows and links to other folders are skipped. The old relative-link form is still read.
- **Codename case.** dl.twrp.me, pitchblackrecovery.com and SourceForge are case-sensitive (`/AI2201/` is a page, `/ai2201/` a 404) and the app passes lower-cased names, so a device whose codename has a capital letter missed (100 codenames in TWRP's list; 41 of 135 PitchBlack folders). TWRP's search match keeps the title's spelling. PitchBlack is tried as given, in lower case, then in the spelling its home page's device list uses (one extra read, once per lookup, only when the first two miss).
- **PitchBlack.** A device page's HTML has no download links; a script loads `/assets/json/builds-<codename>.json`. The lookup reads that list first (`parsePbrpBuilds`: `latest` and `older_builds`, https links on the allow-list only), then the earlier routes. SourceForge's listing links end in `/download`, which the old name check rejected; `parseSourceForgeFiles` reads them and skips folder, stats and info links. The GitHub-release route is unchanged and was not run: `api.github.com` is denied by this session's gateway.
- **Run for real** (scratch tests, not committed) through `RecoveryCatalogClient` and `RecoveryFetcher` from this JVM, with lower-cased names as the app passes them: TWRP `enchilada` 36 offers and `x00p` 11 (`dl.twrp.me/X00P/`); PitchBlack `enchilada` 12, `x00p` 3, `i01wd` 1; a device neither site lists (`sunfish`) comes back empty without an error. `twrp-3.2.1-0-enchilada.img` downloaded 27,389,952 bytes starting `ANDROID!`, and the site's published `.md5` matched the file. `PBRP-olive-3.1.0-20210820-2120-OFFICIAL.zip` downloaded through the SourceForge redirect, 54,096,180 bytes starting `PK\x03\x04`.
- **Not done:** TWRP's `<file>.md5` / `.sha256` sit next to every image but only each file's own page links them, so a TWRP offer still carries no published checksum (a download is checked for being an image or zip, and its SHA-256 is shown). Details in `docs/REVIEW.md` and `docs/CUSTOM_RECOVERY_INSTALLER.md`.
- Tests: 11 new in `RecoveryCatalogTest` (excerpts of the live pages as fixtures). The 8 that can run on the old code failed there; the other 3 call APIs it does not have. `:core:test` **464/0** (was 453), `:desktop:test` 9/0, `:app:testDebugUnitTest` 39/0, `:app:assembleDebug` builds, `bash tests/run-tests.sh` **1057/0**, `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not run on a phone or a desktop window.**

### Publish an APK release from the GitHub web UI (2026-10-24)
- New `.github/workflows/publish-release.yml` ("Publish release", manual only). Actions -> Publish release -> Run workflow -> type the tag (e.g. `v0.3.0`). It refuses unless it runs from `main`, the tag is `vMAJOR.MINOR.PATCH` (surrounding spaces trimmed), the tag does not exist yet, and it equals `v` + `versionName` in `android-app/app/build.gradle.kts`. It then runs `:core:test :app:testDebugUnitTest :app:assembleDebug`, and `softprops/action-gh-release@v2` creates the tag on the run's commit and a **published** release with `rootforge-os-android-debug.apk` and its `.sha256`.
- Why: the owner releases from a phone; creating `v0.3.0` from the web form failed ("can't create that tag", exact cause not established), and this agent's session cannot push tags (HTTP 403 from its own network policy).
- Debug APK only. A tag created with `GITHUB_TOKEN` does not start `release.yml`, so the ISO / Termux rootfs path is unchanged and still needs a tag push.
- Checked: YAML duplicate-key loader (the `yaml-lint` CI check) and `actionlint` 1.7 with shellcheck 0.11.0 on the new file: clean. **Not run on GitHub yet**; the first real run is the test. If a tag ruleset is what blocked the web form, this workflow would be refused the same way unless the rule lets GitHub Actions bypass it.

### Android app version 0.3.0 / versionCode 2 (2026-10-24)
- `android-app/app/build.gradle.kts`: `versionCode` 1 -> 2, `versionName` `0.1.0` -> `0.3.0` (debug builds report `0.3.0-debug`). Every APK since v0.2.0 still said `0.1.0`, so builds could not be told apart. Prepares a `v0.3.0` tag; nothing is tagged or published by this change. `applicationId` and signing unchanged. The desktop package (`0.1.0`) and Docker API `app_version` are not bumped.
- **Upgrading from the v0.2.0 APK needs one uninstall:** v0.2.0 was signed with CI's throwaway debug key, and later builds use the committed `app/debug.keystore` (#62, cert SHA-256 `77:7A:C7:00:…:64:6F`).
- Version tripwires in `tests/test_launcher_icons.py` and `tests/test_manual.py` now expect the new values.
- Tests: `:core:test` **453/0**, `:app:testDebugUnitTest` **39/0**, `:app:verifyPaparazziDebug` passes, `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode='2'` / `versionName='0.3.0-debug'`; `apksigner` cert SHA-256 `777ac700…86646f`, the committed key), `bash tests/run-tests.sh` **1057/0**, `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not installed on a phone.**

### Bug sweep 2026-10-24
Each item was reproduced first, and its regression test was run against the old code and failed before the fix.
- **`getvar all` names with an argument were mangled (Kotlin).** `FlashPlanner.parseVarLine` split on the first `:`, so `partition-size:boot_a: 0x4000000` became key `partition-size` / value `boot_a: 0x4000000` and `putIfAbsent` kept only the first partition; Partition info listed one. It now splits on the first `": "`, as `core/device.py` and `docs/ARCHITECTURE.md` §3.1 already say. Affects the USB client, the desktop controller and device identify.
- **Approval prompt showed less than it ran.** The fleet and legacy bridges printed `value.toString().take(300)` while `adb.shell` runs up to 2000 characters, so a paired controller could hide the tail of a command from the person approving. Values are now shown whole; a value over 12,100 JSON characters is refused before any prompt (`bridge/ApprovalPrompt.kt`).
- **A nested request value crashed the bridge.** `JsonElement.toString()` recurses per level: a ~4 KB `command` nested 2,000 deep threw `StackOverflowError` (an `Error`, which `catch (e: Exception)` does not catch) in the Android service coroutine. Nested values are now refused as `invalid_parameters` / `bad_parameters` before anything is printed.
- **Identities were stored where no card looks.** `publishIdentity` keyed by the identity's own serial; the roster looks up the attached target's key. For a Wi-Fi ADB phone (`ip:port`), a Qualcomm 9008 port (Sahara chip serial) or a bootloader whose `serialno` differs from its USB string, the identity never showed and the desktop 2 s loop re-ran `getprop` and `pm list packages` forever. `DeviceIdentity.targetKey` is set by the target-based parser entry points; the displayed serial is unchanged.
- **Built module zips had names the library does not have.** `BuiltModule.fileName` kept spaces, `+` and `/` from the free-text version while `Library.fileFor` rewrites them, so the Modules screen never found the zip it had just built and `module.build` returned a `library_file` that does not resolve. The name is now already a library name.
- **`RecoveryCatalogClient.extractCodenames` regex was quadratic** on remote titles (5 s on 30k characters; the fetch cap is 8 MiB). Titles over 300 characters are ignored.
- **A faulty or hostile target sized a buffer.** ADB sync `DATA` / `FAIL` and Sahara command-response lengths were allocated as sent (an `OutOfMemoryError` is an `Error`). They are bounded (1 MiB / 64 KiB).
- **Docker toolkit API** (`docker/api/rootforge_api.py`): the generated token file was written, then `chmod`ed, at a predictable `/tmp/api.token` and followed a planted symlink, overwriting and re-moding its target (reproduced). It is now created `0600` with `O_EXCL`. `Content-Length: -1` hung a handler thread; a non-numeric one, and a 60 KB nested body, dropped the connection with no response; all are now `400`. Error replies that leave a body unread close the connection. The token is compared with `hmac.compare_digest`. The desktop `HeadlessApi` compares in constant time too (`HeadlessApi.tokenMatches`).
- **`lint_module.sh`** wrote `sh -n` errors to the fixed `/tmp/lint_module_syntax_err` and wrote through a planted symlink (reproduced). It uses `mktemp`, removed by the existing EXIT trap.
- **Found and not fixed at the time** (touches secret redaction, so left for the owner; fixed later the same day, see the entry at the top): `redact_text` and `rf_redact` leave `密码password=hunter2` and `éapi_key=abc` unredacted under a UTF-8 locale, and the Python pattern is quadratic (40 KB of `a-a-a-…` took 63 s; sed 0.03 s). A verified fix and the other open observations are in `docs/REVIEW.md` § Bug sweep 2026-10-24 and `docs/SECURITY_MODEL.md` §2.
- Tests: `:core:test` **445/0** (was 424), `:desktop:test` **8/0** (was 4), `:app:testDebugUnitTest` **39/0** (was 36; `verifyPaparazziDebug` passes), `:app:assembleDebug` (`ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`), `:app:lintDebug` 0 errors, `bash tests/run-tests.sh` **1057/0** (Python unittest 414, was 405), `bash tests/lint.sh` clean. `applicationId`, signing and versioning unchanged. **Not run on a phone, a desktop window, a Docker build (no daemon), or the live TWRP / PitchBlack pages (network policy).**


### Custom Recovery: do not write over a missing or short backup (2026-10-22)
- A recovery `dd` whose shell closed before the exit trailer (`RootShellResult.exitCode == null`) was treated as success. The installer then pulled whatever was at the backup path and wrote the new image. A missing status is now a failed backup, and a pulled backup whose length is not the partition size is deleted and the write is refused.
- Tests: `RecoveryInstallerTest` (missing exit, short backup, full backup still written). `:core:test` 427/0. `bash tests/run-tests.sh` 1055/0. `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not run on a phone or a desktop window.**

### Custom RR catalog: OnePlus alias table, phone checklist (2026-10-20)
- `:core` `CodenameAliases`: 48 stock-name aliases (OnePlus 3T–9R, Nord / N10 / N200, newer `OP…L1` names) so e.g. `OnePlus6T` finds the catalog's `fajita` rows. RootForge-written from Google's Play supported-devices list joined to the catalog's own model numbers; Custom RR's GPL `codename_aliases.dart` was not copied. Aliases only add lookup names; display-only, never feeds `DeviceMatchGate`. OnePlus 5/5T/6/6T/One match by marketing name (weaker, [Likely]).
- `docs/CUSTOM_RR_CATALOG.md`: alias section and an 8-step owner phone checklist (Download, Import file, real target, fallbacks). Not run on a phone.
- The `modules` Paparazzi golden: `recordPaparazziDebug` produced no diff on current `main`, so the golden was already refreshed by #81/#85; `verifyPaparazziDebug` passes.
- Tests: `:core:test` 424/0 (`CodenameAliasesTest` 5), `:desktop:test` 4/0, `:app:testDebugUnitTest` 36/0, `:app:assembleDebug` (`ai.rootforge.os.debug` / `0.1.0-debug`), `bash tests/run-tests.sh` 1055/0, `bash tests/lint.sh` clean. **Not run on a phone or a desktop window.**

### Confirmations stay on the phone they started on (2026-10-20)
- A destructive or remote action no longer follows a newly attached phone if the original one drops while the confirm dialog is open. Desktop `adb`/`fastboot` keep `-s` on the serial captured when the action started (`DeviceSerialPin`). Connecting another phone is refused while any non-identify action is in flight (`SerialOpLock.connectRefusal`); the desktop auto-connect poll skips that case. Android erase and Firehose program keep the client captured before the dialog. Fleet and legacy bridge Allow for `adb shell` / reboots snapshot the transport epoch and serial and refuse with "device changed" if either moved. Host `edl` (no serial flag) refuses unless the connected 9008 device is the only one, checked again after confirm.
- Approval text shows every parameter in full (`bridge/ApprovalPrompt.kt`, shared with the 2026-10-24 bug sweep). A nested value, or one over 12,100 JSON characters, is refused before any prompt instead of being clipped; a shell command is still limited to 2,000 characters when it runs.
- Docker toolkit `rootforge.adb.shell` uses the same serial pin as flash and reboot (`adb -s`). Omitting `serial` with two phones, or with `ANDROID_SERIAL` pointing at a different phone, no longer runs on an unnamed device.
- `applicationId`, signing and versioning unchanged.
- Tests: `:core:test` **424/0** (`SerialOpLockTest` connect refusal, `FleetExecutorTest` and `BridgeExecutorTest` device-changed plus full shell approval); `:desktop:test` **5/0** (`HostControllerSerialPinTest`: erase stays on `PHONEA` after that phone drops and connect to `PHONEB` is refused); `:desktop:compileKotlin` (auto-connect guard). `tests/test_toolkit_api.py` shell pin. `bash tests/run-tests.sh` **1055/0** (Python unittest discover **406** tests, 0 failures). `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not run:** `:app` unit tests or `assembleDebug` (no Android SDK in this environment), a phone, or a desktop window.

### Broad brand coverage, MSM / EDL guide, infographics (2026-10-20)
- Device-identity table (`:core` `BrandCatalog`) covers Pixel, Samsung, Xiaomi/Redmi/POCO, OnePlus, Oppo, realme, Motorola, Sony, HTC, Fairphone, Nothing/CMF, ASUS, LG, Huawei/Honor, vivo/iQOO, HMD/Nokia, Lenovo, Meizu, ZTE/nubia/REDMAGIC, TECNO, Infinix, itel, TCL/Alcatel, SHIFT. Matching is whole-word tokens (`lg` does not match `google`).
- Brand **Root wizards** and **bootloader unlock portals** follow that table. Live official unlock pages stay Motorola / Sony / Xiaomi / HTCdev / OnePlus / Fairphone. Pixel has no token page. Nothing, Oppo, realme, HMD, Lenovo, Transsion, TCL and SHIFT have no token webpage (in-phone Deep Testing or OEM unlocking). Samsung, ASUS, LG, Huawei, vivo, Meizu and ZTE are noted as no longer offering official unlocking — no invented URL.
- **MSM / EDL** drawer screen (was EDL): when a phone is Qualcomm `05c6:9008`, a plain-language guide explains MSM Download Tool and an EDL restore, with official https firmware/support links for that brand (or the MSM OEM list if the chip did not name a maker). **Never ships** MSM / QFIL / RSA / Mi Flash / firmware. The guide **never writes**. Experimental Sahara/Firehose stays below, still typed `EDL`, still ships no programmer. EDL as FRP/screen-lock wipe and Firehose auth bypass are refused (`MsmGuides.REFUSED`).
- Compose-drawn step diagrams (`WizardDiagrams` + `StepDiagram`) on root wizards, unlock portal cards, and the MSM / EDL guide. Same UI is compiled into the Android app and `:desktop`. No bitmaps, no runtime downloads.
- Links gains **Official firmware and EDL tools**. Glossary: Qualcomm 9008, MSM Download Tool. Ask: `msm-edl-guide`.
- Safety unchanged: dry-run, typed confirm, backup-first, no auto-reboot. `applicationId`, signing and versioning unchanged.
- Tests on this head (after `origin/main` `3d05929`): `BrandCatalogTest` 4/0; `MsmGuidesTest` 5/0; `WizardDiagramsTest` 2/0; `UnlockPortalsTest`; `RootGuidesTest`; `DirectoryLinksTest`; `RootPlanTest` EDL MSM steps; `DeviceIdentityParserTest` remembered OnePlus brand on 9008. `:core:test` **419/0**; `:desktop:test` **4/0**; `:app:testDebugUnitTest` **36/0** (Paparazzi `edl` recorded; `devices` re-recorded); `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`, APK 19 MiB); `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not run on a phone or a desktop window.**

### ESP32 and Raspberry Pi helpers (2026-10-20)
- Owner-only bootloader unlock / flash helpers: `:core` `BoardHelpers` plus **Links → ESP32 & Pi helpers** (official https: Raspberry Pi documentation on GitHub, Espressif USB Serial/JTAG and ESP32-S3, Android platform-tools and AOSP locking/unlocking). A Raspberry Pi reuses `:desktop` `HostController` / `HostToolset` (`LinuxBoard` reads `/proc/device-tree/model`). An ESP32 is documented as a USB-serial CDC bridge only.
- **Refused on ethics grounds (not implemented):** screen-lock bypass, FRP bypass, HID/key injection to type credentials. Shared refuse list in `BoardHelpers.REFUSED`; Ask topic `frp-screen-lock-refused`; comments in `esp32_toolkit.sh` / `rpi_fleet_tools.sh`. Dry-run, typed confirm, backup-first, no auto-reboot unchanged. `applicationId`, signing and versioning unchanged.
- Doc: `docs/BOARD_HELPERS.md`. Glossary: ESP32 helper, Raspberry Pi helper.
- Tests on this head: `BoardHelpersTest` 4/0 (https unique allowed hosts; FRP/screen-lock refuse list); `DirectoryLinksTest` 3/0; `LinuxBoardTest` 1/0; Ask `esp32-pi-helpers` / `frp-screen-lock-refused`. `:core:test` **407/0**; `:desktop:test` **4/0**; `:app:testDebugUnitTest` **35/0** (Paparazzi still match); `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not run on a Pi, an ESP32, a phone, or a desktop window.**

### Official bootloader unlock portals (2026-10-20)
- Devices **Root plan** now shows the matching maker's official unlock-token/code page (`:core` `UnlockPortals`) plus a plain-words **Read unlock data** step (Motorola `fastboot oem get_unlock_data`, HTC `oem get_identifier_token`, OnePlus `getvar serialno`). Dry-run first; typed `UNLOCK` still gates a later unlock. Official pages open in the system browser only. Serial, IMEI and unlock data are **never** sent to a site.
- Live https vendor pages checked 2026-10-20: Motorola, Sony Open Devices, Xiaomi Mi Unlock (`en.miui.com/unlock/`), HTCdev, OnePlus unlock token, Fairphone. Pixel has no token portal (OEM unlocking). Nothing has no token webpage. Samsung, ASUS, LG and Huawei no longer offer official unlocking — in-app note, no invented URL. Sony / HTC / Fairphone still get their portal when the wizard is generic fastboot.
- **Links** gains a **Bootloader unlock portals** section (same table; `DirectoryLink.url` may be null for closed programmes). `:core` `UnlockPortalsTest` and `DirectoryLinksTest` require every portal URL to be unique https on a vendor host.
- Glossary adds Unlock portal (same line in `docs/MANUAL.md` / Help). `applicationId`, signing and versioning unchanged.
- Tests on this head: `UnlockPortalsTest` 5/0 (https unique vendor hosts; `lg` does not match `google`; closed programmes have no URL); `DirectoryLinksTest` 3/0; `RootPlanTest` 9/0 (Motorola portal + `oem get_unlock_data`; Sony generic wizard still gets the Sony portal; EDL/null have no portal); `RootGuidesTest` 9/0; `:desktop` `HostControllerIdentifyTest` 1/0. `:core:test` **403/0**; `:desktop:test` **3/0**; `:app:testDebugUnitTest` **35/0** (Paparazzi `devices` / `links` / `glossary` / `help` still match); `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not run on a phone or a desktop window.**

### Brand root wizards and PC Devices (2026-10-20)
- **Devices** cards now include a data-driven **Root wizard** (`:core` `RootGuides`) from the read-only identity: Google Pixel, Samsung, Xiaomi / Redmi / POCO, OnePlus, Motorola, plus a generic fastboot path. Each step is plain language, names what will be erased, stays dry-run first, needs the same typed phrase as the existing tool, and never auto-reboots. Writes still open Fastboot / Custom Recovery / Root for that serial — the wizard does not flash.
- **Samsung** is explanation plus the existing fastboot Unlock path: Download Mode / Odin are link-only (`odinFlashing=false`); Knox warning is shown; RootForge does not speak Odin or flash a tar. **Xiaomi** notes the Mi Unlock 7–30 day wait (cannot skip). **Motorola** opens the official unlock-code site. Fastboot identify fills brand/model from the USB descriptor when getvar omitted them.
- **PC / `:desktop`:** the same Devices screen and identify/plan path talk to host `adb`/`fastboot` (`adb -s` / `fastboot -s`). Wide windows (≥ 840 dp) show two phone cards side by side (`RootGuides.columnsForWidthDp`). `hostCaption = "this computer"`. `ProcessRunner` is open for tests. `applicationId`, signing and versioning unchanged.
- Glossary adds Download mode, Odin, Knox, Mi Unlock (same lines in `docs/MANUAL.md` / Help).
- Merged `origin/main` `3d05929` (#84 bug sweep). Tests on this head: `RootGuidesTest` 9/0; `DeviceIdentityParserTest` 10/0 (USB-brand fill); `RootPlanTest` 7/0 (PIXEL/SAMSUNG guides; EDL/null stay without a wizard); `:desktop` `HostControllerIdentifyTest` 1/0 (two serials, masked logs, PIXEL+SAMSUNG guides, two-column helper). `:core:test` **396/0**; `:desktop:test` **3/0**; `:app:testDebugUnitTest` **35/0** (Paparazzi `devices` / `glossary` / `help` still match); `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not run on a phone or a desktop window.**

### Multi-device identify (2026-10-20)
- New **Devices** drawer screen lists every USB phone at once (ADB, fastboot, Qualcomm EDL `05c6:9008`). Plug-in is `UsbManager.ACTION_USB_DEVICE_ATTACHED` plus a 2 s poll of the USB list (Android) or `adb devices` / `fastboot devices` / `lsusb` (desktop). Identify is read-only: brand, model, `ro.product.device`, SoC/platform, Android version, security patch, bootloader lock, Magisk/KernelSU/APatch/none, A/B slot, USB mode. Fastboot uses `getvar` (`product`, `unlocked`, `current-slot`, …). EDL is Sahara/VID-PID identify only — Devices never starts Firehose.
- Serials are shown and logged as `••••` plus the last four characters (`SerialMask`). Each card has a **Root plan** (unlock → recovery → root manager) using matching Custom RR catalog rows; a TWRP/PitchBlack match can open Custom Recovery pre-filled. Per-device buttons open the existing tools aimed at that serial.
- Safety unchanged: dry-run default, typed confirm, backup before a recovery write, never auto-reboot. The old global op mutex is now `SerialOpLock` (one mutex per serial) so two phones can work at once. `applicationId`, signing and versioning unchanged.
- Tests after `origin/main` `3f7661d` (#83 config.export): `DeviceIdentityParserTest` 9/0 (Pixel, Samsung, Xiaomi, OnePlus, Motorola getprop + Pixel/Samsung getvar + EDL 9008 + KernelSU/APatch packages); `SerialMaskTest` 3/0; `RootPlanTest` 7/0; `SerialOpLockTest` 3/0; `UsbScanFilterTest` 6/0. `:core:test` **378/0**; `:desktop:test` 2/0; `:app:testDebugUnitTest` 35/0 (Paparazzi `devices` recorded; `dashboard` / `glossary` re-recorded); `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). Docs: `docs/MANUAL.md` Devices + Glossary Serial/SoC/Slot/USB mode (same text in Help). **Not run on a phone.**

### Bug sweep 2026-10-20
- Fleet replay cache no longer drops a still-live nonce when it hits the 4,096-entry cap. A full cache is `internal` (`too many recent fleet nonces`) so the 10-minute retain window stays intact (`ReplayGuard`).
- Custom Recovery refuses a write (and a dry-run) when `blockdev --getsize64` failed or returned nothing. Unknown partition size is not safe (`RecoveryPlanner`).
- `config.import` rejects `autoConnect` values other than `true`/`false` and `terminalApp` values that are not a valid Android package name (same rule as Settings). Empty `terminalApp` still clears it. Remote callers stay `capability_off`; external control is unchanged.
- Android library/image header reads no longer call `InputStream.readNBytes` (API 33). They use `:core` `readPrefix`, which is safe on minSdk 26. `:app:lintDebug` NewApi on those call sites is gone.
- Tests on that head: `:core:test` **363/0**; `:desktop:test` 2/0; `:app:testDebugUnitTest` 34/0; `:app:assembleDebug` (`ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`); `:app:lintDebug` 0 errors (24 pre-existing warnings); `bash tests/run-tests.sh` **1055/0**; `bash tests/lint.sh` clean (shellcheck 0.11.0). **Not run on a phone.**
- `applicationId`, signing and versioning unchanged.

### Fleet config.export / config.import (2026-10-20)
- Protocol 1.1 standalone Bridge ops `config.export` and `config.import` on the frozen EXTERNAL_NODE envelope (droidcommand-AI `docs/specs/UNIFIED_BACKUP.md` / DCA #236). Advertised in `capabilities.get` as `config.export` `{kind:action, remoteAllowed:false, requiresConfirm:false}` and `config.import` `{kind:action, remoteAllowed:false, requiresConfirm:true}`. Same package+certificate pairing as the other 1.1 ops; a remote caller is `capability_off` for both.
- Export is non-secret preferences only (`autoConnect`, `terminalApp`). Magisk / KernelSU / APatch tokens, root passwords, signing material, pairing keys / allow-list secrets, the VictorSuite pin and the desktop API token are never included. Import accepts `dryRun` and `conflictPolicy` `skip` \| `overwrite` \| `merge-by-id`, needs on-device `ApprovalCenter` confirm, never turns on `remote.command` / external control, and rejects unknown or oversized payloads. `applicationId`, signing and versioning unchanged.
- Tests after merging `origin/main` (`3737c12` #82 `node.status.get`): `ConfigBackupTest` 12/0 (secret-exclusion, dry-run, each conflict mode, confirm required, remote.command never enabled, schema/size rejects); `FleetCapabilitiesTest` flags; `FleetExecutorTest` remote-caller refuse. `:core:test` **355/0**; `:desktop:test` 2/0; `:app:testDebugUnitTest` 34/0; `:app:assembleDebug` (`ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1056/0; `bash tests/lint.sh` clean (shellcheck 0.11.0). Docs: `docs/MANUAL.md` / in-app Help. **Not run on a phone.**

### Fleet `node.status.get` (STATUS-TRAY) (2026-10-20)
- Protocol 1.1 envelope op **`node.status.get`** on the existing Messenger service (`ai.rootforge.os.action.BRIDGE`). Advertised in `capabilities.get` as `{kind: read, remoteAllowed: true, requiresConfirm: false}`. Same package+certificate pairing, default-off external-control switch, nonce/ttl/replay as remote control. Protocol minor stays **1**. `applicationId`, signing and versioning unchanged.
- Success `result` is one frozen `NodeStatusSnapshot`: `nodeId`, `packageName` (Android applicationId; omitted on desktop), `connectionClass: EXTERNAL_NODE`, `updatedAtMs`, `domains[{name, state, detail?}]`. Always reports `node_health`. The Android app adds an `other` domain for root (`su` binary present — looked up, never executed — and manager APK from `RootManager.detect`). `detail` ≤ 200 characters; a snapshot whose detail or leftover payload keys look like secrets (`password`, `token`, `secret`, `signingKey`, …) is `invalid_parameters`, not stripped. Optional `params.nodeId` must match this node (`not_found` otherwise). RootForge is a node, not the tray aggregator.
- Docs: `docs/MANUAL.md` Fleet integration (same text in in-app Help). Spec: droidcommand-AI `docs/specs/STATUS_TRAY.md`.
- Tests: `:core:test` **341/0** (`NodeStatusSnapshotTest` 15, `FleetEnvelopeTest` 13, `FleetAuthTest` 8, `FleetCapabilitiesTest` 2, `ManualLibraryTest` 4); `:desktop:test` 2/0; `:app:testDebugUnitTest` 34/0 (Help golden unchanged); `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not run on a phone.**

### Glossary (2026-10-20)
- In-app **Glossary** drawer screen (`:core` `Glossary`): one short everyday-words line per term (root, su, Magisk, KernelSU, KernelSU Next, APatch, module, Zygisk, Zygisk Next, ReZygisk, Xposed, LSPosed, Vector, Shizuku, Sui, Dhizuku, ADB, fastboot, EDL, bootloader, recovery, TWRP, PitchBlack, boot image, ROM, flash, dry run, SHA-256/checksum, bootloop). Reachable from Help and Links as well as the drawer. The same text is `docs/MANUAL.md` § Glossary (bundled in Help). Official Magisk / LSPosed / Vector / Sui pages stay on **Links**.
- `:core` `GlossaryTest` requires every term and line to be non-empty and unique. `DirectoryLinksTest` still requires Xposed, LSPosed, Vector and Sui on the Links list.
- Tests: `:core:test` 324/0 (`GlossaryTest` 3); `:desktop:test` 2/0; `:app:testDebugUnitTest` 34/0 (Paparazzi `glossary` recorded; `help` / `links` re-recorded); `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0). **Not run on a phone.**

### Local module install on this phone (2026-10-20)
- Modules gains a **This phone** tab (Android APK). When this phone is rooted, a zip from the library is installed with the active manager: Magisk `magisk --install-module`, KernelSU `ksud module install`, APatch `apd module install`. The manager is auto-detected from installed packages (`RootManager.detect`) and, after List, from `/data/adb` (`ksud` / `apd` / `magisk`); the dropdown still overrides. Kitsune is detected but remains [UNSUPPORTED] and assumed Magisk-like.
- Same safety as USB install: zip SHA-256 is shown, dry-run is the default (logs the `su` command, does not call su), a real install needs typed `INSTALL MODULE` via `ApprovalCenter`, and RootForge never reboots (separate **Reboot this phone** button). Installed modules are listed read-only from `/data/adb/modules/*/module.prop`. LSPosed APKs stay on ADB → Install APK. Desktop is a no-op (USB target install unchanged). `applicationId`, signing and versioning unchanged.
- `:core` `LocalModules` builds the quoted `cp` + install + `rm` script and parses the module list. USB `installModuleOnTarget` now uses `RootManager.moduleBinary` for the same binaries.
- Tests after merging `origin/main` (`ef088d3` / #80 Custom RR → Custom Recovery) plus the Links directory: `:core:test` 321/0 (`LocalModulesTest`, `RootManagersTest` detect, `DirectoryLinksTest`, `CatalogRecoveryBridgeTest`); `:desktop:test` 2/0; `:app:testDebugUnitTest` 33/0 (Paparazzi `links` / `modules` kept; `buildCatalog` golden from #80); `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0). **Not run on a phone.**

### Links directory (2026-10-20)
- In-app **Links** drawer screen (`:core` `DirectoryLinks`): official https pages for Magisk, KernelSU, KernelSU Next, APatch; Magisk-Modules-Alt-Repo, Magisk-Modules-Repo, MMRL (`MMRLApp/MMRL`), Xposed-Modules-Repo, LSPosed, Vector (JingMatrix LSPosed successor), Zygisk Next, ReZygisk; Shizuku, Sui, App Ops, Ice Box, Hail, SystemUI Tuner, Canta, Inure, aShell, aShell You, Dhizuku. Play / F-Droid extras only where those listing pages returned 200 on 2026-10-20. Reachable from Modules, Settings → About, and Help as well as the drawer.
- Opens the system browser only. Nothing is downloaded or bundled. `AppActions.openUrl` still refuses non-https. `:core` `DirectoryLinksTest` requires every URL (including extras) to be unique https with no credentials. MMRL's Repositories-tab URL now follows the transfer to `MMRLApp/MMRL`.

### Custom RR catalog → Custom Recovery (2026-10-20)
- Dashboard **Builds for this device**: a matching Custom RR **RECOVERY** row for TWRP or PitchBlack now has **Install with Custom Recovery**, which opens the Custom Recovery screen aimed at that project. Official files are looked up from the **probed** phone, not from the catalog row's device list. Downloads still have to pass the installer https host allow-list; `pitchblack.tech` (the host Custom RR publishes for PitchBlack) was added. SHA-256 is shown; a published MD5/SHA-256 is checked when present. Dry-run stays the default. A write still needs typed `FLASH RECOVERY`, backs up first, writes only `recovery` / `recovery_a` / `recovery_b`, never `dd`s a zip, and never reboots by itself.
- ROMs, root solutions, OrangeFox, SHRP and RedWolf stay link-only. The catalog still does not feed `DeviceMatchGate`. `applicationId`, signing and versioning unchanged.
- Tests after merging #78 (`44716de`) and #79 (`988a941`): new `:core` `CatalogRecoveryBridgeTest` 7/0; `RecoveryCatalogTest` 9/0 (host + project-filter); `ManualLibraryTest` 4/0 (asserts **Install with Custom Recovery** and `pitchblack.tech`). `:core:test` 304/0 (includes fleet 1.1 from #79); `:desktop:test` 2/0; `:app:testDebugUnitTest` 31/0; `:app:assembleDebug` (`ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`). `buildCatalog` Paparazzi golden re-recorded; Help golden still matches. `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not run on a phone.** Doc: `docs/CUSTOM_RR_CATALOG.md`, `docs/CUSTOM_RECOVERY_INSTALLER.md`, `docs/MANUAL.md`.

### Fleet remote-control bridge v1 (2026-10-20)
- Protocol **1.1** fleet envelope on the existing exported Messenger service (`ai.rootforge.os.action.BRIDGE`): `op` is `capabilities.get` | `remote.command` | `macro.step` | `macro.run`, with `requestId`, `targetNodeRef`, `actionRef`, `params`, 128-bit hex `nonce`, `timestampMs`, `ttlMs` (default 120 s, max 300 s), advisory `destructive`, and optional informational `trigger`. The legacy snake_case `rootforge.*` envelope is unchanged (`BridgeIngress` routes on a top-level `op` field).
- Authorize only by caller package + signing-cert SHA-256 (`Message.sendingUid` / `Binder.getCallingUid` + PackageManager). Envelope fields never pair, grant, or skip a confirm. External control stays **off by default**. Replay: expired if `now > timestampMs+ttlMs` or clock skew > 120 s; duplicate nonces keyed `(sourceNodeRef, nonce)` with a ≥ 10 minute cache.
- Destructive actions (flash / slot / lock / unlock, recovery writes including Custom Recovery `su`/`dd`, `shizuku.pmGrant`) go through `ApprovalCenter` with an on-device typed phrase and never auto-approve from a remote trigger. `shizuku.exec` is `capability_off`. Error codes are the frozen 1.1 set.
- Docs: `docs/MANUAL.md` **Fleet integration** (same text in in-app Help after merging #78). `applicationId`, signing and versioning unchanged.
- Tests after merging `main` (`44716de`, #78): `:core:test` **296/0** (`FleetEnvelopeTest` 12, `ReplayGuardTest` 8, `FleetAuthTest` 7, `FleetCapabilitiesTest` 2, `FleetExecutorTest` 15, `ManualLibraryTest` 4); `:desktop:test` 2/0; `:app:testDebugUnitTest` 31/0; `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`). `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not run on a phone.**

### How-to manual and in-app Help (2026-10-20)
- New `docs/MANUAL.md`: plain-language how-to covering what RootForge is and its `EXTERNAL_NODE` fleet role, install and first-run (including the v0.2.0 debug-key uninstall), every current screen, every manifest permission, fleet pairing and the default-off remote-control switch, troubleshooting, and privacy. Desktop mode (#76) and the Custom Recovery installer (#77 / `fabc649`) are documented as landed. Header art is the repo emblem inside a neon cyan-to-magenta ring (`docs/images/manual/header.png`; regenerator `android-app/tools/generate_manual_header.py`). Paparazzi goldens are reused as screenshots.
- The same markdown is bundled on the `:core` classpath and rendered offline by a **Help** drawer screen (and Settings → Open the manual) with a GitHub link. `applicationId`, signing and versioning were not changed.

### Custom Recovery installer for rooted phones (2026-10-20)
- New drawer screen **Custom Recovery** on Android and desktop. It lists official TWRP (`twrp.me` search.json + `dl.twrp.me/<codename>/`) and PitchBlack Recovery (`pitchblackrecovery.com`, GitHub releases, SourceForge) for `ro.product.device` / `ro.build.product`, and accepts a local `.img`/`.zip`. Images are not bundled.
- Detects root (`su -c id`), A/B vs A-only, and whether a dedicated `recovery` / `recovery_a` / `recovery_b` exists. Recovery-in-boot / vendor_boot devices get the installer-zip and “Boot once” paths only; `boot` is never written.
- Writes via `su` + `dd` (this phone on Android, USB ADB target on Android and desktop). Dry-run is the default. A real write uses `ApprovalCenter` with typed `FLASH RECOVERY`, backs up the current partition into the library first, and never reboots by itself.
- Verifies TWRP `.md5` when present; SHA-256 is always shown. https only, official hosts only. `applicationId`, signing and versioning unchanged.
- Tests: new `:core` `recovery/` unit tests (layout, commands, plan, catalog, installer). **Not run on a phone.** Doc: `docs/CUSTOM_RECOVERY_INSTALLER.md`.

### Desktop mode (VictorSuite X11) (2026-10-20)
- Android Dashboard **Desktop mode** card. VictorSuite’s package is `com.termux` (read from Victorious93/VictorSuite on 2026-10-20). RootForge detects it with PackageManager + `<queries>`, pins it by package + signing-certificate SHA-256 (no shared key), and then sends either the dedicated `ai.victorsuite.action.DESKTOP_MODE` intent (specified, not implemented in VictorSuite yet) or Termux `RUN_COMMAND` with only `rootforge-desktop-mode <start|stop|setup|status>`. Missing VictorSuite is a hint, not a crash. Desktop JVM client hides the card. `applicationId`, signing and versioning unchanged.
- Toolkit: `setup_desktop_mode.sh` (idempotent XFCE + tigervnc + VS Code-if-present + launchers), `rootforge_desktop_mode.sh` (X11 or VNC fallback), `termux/desktop-mode-entry.sh`. Unpinned code-server downloads are refused. Spec: `docs/DESKTOP_MODE.md`.
- Tests: `:core:test` 180/0 (`DesktopModeTest` 12); `:desktop:test` 2/0; `:app:testDebugUnitTest` and `:app:assembleDebug` (Paparazzi `desktopMode` golden recorded). `aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`. `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0). **Not run on a phone.**

### Shizuku / Sui on the Android host phone (2026-10-19)
- Optional Shizuku (and Sui) support in the host-phone APK, aligned with the fleet pattern from droidcommand-AI PR #231. `dev.rikka.shizuku:api` / `provider` **13.1.5**, official `ShizukuProvider`, permission `moe.shizuku.manager.permission.API_V23`, `<queries>` for `moe.shizuku.privileged.api` and `rikka.sui`.
- Four states: `GRANTED` / `DENIED` / `NOT_INSTALLED` / `NOT_RUNNING`. Sui counts as present when `managerInstalled || binderAlive`. `ShizukuManager.requestPermission()` is a no-op except from `DENIED`. `onCreate` only starts listeners.
- Sole privileged API is `PrivilegedShell` / `PrivilegedResult` / `PrivilegedResultCodec`. `NullPrivilegedShell` is the fallback. `PrivilegedProcessRunner` uses argv `ProcessBuilder`, never logs stdout/stderr, 64 KiB cap. UserService AIDL is `IPrivilegedShell` (`destroy=16777114`, `exec=1`, `readDeviceState=2`, `pmGrant=3`). Bind is lazy, only after `GRANTED`, off the main thread (`daemon false`, `processNameSuffix privileged`, `debuggable false`).
- Settings lookups are read-only and each one goes through `ApprovalCenter`. `pmGrant` exists for fleet interop only: typed `GRANT PERMISSION`, not shown in the UI. The app works without Shizuku. applicationId, signing and versioning are unchanged.
- RootForge has no flatDir AAR fetch; if one is added later, `:app` `preBuild` / `compile*Aidl` / `*AarMetadata` must `dependOn` that fetch.
- Tests this session: `:core:test` 194/0 (`ShizukuStateResolverTest` 8, `ShizukuManagerTest` 5, `PrivilegedShellTest` 7, `PrivilegedCommandsTest` 6), `:desktop:test` 2/0, `:desktop:compileKotlin`, `:app:testDebugUnitTest` 28/0, `:app:assembleDebug` (debug applicationId still `ai.rootforge.os.debug`; `aapt2` lists `API_V23`). `tests/run-tests.sh` 1023/0, `tests/lint.sh` clean. **Not run on a phone.**

### Android launcher icon from the repo logo (2026-10-19)
- The host-phone APK launcher icon is now the RootForge emblem (fist + anvil + rings, no wordmark) cropped from `assets/logo/rootforge-os-logo.jpg`. Adaptive icon: foreground scaled into the 66% (72/108 dp) safe zone, background `#0C1D36` sampled from the logo field, monochrome stencil for Android 13+ themed icons. Legacy `ic_launcher` / `ic_launcher_round` PNGs at mdpi–xxxhdpi. Regenerator: `android-app/tools/generate_launcher_icons.py`. Signing, `applicationId` and versioning were not changed (`aapt dump badging`: `ai.rootforge.os.debug`, `0.1.0-debug`). Tests: `tests/test_launcher_icons.py` 7/0; `:core:test` 168/0; `:app:testDebugUnitTest` 28/0; `:app:assembleDebug`; `bash tests/run-tests.sh` 1023/0; `bash tests/lint.sh` clean (shellcheck 0.11.0). **Not run on a phone.**

### Custom RR catalog: model-number fallback (2026-10-19)
- "Builds for this device" now also matches by model number: when no catalog codename fits, the target's `ro.product.model` is compared with the catalog row's `models` list (exact, case-insensitive). A Redmi 8 (`olive`, `M1908C3IG`) now finds the 16 ROMs published under `mi439`; such rows say "matched model … as …". Combined codenames are also split on `,`, `&` and spaces (`X00T, X00TD`, `instantnoodle & instantnoodlep`), not just `/`. `TargetInfo` gains an undisplayed `modelNumber` (ADB only). OnePlus stock names (`oneplus6t`) still do not match: those rows have no model numbers and RootForge has no alias table. Display-only as before.
- Tests: `BuildCatalogTest` 14/0 (4 new); `:core:test` 168/0, `:desktop:test` 2/0, `:app:testDebugUnitTest` 28/0, `:app:assembleDebug`. `verifyPaparazziDebug` fails only on `modules`, which fails identically on `main` (golden left stale by #69). A throwaway test against Custom RR's real catalog matched `olive`/`M1908C3IG`, `X00TD` and `instantnoodlep`. Not run on a phone.

### Ask: offline root/ADB questions in the app (2026-10-19)
- New drawer entry **Ask** (Android and desktop). Type a question ("adb devices shows nothing", "which partition do I flash?") and get curated answers with exact commands plus matching Root Guide sections; tapping one opens that guide page. `:core` `ai.rootforge.os.ask`: `AskTopics` (20 hand-written ADB / fastboot / root answers, each with a "varies by device" caveat where it applies) and `AskEngine` (keyword match on word starts, then `GuideLibrary.searchAny`). **This is keyword matching, not an AI model**: it works offline, sends nothing anywhere, cannot see the connected phone, and returns "no match" rather than guessing. A live LLM backend was not added (needs a network call, an API key and a privacy decision from the owner).
- Tests: `:core:test` `AskEngineTest` 6/0 (topic ids unique, every linked guide page exists, intent routing for nine sample questions, word-start matching, nonsense returns nothing). `:desktop:compileKotlin` and `:desktop:test` pass, which compiles the shared screen. **Not run:** `:app` compile / Paparazzi (no Android SDK on this agent; CI `apk` is the check, and no `ask` golden was recorded), a phone, the desktop window. The curated command text is general Platform Tools knowledge, not re-verified on hardware.

### Modules: creator upgrades and repository links (2026-10-19)
- The drawer entry "Module Builder" is now **Modules** with two tabs. **Create**: new optional `boot-completed.sh` and `action.sh`; your own text for any generated file (`ModuleSpec.bodies`; CRLF converted, shebang added to boot scripts, 64 KiB cap, text for a disabled file is a validation error, NUL rejected); hedged per-manager notes (`ModuleBuilder.compatibilityNotes`: webroot on Magisk, action/boot-completed need recent managers, sepolicy and post-fs-data can bootloop). **Repositories**: `ModuleRepos` links to Magisk-Modules-Alt-Repo, Magisk-Modules-Repo, the KernelSU module site, the LSPosed module site, Xposed-Modules-Repo and the MMRL app, each marked with what was checked on 2026-10-19 or "not checked" (the KernelSU and LSPosed sites could not be reached from the authoring sandbox); plus an import-and-install card. Link-out only: nothing is fetched or vetted.
- **Security:** installing any module (built or downloaded) through `installModuleOnTarget` now needs a typed `INSTALL MODULE` confirmation showing the zip's SHA-256, on Android and desktop. New row in `docs/SECURITY_MODEL.md`.
- Tests: `:core:test` 138 (+13: `ModuleBuilderBodiesTest` 9, `ModuleReposTest` 4); screen rendered off-screen with Compose desktop. **Not run:** `:app` compile here (CI `apk`), a phone, a real manager. The Modules Paparazzi golden is stale (new tab row) and needs re-recording with the Android SDK. Not added: extra files from the library (builder's `files` map exists in core; the UI cannot read library bytes yet), an in-app module browser (needs a verified repository JSON format).

### Kitsune Mask as an [UNSUPPORTED] root manager (2026-10-19)
- `RootManager.KITSUNE` (package `io.github.huskydg.magisk`, supplied by the owner, unverified) is selectable in Root / Patching and the module installer. `RootManager.homepage` is now nullable (Kitsune has none: the upstream repo appears gone) and a new `caveat` is shown as a warning. The patched-file prefix (`magisk_patched`) and module command (`magisk --install-module`) are assumptions copied from Magisk and are **unverified** for this fork. No download link and no bundled binary. Guide page updated. +4 `RootManagersTest` tests. `:app` compile and a phone run not done here.

### Custom RR catalog lookup (2026-10-19)
- New `:core` package `ai.rootforge.os.catalog`: parses the `catalog.json` / `freshness.json` format that Custom RR publishes and lists the ROMs, recoveries and root solutions whose entry names the target's product or codename (exact, case-insensitive, `/`-combined codenames split; unknown target → no matches). Links are exposed only if `https://`. Read-only and informational: it does not feed `DeviceMatchGate` or any flash path. No Custom RR code or data is copied in; tests use a synthetic fixture. See `docs/CUSTOM_RR_CATALOG.md`.
- Dashboard card **Builds for this device** (Android and desktop): lists those builds with freshness, warning, and https download/forum links. **Download** fetches the catalog and freshness feed from Custom RR on raw.githubusercontent.com (https only, 8 MiB cap, only on a tap); **Import file** accepts a `catalog.json` or `freshness.json` instead. `BuildCatalogStore` keeps the last good copy and never replaces it with a file that does not parse.
- **The Android app now requests `INTERNET`**, used only by that Download button. Settings → About and the connection notice say so.
- `:core:test` 119/0, `:desktop:test` 2/0, `:app:testDebugUnitTest` 28/0 with `verifyPaparazziDebug` (Dashboard and Flash Tools goldens re-recorded, `buildCatalog` added), `assembleDebug`. Not run on a phone.

### Terminal app default (2026-10-19)
- *Settings → Terminal app* now defaults to `com.termux` (the owner asked for it), so **Open full terminal** works without visiting Settings. Saving an empty value turns the button off; any other valid package overrides it. `TerminalApp.DEFAULT_PACKAGE`, +1 test. Still launch-only and **not run on a phone**.

### Terminal app hand-off (2026-10-19)
- Android: *Settings → Terminal app* stores the package name of a full terminal app (for example the Termux-based VictorSuite); *ADB → Shell* gets **Open full terminal**. Launch only: no command, serial or file is passed, and no package name is hard-coded (its VictorSuite id is not known here). The name is validated as an Android application id (`TerminalApp.isValidPackage`, 2 new tests). `AndroidManifest.xml` `<queries>` gains a MAIN/LAUNCHER intent so the chosen app can be resolved. Desktop has no hand-off (no-op). **Not run on a phone.** Magisk Kitsune is still guide-only: its package name and a safe download could not be verified.

### Root Guide (2026-10-19)
- New **Root Guide** drawer entry in the Android host-phone app and the desktop client: 26 offline pages adapted from Awesome Android Root (MIT; guides, device pages, FAQ, troubleshooting, resources) plus one RootForge page, "Rooting with RootForge OS", mapping guide steps to app screens and stating what the app does not do. Search across all pages, nested lists, tables, callouts, collapsible sections, selectable code blocks, in-guide links and a Back stack.
- `:core` gains `ai.rootforge.os.guide` (`GuideMarkdown` parser, `GuideLibrary` manifest/search, `GuideLink` allow-list: only `guide:`, `#anchor` and `https://`). Pages live in `core/src/main/resources/ai/rootforge/os/guide/`. `android-app/tools/import_root_guide.py` regenerates them from a checkout and records the source commit; the app/module catalogue is not imported.
- Three RootForge-written pages: "Rooting with RootForge OS" (now also covers shells and the VictorSuite boundary), "Magisk Kitsune (Kitsune Mask)" (status: upstream repo appears gone, last build found is Magisk 27.0 based; not offered in Root / Patching because its package name and a safe download could not be verified) and "PitchBlack Recovery (PBRP)". Recovery screen gains a **PitchBlack (PBRP)** browser-link button (`https://pitchblackrecovery.com/`, not reachable from the authoring sandbox). No interactive ADB shell and no VictorSuite launch were added: the app's one-shot `adb shell` already exists and VictorSuite's package/hand-off is unknown.
- `NOTICE` and `LICENSE-awesome-android-root.txt` carry the upstream MIT attribution. New `docs/ROOT_GUIDE.md`.
- Tests: `:core:test` 118 tests, 0 failures (19 new, including a check that every internal link and anchor in the shipped pages resolves); `tests/test_guide_import.py` 14 tests; `bash tests/run-tests.sh` 1023/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 from the repo's pip wheel); `:desktop:compileKotlin` builds. The screen was rendered off-screen with Compose desktop to check layout. **Not run:** `:app` build / `testDebugUnitTest` / Paparazzi (no Android SDK in this session), a phone, the desktop app window. No screenshot golden recorded.

### Desktop API token file (2026-10-19)
- `~/.rootforge/desktop.properties`, which stores the headless API bearer token (`api_token`), is created mode `0600` and an existing group- or world-readable copy is tightened to owner read/write before it is loaded or rewritten. `DesktopSettings` used `File.writer()` with the process umask, so under umask `022` the token was `0644`. Loopback is shared by every local account, and a home directory is often traversable, so another user on the machine could read the token and POST `rootforge.fastboot.unlock` / `flash` with the matching confirmation phrase. The parent directory is left at the umask default. **Not run on a phone.**

### Docs (2026-10-18, README refresh)
- `README.md` reorganised and rewritten in plainer language, with terms explained: what RootForge is, where it sits in the Victorious fleet (`EXTERNAL_NODE`, Messenger + JSON `protocol_major` 1, pairing by package + signing certificate, no shared signing key, with each claim marked [Certain] / [Likely] / [Guessing]), the host-phone app and Flash Tools, install and build steps per client, and a Signing section (fixed committed debug key and its SHA-256, v0.2.0 uninstall-once note, release key only in CI secrets). Mentions the Modules screenshot, adds the CI workflow table, and corrects a missing `./` on the `packageExe` command. Docs only; no code, workflow or test changes.

### Android sideload
- The host-phone `sideload-host` client seeks the package and serves whatever block recovery asks for, including a re-read. It used to walk the file only forward and abort when recovery read the zip central directory (or read the package a second time to install it), so an OTA sideload from the Android app stopped before the update finished. `FAILFAIL` from the device is reported as a failure. **Not run against a phone in recovery.**

### Android debug signing
- The debug build type is signed with a committed, app-specific debug keystore (`android-app/app/debug.keystore`, SHA-256 `77:7A:C7:00:87:05:A8:B4:B8:93:AC:EF:0E:03:E9:1C:7F:49:52:C9:52:FE:B2:6E:06:D7:90:B9:14:86:64:6F`), so CI and local debug APKs share one certificate and update in place. Debug only; release signing is unchanged. Users of the v0.2.0 debug APK must uninstall once. See `docs/ANDROID_APP.md`.

### Build and release
- `release.yml` builds the debug host-phone APK on `v*` tags and attaches
  `rootforge-os-android-debug.apk` (plus SHA-256) to a published GitHub Release
  without waiting for the ISO / Termux draft job. The APK artifact is not named
  `rootforge-*`, so `tests/verify-release-assets.sh` still sees only the ISO
  set. Desktop/Docker stay on `toolkit.yml` CI artifacts (7-day retention after
  #54). Not release-signed unless `ROOTFORGE_KEYSTORE_*` secrets exist
  (`android-apk.yml`).
- `release.yml` now runs `bash termux/make-release-metadata.sh` (the file was
  `100644`; v0.2.0's `release` job died with Permission denied after ISO +
  four rootfs builds succeeded). The script is also marked executable.

### Docker toolkit API (2026-10-16, #60)
- `rootforge.fastboot.flash`, `unlock`, `lock`, `slot.set`, and `fastboot.reboot`, plus `adb.reboot`, now pass `-s` for one selected device. An explicit `serial` that is not in that mode is refused instead of flashing or rebooting a different phone. Omitting `serial` is allowed only when exactly one device is in that mode. **Not run against a phone.**

### Modules (2026-10-15, #59)
- A module display name is shell-quoted before it is written into `customize.sh` (`new_module_scaffold.sh` and the Kotlin `ModuleBuilder`). That file is sourced by Magisk, KernelSU, and APatch. A name containing `$(...)`, backticks, or a `"` used to be spliced into double quotes and ran as root at install time. Newlines in the name are still rejected so `module.prop` cannot gain extra keys.

## v0.2.0 — 2026-10-15

First published GitHub Release, tagged at `e1f84ea` on `main`. Attaches the
**debug** host-phone APK (`rootforge-os-android-debug.apk`, applicationId
`ai.rootforge.os.debug`, versionName `0.1.0-debug`). Built by `android-apk.yml`
on that commit (`assembleDebug`); signed with the Android **debug** keystore,
not a release key. No `ROOTFORGE_KEYSTORE_*` secrets were present, so no
release-signed APK exists. ISO and Termux rootfs are **not** attached:
`release.yml` still drafts those only after live-build +
`tests/verify-release-assets.sh`, and that path was not used for this tag.
Desktop/Docker were 14-day `toolkit.yml` artifacts at tag time (#54 later
capped CI artifacts at 7 days).

Direct APK:
https://github.com/Victorious93/rootforge-os/releases/download/v0.2.0/rootforge-os-android-debug.apk

The dated notes below are the contents of this tag (previously Unreleased).

## 2026-10-15 (README)

### Docs
- README landing page covers Flash Tools (USB OTG hub, device-match gate, SHA-256 inspector, command log, typed warnings; EDL-Commander JNI plan-only), DroidCommand Messenger pairing, git-clone CLI on Linux/Termux, Victorious Framework / VictorSuite `:core` sharing, and the existing APK / desktop / Docker / license sections. Stale “README §17” / “section 14” pointers in `HACKING.md`, `BUILD.md`, `docs/PLATFORM_SUPPORT.md`, `docs/FLASH_TOOLS_PLAN.md`, `.github/workflows/release.yml`, and the Makefile release comment updated.

## 2026-10-15 (Flash Tools)

### Android host-phone app
- New **Flash Tools** drawer section: USB/OTG scan with ignored-device filter, connection guide, ADB tools (device check, shell, packages, push/pull, logcat, screen info, screenshot), fastboot tools (product/partition info, unlock, flash, reboot), image/APK inspector with SHA-256 verify, persistent redacted command log.
- Shared `:core` device-match gate: a flash is refused unless a sidecar `<image>.manifest.json` matches the target product/codename, slot, and fingerprint (or a declared product/slot manifest). SHA-256 in the sidecar is checked when present. Same gate on the desktop `HostController` flash path. Mismatches are logged as `Refused: …`.
- Unlock and flash still use `ApprovalCenter` typed phrases (`UNLOCK`, `FLASH <partition>`). No skip setting.
- Unit tests in `:core` and `:app` for the match gate, SHA-256, command log, and USB filtering. **Not run on a host/target pair, not flashed, not unlocked.**
- EDL-Commander JNI/NDK is plan-only (`docs/FLASH_TOOLS_PLAN.md` Phase 4).

## 2026-10-15 (licensing)

### License
- Repository license locked: original work is **GPL-3.0-or-later**
  (`LICENSE`), except shared Kotlin `:core` (`android-app/core/`), which
  is **Apache-2.0** (`android-app/core/LICENSE`). `NOTICE` records the
  applying notices and that third-party/Termux/Debian/SDK code is not
  relicensed. README polish from #51 kept; license badges replace
  “none yet”.

## 2026-10-14 (toolkit clients)

### Linux / Windows desktop and Docker
- Shared Kotlin `:core` (flash planner, module builder, approvals, bridge
  protocol, `adb devices` parser) used by the Android APK and a Compose
  Multiplatform `:desktop` app. Desktop drives **host** `adb`/`fastboot`/`edl`,
  not the USB-Host wire protocol.
- Headless mode: `./gradlew :desktop:run --args='--headless --port 8787'`
  (127.0.0.1, bearer token). Optional web UI at `/`.
- `docker/Dockerfile` packages the CLI plus the same API. USB passthrough
  documented (`--device /dev/bus/usb`, `--privileged`).
- CI: `.github/workflows/toolkit.yml` uploads Linux `.deb`, Windows `.exe`,
  and a `docker save` tarball. **Not run against a phone.**

## 2026-10-14

### Android host-phone app
- Bridge executor owns every documented gate (pending callers are recorded even
  while external control is off; unknown parameter keys and path-like image
  names are `bad_parameters`). Messenger constants live in `Protocol.kt`.
  Settings pairing UI shows a cert-change warning; a notification fires when a
  controller first asks to pair. `BridgeExecutorTest` covers all eleven
  operations plus EDL/sideload/oem rejection. **Not run next to a live DCA.**
- New `android-app/`: Material 3 toolkit APK. Host phone talks to a target over
  USB-C using the USB Host API and a pure-Kotlin ADB / fastboot / Sahara stack.
  Module zip builder, Magisk/KernelSU/APatch patching workflow (the manager app
  on the target does the patch; this app moves images), typed confirmations for
  writes. droidcommand-ai integration is a bound Messenger service speaking the
  same JSON envelope as `core-rootforge` (`docs/DROIDCOMMAND_INTEGRATION.md`).
  Compiles locally; protocol tests and Paparazzi snapshots pass; **not run on a
  phone**.

### Bugfixes (hermetic)
- `boot cpio` refuses a ramdisk path that leaves the unpack directory.
- Module display names and `brain new` titles cannot inject extra keys via newlines.
- Adb+fastboot serial lists are deduplicated (CLI devices list and fleet).
- A truncated cached ADBKeyboard APK is discarded instead of installed.
- Fleet treats flash exit 4 as "written, boot unverified" rather than FAIL.
- Live-groups unit adds `rootforge` to kvm/plugdev/docker, not `user`.
- Chroot rootfs hostname/motd no longer claim to be unrooted PRoot.
- Scaffolded Magisk `update-binary` sources `util_functions.sh` and calls
  `install_module`.
- Desktop launcher `--start` test no longer fails on a host that has XFCE.

### Docs
- `docs/REVIEW.md`, `docs/ANDROID_APP.md`, `docs/DROIDCOMMAND_INTEGRATION.md`.
  README no longer claims cryptographic signing.
- Merged main's landing-page README (#46 / `docs/README-legacy.md` archive) and
  kept this branch's Android app install notes, structure entry, and doc map.

## 2026-10-13

### OTA extract
- A same-sized image replaced in the same second is counted as produced.
  The previous check stamped `stat` `%Y.%N`, and GNU stat's `%N` is the file
  name, not nanoseconds, so a rewrite that did not change the size before the
  clock ticked was reported as "produced no files" while the bytes on disk
  were already the new payload. Identity is now the SHA-256.

## 2026-10-12

### OTA extract
- `extract_ota.sh` and `rootforge ota extract` treat an image as produced only
  when this run created or replaced it. A reused output directory that already
  held a file (a previous payload's `boot.img`, or any unrelated file) used to
  make an empty extraction exit 0, and the CLI hashed that leftover as if it
  had come out of the payload just extracted.

## 2026-10-07 (architecture repair)

### Device detection and flashing (A)
- `rootforge.core.device`: `fastboot getvar` is parsed from stdout **and** stderr; names
  with arguments (`partition-size:boot_a`) survive; lock state comes from `unlocked` only
  (`secure` is not lock state); an undeterminable slot layout is a blocker, not "A/B".
- New `rootforge device check` (go/no-go; exit 3 when blocked) with `--partition`,
  `--image`, `--both-slots`, `--expect-product/--expect-slot/--expect-bootloader-version`,
  `--json`.
- `flash_patched_boot.sh`/`rootforge flash boot` rewritten: one explicit device, plan +
  typed `FLASH <serial>`, re-check, explicit `fastboot --slot` writes, bounded reboot and
  `sys.boot_completed` wait, `ANDROID!` header check. Exit codes 0/2/3/4/5/130.
- `unlock_bootloader.sh`, `kernelsu_patch_boot.sh --flash` use the same gate.

### Backup and restore (B)
- Backups carry `manifest.json` (v1; trust `captured` | `legacy-imported`). Restore flashes
  only verified manifest entries, to a matching device and slot, after typed
  `RESTORE <serial>`; re-verifies before writing; stops at the first failure.
- `rootforge backup verify [--json] [--partitions]`, `backup import-legacy`,
  `backup create --partitions`, `backup restore --accept-legacy-import`.
- Backup exit codes: 0 complete, 4 partial, 1 failure.

### First boot and installer (C)
- `00_bootstrap_distro.sh`: explicit target user (`--user`, `$SUDO_USER`,
  `/var/lib/rootforge/install-user`, invoking non-root user; never root), `--only
  system|user`, `--headless`, `--check`; resumable stages with markers; user-owned SDK
  staging with a pinned digest and atomic swap; no `apt-get upgrade`.
- Calamares: `removeuser` deletes the live user, `users` forbids `root`/`rootforge`,
  `shellprocess` removes the live sudoers rule and records the installed user.
- `rootforge-firstboot.service` retries after an offline first boot.

### Config, logging, diagnostics, dispatch (D)
- `config`: per-layer schema validation, CLI-option layer, `rootforge config show --json`,
  OS errors reported as `ConfigError`; `backup_partitions.sh` reads `backup.partitions`.
- `log`: terminal echo is redacted too (a secret in an event used to reach stdout); log
  files are created `0600`.
- `doctor`: severity declared per check (`@optional_check`); a crash keeps its declared
  severity; new `jq` (required) and `PyYAML` (optional) checks; audit events restored.
- `ota inspect` identifies an OTA input; the partition-image mount moved to
  `ota inspect-image`. `boot cpio` added; `boot repack` needs an output. `runner` prefers
  the sibling script over `/usr/local/bin`.

### Termux (E)
- `rootforge-chroot.sh install` requires `--sha256`/`--sha256-file`, scans the archive,
  stages the extraction and marks completion; `login` refuses an incomplete install.
- `termux/install.sh` and the proot-distro plugin are now **templates**
  (`termux/templates/*.in`) that refuse to run; `termux/make-release-metadata.sh`
  generates the real files from the built tarballs and binds tag, arch, flavor, URL and
  SHA-256.
- `bootstrap_proot.sh` installs only what the CPU can run (`--plan`, `--capabilities`,
  `--with-system-image`, `--allow-nonnative-sdk`); cmdline-tools download is pinned and
  verified; no longer overwrites `/etc/profile.d/rootforge.sh`.
- `build-rootfs.sh` writes `sha256sum`-format `.sha256` files.

### Build and release (F)
- `Makefile`: `build` no longer pipes through `tee`; `ID_U`/`AUTO_BUILD` seams.
  `auto/build` deletes stale ISO/digest/`binary*.iso` first and says to `make clean` if
  live-build skipped stages.
- `release.yml`: gated on the lint workflow for the same commit; ISO upload fails if files
  are missing; build logs are never release assets; metadata is generated and the asset
  set verified (`tests/verify-release-assets.sh`) before a draft release is created.
- Hooks: **restored SHA-256 pins** that a merge had disabled in 0040, 0050, 0060, 0062,
  0085, 0095 (and 0060 referenced an undefined variable); NodeSource 22 with pinned key
  fingerprints (Node 20 is end of life; Claude Code requires >=22); Claude Code pinned to
  2.1.292 with registry integrity; `repo` pinned to tag v2.65 and its digest; version
  checks no longer swallowed. `check-hooks.sh` gained rules for unused pins, "latest"
  beside a pin, and unpinned `npm install -g`.
- README: live ISO is BIOS-only; UEFI/Secure Boot unsupported.

### Logging and tests (Stage 3 follow-up)
- One `rootforge` invocation is one execution: `main()` sets `ROOTFORGE_EXECUTION_ID`
  (validated; an inherited valid value is kept) and every wrapped script inherits it.
- New `common.sh` helpers `rf_ensure_execution_id`, `rf_private_file`, `rf_log_init`; every
  script that writes a log or report now creates it `0600` from creation, stamps the
  execution ID into logs, and hands new files to `$SUDO_USER` under sudo. Scripts that did
  not source `common.sh` (`build_matrix.sh`, `check_root_detection.sh`, `harden_kernel.sh`,
  `join_headscale.sh`, `rpi_fleet_tools.sh`, `setup_intercept_proxy.sh`, `setup_terminal.sh`,
  `setup_vpn.sh`, `build_magisk_module.sh`, `extract_ota.sh`) now do.
- The CLI's JSON-lines logs are also handed to `$SUDO_USER` under sudo.
- New subprocess-level tests for `boot inspect/unpack/repack/cpio/verify` (stub
  `magiskboot`/`avbtool`), including missing-tool errors without a traceback.
- Migration: existing log files keep their mode; only newly created ones are `0600`. A log
  line `# rootforge execution <id>: ...` now begins each new script log.

### Script-log redaction
- `common.sh`: `rf_redact` (one sed script), `rf_redact_file`, `rf_redact_registered`; every
  file handed out by `rf_private_file`/`rf_log_init` is redacted in place when the script
  exits (an `EXIT` hook composed with any trap already set; verified on bash 5.2 for normal
  exit, `exit N`, `set -e` abort, SIGINT, SIGTERM and SIGHUP, with the exit status preserved).
  `harden_system.sh` and `setup_intercept_proxy.sh` set their own `EXIT` trap and call
  `rf_redact_registered` in it.
- `core/log.py`: `redact_text` now also covers private-key blocks, more token shapes
  (`tskey-`, `xox*-`, `AKIA…`, `hf_…`), secret-valued options (`--authkey X`) and secret-named
  assignments (`API_KEY=…`, `PrivateKey = …`, JSON members); `redact_argv` handles a secret in
  the next argument; `redact_file` rewrites in place. The CLI redacts every script log stamped
  with the run's execution ID after the command (`script_logs_redacted` in the finish event),
  which also covers a SIGKILLed script.
- Tests: `tests/test_redaction_parity.py` runs one 35-sample table through both engines and
  checks each against the intended output; shell tests cover normal exit, `exit N`, a `set -e`
  abort, SIGTERM, SIGHUP and SIGKILL (SIGINT was checked by hand, not in the suite), composition
  with an existing trap, in-place rewrite (inode, mode, symlinks), header-less reports, the
  SIGKILL limit, and a real `join_headscale.sh` run whose registration URL stays visible on the
  terminal but not in the log.
- Known limits: pattern-based and over-redacting by design; the terminal is not redacted;
  the secret is in the `0600` file until exit; a script SIGKILLed outside the CLI is not redacted.

### CLI-side audit trail
- New `core/audit.py`: `flash`, `backup`, `module`, `avd`, `boot patch` and `boot flash-last`
  now write `command started` / `command finished` events (command, redacted argv, euid,
  `SUDO_USER`, exit status, duration, scripts run with their exit statuses, script logs for
  the run) to `rootforge-<command>-<id>.jsonl`. Python-native commands (`backup verify`,
  `backup import-legacy`) are covered too. Exit statuses are returned untouched; an
  exception or Ctrl-C is recorded and re-raised; an unwritable log does not stop the command
  (a warning is printed). `runner` records the scripts it executes; `log.script_logs_for`
  finds the script logs stamped with an execution ID.
- Tests: `tests/test_audit.py` (22) and an end-to-end shell section (blocked flash exit 3
  recorded at `warn` with its script log linked, backup list, backup verify, unwritable log).

### Documentation
- `CLAUDE.md` consolidated (history archived under `docs/archive/`); new `AGENTS.md`,
  `docs/ARCHITECTURE.md`, `docs/PLATFORM_SUPPORT.md`, `docs/SECURITY_MODEL.md`; plan rewritten as a staged roadmap;
  README, BUILD.md, HACKING.md updated; earlier audit/review documents labelled as
  superseded where they are wrong.

### Migration notes
- **Legacy backups** (no `manifest.json`): run `rootforge backup verify`; it will not pass.
  Convert with `rootforge backup import-legacy <codename> <timestamp>`; restoring one needs
  `--accept-legacy-import` and loses device/slot matching.
- **`flash boot --both-slots`** now also requires `--slots-same-build`.
- **`rootforge ota inspect <image>`** used to mount a partition image; use
  `rootforge ota inspect-image <image>`. `ota inspect <file>` now identifies an OTA input.
- **Termux install**: there is no `main/termux/install.sh` to pipe into a shell. Use the
  `install.sh` and `SHA256SUMS` published with a release, or generate them locally with
  `termux/make-release-metadata.sh`. The chroot launcher refuses to install without a digest.
- **`00_bootstrap_distro.sh`** no longer infers the user from `$HOME`/root; pass `--user`
  or run it through `sudo` as that user. On non-amd64 CPUs it no longer fetches the SDK
  (use `termux/bootstrap_proot.sh`).
- **`.sha256` files from `build-rootfs.sh`** now contain `<digest>  <name>`; readers that
  expect a bare digest should take the first field (the generator and launcher do).
- **Node.js 22** replaces Node 20 in the ISO.
