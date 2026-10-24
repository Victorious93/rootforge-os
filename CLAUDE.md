# CLAUDE.md — RootForge-OS session guide and state

Single source of truth for AI-assisted sessions. **Read PROJECT STATE first; update it
before you finish.** History that used to live here (about 115 KB of dated session notes,
phase reports and superseded plans) is archived verbatim at
`docs/archive/CLAUDE_pre-consolidation_2026-10-07.md` — it contains known-false claims, so
read it as history, never as current fact. Architecture: `docs/ARCHITECTURE.md`. Support
matrix: `docs/PLATFORM_SUPPORT.md`. Security: `docs/SECURITY_MODEL.md`. Plan:
`docs/IMPLEMENTATION_PLAN.md`. Change history: `CHANGELOG.md`. Conventions for any agent:
`AGENTS.md`.

## PROJECT STATE

> **Update 2026-10-24 (Custom Recovery lookup vs the live sites):** branch `ccr-4237cfad-065fob`, on `main`
> `500a0a3` (merged in twice, after #87 and after #89 / #92 / #93 / #90; the only conflicts were docs, both
> sides kept). The owner allowed `twrp.me`, `dl.twrp.me` and `pitchblackrecovery.com` (and `sourceforge.net` was
> reachable), so the lookup was run against the real pages. **[Certain] The old parsers returned no offer for
> any device tried**: the unit fixtures matched layouts the sites do not use. (1) `dl.twrp.me/<codename>/` links
> every file as a root-relative `/<codename>/<file>.html` page, which `parseTwrpDirectory` dropped; it now reads
> those rows and uses the file's page as the offer's page and Referer (the image host returns that page's HTML
> without a dl.twrp.me Referer). (2) The sites are case-sensitive (`/AI2201/` 200, `/ai2201/` 404) and the app
> passes lower-cased names: TWRP now keeps its search title's spelling, PitchBlack tries the name as given, lower
> case, then the spelling in its home page's device list. (3) PitchBlack device pages have no download links in
> their HTML (a script loads `/assets/json/builds-<codename>.json`) and SourceForge's links end in `/download`;
> both are read now (`parsePbrpBuilds`, `parseSourceForgeFiles`). Run through the real `RecoveryCatalogClient` and
> `RecoveryFetcher` from this JVM: TWRP `enchilada` 36 offers, `x00p` 11; PitchBlack `enchilada` 12, `x00p` 3; a
> 27 MB TWRP image (`ANDROID!`, its published `.md5` matched) and a 54 MB PitchBlack zip (`PK`, through the
> SourceForge redirect) downloaded. **Not done:** TWRP's `.md5`/`.sha256` are linked only from each file's own page
> and are still not fetched; the GitHub-release route was not run (`api.github.com` is denied by this session's
> gateway). 11 new tests; the 8 that can run on the old code failed there. `:core:test` **464/0** (was 453 on `main`),
> `:desktop:test` 9/0, `:app:testDebugUnitTest` 39/0, `:app:assembleDebug` builds, `bash tests/run-tests.sh`
> **1057/0**, `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not run on a phone or a desktop
> window.** The same session also cleared the merge conflicts on the bot PRs #89 and #90 (both merged since);
> neither is part of this branch.

> **Update 2026-10-24 (APK version bump):** branch `ccr-d6eee520-ln03s1`, on `main` `a8eb53b`.
> APK `versionCode` 1 -> 2, `versionName` `0.1.0` -> `0.3.0`, to replace the v0.2.0 debug APK
> (built from `e1f84ea`, 34 merged PRs behind). Plan: after the PR merges, tag `v0.3.0` on `main`;
> `release.yml` then publishes the debug APK and builds the ISO/rootfs into a **draft** (its first
> run since the `make-release-metadata.sh` mode fix). Not tagged by this session. v0.2.0 users must
> uninstall once (debug key changed in #62). Version tripwires in `tests/test_launcher_icons.py` /
> `tests/test_manual.py` updated. Local: `:core:test` **453/0**, `:app:testDebugUnitTest` **39/0**,
> `verifyPaparazziDebug` passes, `:app:assembleDebug` (`ai.rootforge.os.debug` / `versionCode='2'` /
> `versionName='0.3.0-debug'`, committed debug cert), `tests/run-tests.sh` **1057/0**,
> `tests/lint.sh` clean. Maven Central answered 429 repeatedly; the build resolved through a
> session-local Gradle init script adding Google's Central mirror
> (`maven-central.storage-download.googleapis.com/maven2/`), not committed. **Not run on a phone.**

> **Update 2026-10-24 (bug sweep 2):** branch `ccr-4237cfad-065fob`, on `main` `5652d24`. Ran every
> suite, then probed untrusted-input code (mutation fuzzing of every parser, `hypothesis` on the
> Python parsers, hostile-length frames, a reproduction of each finding before its fix). Fixed, each
> with a regression test that was shown failing on the old code first: (1) `FlashPlanner.parseVarLine`
> split on the first `:` so `partition-size:boot_a: 0x4000000` became key `partition-size`, and
> `putIfAbsent` kept only the first partition (Partition info listed one) — it now splits on the first
> `": "`, the rule `core/device.py` and `docs/ARCHITECTURE.md` §3.1 already state. (2) The fleet and
> legacy approval prompt showed `value.toString().take(300)` while `adb.shell` runs up to 2000
> characters: a paired controller could hide a command's tail from the person approving. (3) The same
> `toString()` recursed per JSON level: a ~4 KB nested `command` threw `StackOverflowError`
> (an `Error`, missed by `catch (e: Exception)`) in the Android service coroutine. Both are now
> `bridge/ApprovalPrompt.kt`: values shown whole, nested or over-long values refused before any
> prompt. (4) `publishIdentity` keyed an identity by its own serial but the roster looks it up by the
> target key (USB/`adb devices` serial or device name), so a Wi-Fi ADB phone (`ip:port`), a Qualcomm
> 9008 port (Sahara chip serial) or a bootloader whose `serialno` differs from its USB string never
> showed its identity, and the desktop loop re-ran `getprop` + `pm list packages` every 2 s —
> `DeviceIdentity.targetKey`. (5) `BuiltModule.fileName` was not a library name (`1.0 beta`, `1.0+5`):
> the Modules screen could not find the zip it had just built and `module.build` returned a name
> that does not resolve. (6) `extractCodenames` regex was quadratic on remote titles (5 s on 30k
> characters; now bounded). (7) ADB sync `DATA`/`FAIL` and Sahara command-response lengths were
> allocated as sent by the target (an `OutOfMemoryError` is an `Error`); now bounded. (8) Docker
> toolkit API: the generated token file was written then `chmod`ed (loose-umask window) at a
> predictable `/tmp` path and **followed a planted symlink, overwriting and re-moding its target**
> (reproduced); `Content-Length: -1` hung a handler thread; `abc` and a 60 KB nested body dropped the
> connection with no response; the token was compared with `==`. Now `O_EXCL` 0600, 400s,
> `hmac.compare_digest`; `HeadlessApi` compares in constant time. (9) `lint_module.sh` wrote
> `sh -n` errors to the fixed `/tmp/lint_module_syntax_err` and wrote through a planted symlink
> (reproduced); now `mktemp`.
> **Found, NOT fixed — needs the owner (touches secret redaction, ASK FIRST):** `redact_text`
> (Python) and `rf_redact` (sed) both leave `密码password=hunter2` and `éapi_key=abc` unredacted under a
> UTF-8 locale (`\b` cannot match between a non-ASCII letter and the name; `LC_ALL=C` redacts), and the
> Python pattern is quadratic: 40 KB of `a-a-a-…` took **63 s**, 100 KB **395 s** (sed: 0.03 s) while
> `redact_file` takes logs up to 64 MiB at exit. A linear lookahead pattern that is byte-identical on
> 250k random ASCII inputs and 16,000x faster was verified in scratch; it also closes the Unicode
> leak in Python (the sed rule would need the same change and parity samples). Also unverified: the
> TWRP / PitchBlack page formats — this session's network policy denies `twrp.me`, `dl.twrp.me` and
> `pitchblackrecovery.com`, so `parseTwrpDirectory` (which drops root-relative hrefs) was not checked
> against the live pages (checked and fixed later the same day: see the update above).
> Local suite on this head: `:core:test` **445/0** (was 424), `:desktop:test` **8/0** (was 4),
> `:app:testDebugUnitTest` **39/0** (was 36; `verifyPaparazziDebug` passes), `:app:assembleDebug`
> (`aapt2 dump badging`: `ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`),
> `:app:lintDebug` 0 errors / 30 warnings, `bash tests/run-tests.sh` **1057/0** (Python unittest 414,
> was 405), `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). Android SDK installed at
> `/opt/android-sdk` again (cmdline-tools 23.0, SHA-1 checked against Google's manifest;
> `sdkmanager --sdk_root=/opt/android-sdk "platforms;android-35" "build-tools;34.0.0" "platform-tools"`;
> it prints a deprecation notice). Maven Central answered 429 on two of three first attempts; retry
> only on resolution errors, `--max-workers=1`. **Not run:** a phone, a desktop window, a Docker build
> (the CLI is present, no daemon), the live TWRP / PitchBlack pages. Every open observation, with its
> evidence and confidence, is in `docs/REVIEW.md` § Bug sweep 2026-10-24.


> **Update 2026-10-22 (recovery backup gate):** branch `cursor/critical-bug-scan-921d`.
> Custom Recovery treated a missing `su` exit status as success and wrote
> the new image even when the pulled backup was shorter than the partition.
> `RootShellResult.ok` is now exit code 0 only. After the pull, the backup
> file must be exactly `blockdev --getsize64` bytes or it is deleted and
> nothing is written. `:core:test` **427/0** (`RecoveryInstallerTest` 10).
> `bash tests/run-tests.sh` 1055/0. `bash tests/lint.sh` clean (shellcheck
> 0.11.0 via the repo wheel). **Not run on a phone or a desktop window.**
> `:desktop:test` and `:app` were not re-run on this branch before the merge below
> (no desktop or app code change).
> **Merged with `main` `4337dbd` on 2026-10-24** (after #91 and #87). The only conflicts
> were `CHANGELOG.md` and this file, where both sides added a block at the top: both are
> kept, newest first. On the merged tree: `:core:test` **448/0**, `:desktop:test` 8/0,
> `:app:testDebugUnitTest` 39/0, `:app:assembleDebug` built, `bash tests/run-tests.sh`
> **1058/0** (1057/0 on an earlier run of the same tree: one check loops over
> `ai_tools_*.log`, named to the second, so it runs once or twice), `bash tests/lint.sh`
> clean (shellcheck 0.11.0 via the repo wheel). **Not run on a phone or a desktop window.**

> **Update 2026-10-20 (catalog aliases):** branch `feat/catalog-aliases`.
> `:core` `CodenameAliases` (48 OnePlus stock names -> catalog codenames,
> RootForge-written from Google's Play supported-devices list + the
> catalog's model numbers; nothing copied from GPL Custom RR; display-only).
> The `modules` Paparazzi golden was NOT stale on current `main`: record
> produced no diff and `:app:verifyPaparazziDebug` passes (the earlier
> "fails on plain main" note is obsolete since #81/#85). Phone checklist for
> the owner is in `docs/CUSTOM_RR_CATALOG.md`. Local suite: `:core:test`
> **424/0**, `:desktop:test` 4/0, `:app:testDebugUnitTest` 36/0,
> `:app:assembleDebug` (`ai.rootforge.os.debug` / `0.1.0-debug`),
> `tests/run-tests.sh` 1055/0, `tests/lint.sh` clean (shellcheck via the repo
> wheel). Android SDK installed at `/opt/android-sdk` on this agent only
> (Maven Central rate-limits with 429; retry with `--max-workers=1`).
> **Not run on a phone or a desktop window.**

> **Update 2026-10-20 (confirm stays on the original phone):** branch
> `cursor/critical-bug-inspection-8120`. While a confirm dialog was open,
> dropping that phone let auto-connect publish another one, and the write
> or shell followed the new phone (desktop `adb`/`fastboot` sampled
> `active` per call; Android erase/Firehose re-read the client after
> Allow; fleet/bridge ACTION approval ran before `SerialOpLock`).
> Desktop ops now pin `-s` to the serial captured at start
> (`DeviceSerialPin`). `connect` is refused while any non-identify action
> is in flight. Fleet/bridge shell and reboots snapshot transport epoch +
> serial and refuse if either changed. Host `edl` refuses unless that
> 9008 device is the only one. Approval lines are shown in full (main's
> `bridge/ApprovalPrompt.kt`: a nested value or one over 12,100 JSON characters
> is refused before any prompt). Docker `adb.shell` uses `_select_serial`.
> Dry-run / typed confirm / backup / no auto-reboot unchanged.
> `applicationId`, signing and versioning unchanged. Local suite:
> `:core:test` **424/0**; `:desktop:test` **5/0**
> (`HostControllerSerialPinTest`); `:desktop:compileKotlin`;
> `bash tests/run-tests.sh` 1055/0 (Python unittest 406/0);
> `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel).
> **Not run:** `:app` tests or assemble (no Android SDK here), a phone,
> or a desktop window.
> **Merged with `main` `4337dbd` on 2026-10-24** (after #85, #88, #91 and #87). Conflicts:
> `CHANGELOG.md` and this file (both sides added a block at the top: both kept, newest
> first), and `BridgeExecutor.kt` / `FleetExecutor.kt`, where this branch and #91 each
> added an approval-line helper. Kept #91's shared `approvalParamLines`
> (`bridge/ApprovalPrompt.kt`), which also refuses nested values, and dropped this branch's
> private 4,096-character copies; the passages above that said 4,096 are corrected. Every
> test this branch added still passes unchanged. On the merged tree: `:core:test`
> **450/0**, `:desktop:test` **9/0**, `:app:testDebugUnitTest` **39/0** (the `:app` suite
> and `:app:assembleDebug` did run this time), `bash tests/run-tests.sh` **1057/0**
> (Python unittest 415), `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel).
> **Not run on a phone or a desktop window.**

> **Update 2026-10-20 (brands + MSM/EDL + infographics):** branch
> `feat/multi-device-identify` (same PR #85). `:core` `BrandCatalog`
> identity table (24 makers), matching Root wizards / unlock portals,
> MSM / EDL explain+link guide (`MsmGuides`; never ships MSM/firmware;
> no writes from the guide), Compose step diagrams on wizards / portals
> / MSM (Android + `:desktop`). Official https firmware pages in Links.
> Closed unlock programmes stay noted with no invented URL. Ethics
> refuse: EDL FRP/screen-lock wipe, Firehose auth bypass, leaked MSM
> packages, unofficial dump sites. Dry-run / typed confirm / backup /
> no auto-reboot unchanged. `applicationId`, signing and versioning
> unchanged. Local suite: `:core:test` **419/0** (`BrandCatalogTest` 4,
> `MsmGuidesTest` 5, `WizardDiagramsTest` 2); `:desktop:test` **4/0**;
> `:app:testDebugUnitTest` **36/0** (Paparazzi `edl` recorded; `devices`
> re-recorded); `:app:assembleDebug` (`aapt dump badging`:
> `ai.rootforge.os.debug` / `versionCode=1` / `versionName=0.1.0-debug`);
> `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean
> (shellcheck 0.11.0 via the repo wheel). **Not run on a phone or a
> desktop window.**

> **Update 2026-10-20 (ESP32 & Pi helpers):** branch
> `feat/multi-device-identify` (same PR). Owner-only bootloader unlock /
> flash helpers: `:core` `BoardHelpers` + Links **ESP32 & Pi helpers**
> (official https: Raspberry Pi docs on GitHub, Espressif USB Serial/JTAG
> + ESP32-S3, Android platform-tools / AOSP locking). A Pi reuses
> `:desktop` `HostController` (`LinuxBoard` / `/proc/device-tree/model`).
> ESP32 is a USB-serial CDC bridge only. **Refused:** screen-lock bypass,
> FRP bypass, HID credential injection (`BoardHelpers.REFUSED`, Ask
> `frp-screen-lock-refused`). Dry-run / typed confirm / backup / no
> auto-reboot unchanged. `applicationId`, signing and versioning
> unchanged. Local suite: `:core:test` **407/0** (`BoardHelpersTest` 4);
> `:desktop:test` **4/0** (`LinuxBoardTest` 1); `:app:testDebugUnitTest`
> 35/0; `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug`
> / `versionCode=1` / `versionName=0.1.0-debug`); `bash tests/run-tests.sh`
> 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo
> wheel). **Not run on a Pi, an ESP32, a phone, or a desktop window.**

> **Update 2026-10-20 (bootloader unlock portals):** branch
> `feat/multi-device-identify` (same PR). Official maker unlock-token
> pages (`:core` `UnlockPortals`) on the Devices Root plan and in Links
> (**Bootloader unlock portals**). Live https vendor pages checked
> 2026-10-20: Motorola, Sony Open Devices, Xiaomi Mi Unlock, HTCdev,
> OnePlus, Fairphone. Pixel has no token page; Nothing has no token
> webpage; Samsung / ASUS / LG / Huawei no longer offer official
> unlocking (in-app note, no invented URL). Plan shows matching portal
> plus a plain-words unlock-data step (`oem get_unlock_data` for
> Motorola) with dry-run first; typed `UNLOCK` still gates a later
> unlock. System browser only; never sends serial/IMEI/unlock data.
> `DirectoryLink.url` may be null. Glossary: Unlock portal.
> `applicationId`, signing and versioning unchanged. Local suite:
> `:core:test` **403/0** (`UnlockPortalsTest` 5, `RootPlanTest` 9,
> `RootGuidesTest` 9, `DirectoryLinksTest` 3); `:desktop:test` **3/0**;
> `:app:testDebugUnitTest` 35/0 (Paparazzi `devices` / `links` /
> `glossary` / `help` still match); `:app:assembleDebug`
> (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` /
> `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1055/0;
> `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel).
> **Not run on a phone or a desktop window.**

> **Update 2026-10-20 (brand root wizards + PC Devices):** branch
> `feat/multi-device-identify` (same PR). `RootGuides` table drives a
> per-identity wizard on Devices: Pixel, Samsung (Odin/Download Mode
> explained, Knox warning, `odinFlashing=false`), Xiaomi (Mi Unlock wait),
> OnePlus, Motorola (official unlock-code URL), generic fastboot. Each step
> names what is erased; writes still go through existing Fastboot / Custom
> Recovery / Root (dry-run, typed phrase, never auto-reboot). Desktop uses
> the same screen: host `adb`/`fastboot`, two-column grid at ≥ 840 dp,
> `HostControllerIdentifyTest`. Glossary: Download mode, Odin, Knox, Mi
> Unlock. Merged `origin/main` `3d05929` (#84). `applicationId`, signing
> and versioning unchanged. Local suite: `:core:test` **396/0**
> (`RootGuidesTest` 9, `DeviceIdentityParserTest` 10, `RootPlanTest` 7);
> `:desktop:test` **3/0** (`HostControllerIdentifyTest` 1);
> `:app:testDebugUnitTest` 35/0; `:app:assembleDebug`
> (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` /
> `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1055/0;
> `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel).
> **Not run on a phone or a desktop window.**

> **Update 2026-10-20 (multi-device identify):** branch
> `feat/multi-device-identify`. **Devices** drawer screen lists every attached
> phone (ADB, fastboot, Qualcomm EDL `05c6:9008`) from USB attach plus a 2 s
> poll. Read-only identity (brand, model, `ro.product.device`, SoC, Android
> version, security patch, bootloader lock, Magisk/KernelSU/APatch/none, slot,
> USB mode). Fastboot `getvar`; EDL identify only (no Firehose). Serials
> masked to last four characters in UI and logs. Root plan
> unlock → recovery → root manager from the Custom RR catalog; actions open
> existing tools for that serial. `SerialOpLock` is one mutex per serial
> (devices can work in parallel). Dry-run, typed confirm, backup-first, no
> auto-reboot unchanged. `applicationId`, signing and versioning unchanged.
> Docs: `docs/MANUAL.md` / Help / Glossary (Serial, SoC, Slot, USB mode).
> Local suite (before #84 merge): `:core:test` **378/0**
> (`DeviceIdentityParserTest` 9, `SerialMaskTest` 3, `RootPlanTest` 7,
> `SerialOpLockTest` 3, `UsbScanFilterTest` 6); `:desktop:test` 2/0;
> `:app:testDebugUnitTest` 35/0 (Paparazzi `devices`; `dashboard` /
> `glossary` re-recorded); `:app:assembleDebug`
> (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` /
> `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1055/0;
> `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel).
> **Not run on a phone.**

> **Update 2026-10-20 (bug sweep):** PR #84, on `main` as `3d05929`.
> Four confirmed bugs on `main` `3f7661d` (#76–#83): (1) fleet
> `ReplayGuard` evicted the oldest live nonce after 4,096 distinct
> `(source, nonce)` pairs, so a reused envelope inside the 10-minute
> retain window could return `OK` — the cap now refuses the new
> request (`internal`) and never evicts a live nonce; (2) Custom
> Recovery allowed a `dd` write when `blockdev --getsize64` failed
> or was unreadable — unknown size is now a blocker; (3)
> `config.import` accepted any `terminalApp` / `autoConnect` string
> — import now requires `true`/`false` and a valid package name
> (empty terminal app still clears it); (4) Android
> `FileInputStream.readNBytes` (API 33) was used on minSdk 26 for
> image-header reads and would crash on API 26–32 — now `:core`
> `readPrefix`. External control stays off; `applicationId` /
> signing / versioning unchanged. Local suite on that PR:
> `:core:test` 363/0; `:desktop:test` 2/0; `:app:testDebugUnitTest`
> 34/0; `:app:assembleDebug` (`aapt dump badging`:
> `ai.rootforge.os.debug` / `versionCode=1` /
> `versionName=0.1.0-debug`); `:app:lintDebug` 0 errors (24
> pre-existing warnings); `bash tests/run-tests.sh` 1055/0;
> `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo
> wheel). **Not run on a phone.**

> **Update 2026-10-20 (fleet config.export / config.import):** branch
> `feat/config-backup`. Protocol 1.1 standalone Bridge ops `config.export` and
> `config.import` (droidcommand-AI `UNIFIED_BACKUP.md` / DCA #236) on the
> frozen EXTERNAL_NODE envelope. Advertised in `capabilities.get`:
> `config.export` `{kind:action, remoteAllowed:false, requiresConfirm:false}`;
> `config.import` `{kind:action, remoteAllowed:false, requiresConfirm:true}`.
> Same package+cert pairing; a remote caller is `capability_off` for both.
> Export is non-secret preferences only (`autoConnect`, `terminalApp`); never
> Magisk/KernelSU/APatch tokens, root passwords, signing material, pairing
> keys/allow-list secrets, VictorSuite pin, desktop API token. Import:
> `dryRun`, `conflictPolicy` skip|overwrite|merge-by-id, on-device
> `ApprovalCenter` confirm, never enables `remote.command` / external control
> (stays as-is). Unknown/oversized payloads rejected. `applicationId`,
> signing and versioning unchanged. Local implementation is `:core`
> `ai.rootforge.os.config`. Docs: `docs/MANUAL.md` / Help (`cmp` identical).
> Merged `origin/main` (`3737c12` #82 `node.status.get`, plus #81). After
> that merge: `:core:test` **355/0** (`ConfigBackupTest` 12,
> `FleetCapabilitiesTest` 2, `FleetEnvelopeTest` 14, `FleetExecutorTest` 16,
> `NodeStatusSnapshotTest` 9, `NodeStatusFleetOpTest` 6,
> `ManualLibraryTest` 4); `:desktop:test` 2/0; `:app:testDebugUnitTest`
> 34/0; `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug`
> / `versionCode=1` / `versionName=0.1.0-debug`); `bash tests/run-tests.sh`
> 1056/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel).
> **Not run on a phone.**

> **Update 2026-10-20 (fleet `node.status.get`):** branch `feat/node-status-get`.
> Protocol 1.1 envelope op `node.status.get` on the existing Messenger
> service. Advertised in `capabilities.get` as `{kind:read,
> remoteAllowed:true, requiresConfirm:false}`. Returns one frozen
> `NodeStatusSnapshot` (`nodeId`, `packageName`, `connectionClass:
> EXTERNAL_NODE`, `updatedAtMs`, `domains[{name,state,detail?}]`): always
> `node_health`, plus an `other` root pulse on Android (`su` binary
> present — looked up, never executed — and manager APK via
> `RootManager.detect`). `detail` ≤ 200 chars; secret-shaped detail is
> `invalid_parameters`, not stripped. Same package+cert pairing, default-off
> external control, nonce/ttl/replay as remote control. Protocol minor stays
> 1. `applicationId`, signing and versioning unchanged. RootForge is a node,
> not the tray aggregator. Spec: droidcommand-AI
> `docs/specs/STATUS_TRAY.md`. Documented in `docs/MANUAL.md` / Help.
> **Not run on a phone.** After local suite: `:core:test` 341/0
> (`NodeStatusSnapshotTest` 15, `FleetEnvelopeTest` 13, `FleetAuthTest` 8,
> `FleetCapabilitiesTest` 2); `:desktop:test` 2/0;
> `:app:testDebugUnitTest` 34/0 (Help golden unchanged);
> `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug` /
> `versionCode=1` / `versionName=0.1.0-debug`); `bash tests/run-tests.sh`
> 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel).

> **Update 2026-10-20 (glossary):** branch `feat/local-module-install`
> (PR #81). In-app **Glossary** drawer screen (`:core` `Glossary`): one
> short everyday-words line per required term (root, su, Magisk, KernelSU,
> KernelSU Next, APatch, module, Zygisk, Zygisk Next, ReZygisk, Xposed,
> LSPosed, Vector, Shizuku, Sui, Dhizuku, ADB, fastboot, EDL, bootloader,
> recovery, TWRP, PitchBlack, boot image, ROM, flash, dry run,
> SHA-256/checksum, bootloop). Reachable from Help and Links. Same text in
> `docs/MANUAL.md` § Glossary (bundled in Help). Links still lists Xposed,
> LSPosed, Vector and Sui. `:core` `GlossaryTest` requires every entry
> non-empty and unique. `applicationId`, signing and versioning unchanged.
> `:core:test` 324/0 (`GlossaryTest` 3); `:desktop:test` 2/0;
> `:app:testDebugUnitTest` 34/0 (Paparazzi `glossary` / `help` / `links`);
> `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug` /
> `versionCode=1` / `versionName=0.1.0-debug`); `bash tests/run-tests.sh`
> 1055/0; `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel).
> **Not run:** a phone, the desktop window.

> **Update 2026-10-20 (local module install on this phone):** branch
> `feat/local-module-install` (PR #81). Merged `origin/main` `ef088d3` (#80
> Custom RR → Custom Recovery). Modules gains a **This phone** tab on the
> Android APK: when this phone is rooted, a library zip is installed with
> Magisk (`magisk --install-module`), KernelSU (`ksud module install`) or
> APatch (`apd module install`). `RootManager.detect` auto-picks from
> installed packages and, after List, from `/data/adb` markers; the
> dropdown overrides. Same safety as USB install: SHA-256 shown, dry-run
> default (logs the `su` command, does not call su), typed `INSTALL MODULE`
> via `ApprovalCenter`, never auto-reboot (separate Reboot button).
> Installed modules listed read-only from `/data/adb/modules`. LSPosed APKs
> stay on ADB → Install APK. Desktop is a no-op. Same PR: in-app **Links**
> directory (`DirectoryLinks`) of official https pages (managers, module
> repos, Shizuku apps); browser only; from Modules, Settings, Help and the
> drawer. `applicationId`, signing
> and versioning unchanged. After merging `origin/main` (`ef088d3` / #80):
> `:core:test` 321/0 (`LocalModulesTest`, `RootManagersTest` detect,
> `DirectoryLinksTest`, `CatalogRecoveryBridgeTest`); `:desktop:test` 2/0;
> `:app:testDebugUnitTest` 33/0; `:app:assembleDebug`
> (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` /
> `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1055/0;
> `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel).
> **Not run:** a phone, the desktop window.

> **Update 2026-10-20 (Custom RR → Custom Recovery):** PR #80, on `main` as
> `ef088d3`. Dashboard **Builds for this device** now has **Install with
> Custom Recovery** on a matching Custom RR TWRP or PitchBlack RECOVERY row.
> That opens the Custom Recovery screen pre-filled with that project
> (`CatalogRecoveryBridge`). Official files are looked up from the **probed**
> phone, not the catalog row. Downloads still pass the installer https host
> allow-list; `pitchblack.tech` / `www.pitchblack.tech` were added because
> that is the host Custom RR publishes for PitchBlack (2026-09-12 catalog).
> ROMs, root solutions, OrangeFox, SHRP and RedWolf stay link-only. Dry-run
> stays the default; SHA-256 is shown; a published checksum is verified when
> present; a write still needs typed `FLASH RECOVERY`, backs up first, writes
> only `recovery` / `recovery_a` / `recovery_b`, never `boot`/`vendor_boot`,
> never `dd`s a zip, never auto-reboots. The catalog still does not feed
> `DeviceMatchGate`. `applicationId`, signing and versioning unchanged.
> On that PR: `:core:test` 304/0; `:desktop:test` 2/0;
> `:app:testDebugUnitTest` 31/0; `:app:assembleDebug`.
> **Not run:** a phone, the desktop window.

> **Update 2026-10-20 (fleet bridge v1):** PR #79, on `main` as `988a941`. Protocol 1.1
> fleet remote-control envelope on the existing exported Messenger service
> (`ai.rootforge.os.action.BRIDGE`): `op` `capabilities.get` | `remote.command` |
> `macro.step` | `macro.run`, nonce/ttl/skew replay cache, pairing by package +
> cert SHA-256 only (never `trigger.*`). External control still off by default.
> Destructive ops (flash, recovery names including Custom Recovery `su`/`dd`,
> `shizuku.pmGrant`) go through `ApprovalCenter` and never auto-approve. Legacy
> snake_case `rootforge.*` envelope unchanged (`BridgeIngress`). Docs:
> `docs/MANUAL.md` Fleet integration (same text in in-app Help after merging
> #78 / `44716de`). `applicationId` / signing / versioning unchanged. After
> merging `origin/main` (#77 then #78): `:core:test` **296/0**
> (`FleetEnvelopeTest` 12, `ReplayGuardTest` 8, `FleetAuthTest` 7,
> `FleetCapabilitiesTest` 2, `FleetExecutorTest` 15, `ManualLibraryTest` 4),
> `:desktop:test` 2/0, `:app:testDebugUnitTest` 31/0, `:app:assembleDebug`
> (`aapt`: `ai.rootforge.os.debug` / `versionCode=1` /
> `versionName=0.1.0-debug`); `bash tests/run-tests.sh` 1055/0;
> `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not
> run on a phone.**

> **Update 2026-10-20 (how-to manual + in-app Help):** branch `docs/manual`
> (PR #78, on `main` as `44716de`). Added `docs/MANUAL.md` (plain-language how-to:
> what it is / EXTERNAL_NODE fleet role, install and v0.2.0 debug-key uninstall,
> every screen, every manifest permission, fleet pairing + default-off switch,
> troubleshooting, privacy). Header art: emblem + neon cyan-to-magenta ring
> (`docs/images/manual/header.png`; `android-app/tools/generate_manual_header.py`).
> Same markdown is bundled at `ai/rootforge/os/manual/MANUAL.md` and rendered
> by a **Help** drawer screen (also Settings → Open the manual) with a GitHub
> link. Merged `origin/main` (`b8e7708` #76, then `fabc649` #77): Desktop mode
> and the Custom Recovery installer are documented as landed. `applicationId`,
> signing and versioning unchanged (`aapt dump badging`: `ai.rootforge.os.debug`
> / `versionCode=1` / `versionName=0.1.0-debug`). Verified on that head:
> `:core:test` 252/0 (`ManualLibraryTest` 4, `recovery/` 41, `DesktopModeTest`
> 12); `:desktop:test` 2/0; `:app:testDebugUnitTest` 31/0; `:app:assembleDebug`;
> `bash tests/run-tests.sh` 1055/0; `bash tests/lint.sh` clean
> (shellcheck 0.11.0). **Not run:** a phone, the desktop window.

> **Update 2026-10-20 (Custom Recovery installer):** branch `feat/recovery-installer`
> (PR #77, on `main` as `fabc649`). New drawer screen **Custom Recovery**
> (Android + desktop) installs official TWRP (`twrp.me/search.json` +
> `dl.twrp.me/<codename>/`) and PitchBlack (`pitchblackrecovery.com`, GitHub
> releases, SourceForge) on a **rooted** phone with `su`/`dd`. Detects
> `ro.product.device` / `ro.build.product`, `su -c id`, A/B vs A-only, and
> dedicated `recovery`/`recovery_a`/`recovery_b` vs recovery-in-boot
> (`vendor_boot`/`boot`). Writes only the dedicated recovery partition; never
> `boot` or `vendor_boot`. Dry-run default; typed `FLASH RECOVERY` via
> `ApprovalCenter`; backup of the current partition into the library first;
> reboot to recovery is a separate button. Images are not bundled; https
> official hosts only; TWRP `.md5` checked when present, SHA-256 always shown.
> Verified on that PR: `:core:test` 248/0 (`recovery/` 41), `:desktop:test` 2/0,
> `:app:testDebugUnitTest` 30/0, `:app:assembleDebug`. **Not run on a phone.**
> Doc: `docs/CUSTOM_RECOVERY_INSTALLER.md`.

> **Update 2026-10-20 (Desktop mode):** branch `feat/desktop-mode-x11` (PR #76, on `main` as `b8e7708`).
> Android Dashboard card talks to VictorSuite (`com.termux`, confirmed in that repo)
> by package + cert pin, then `RUN_COMMAND` with only `rootforge-desktop-mode <op>`
> (dedicated `ai.victorsuite.action.DESKTOP_MODE` is specified in
> `docs/DESKTOP_MODE.md` and is **not** in VictorSuite yet). Setup scripts
> `setup_desktop_mode.sh` / `rootforge_desktop_mode.sh` /
> `termux/desktop-mode-entry.sh`. Missing companion is a hint, never a crash.
> Signing / `applicationId` / versioning unchanged. Verified on that PR:
> `:core:test` 180/0, `:desktop:test` 2/0, `:app:testDebugUnitTest` + `assembleDebug`
> (`aapt`: `ai.rootforge.os.debug` / `0.1.0-debug`); `bash tests/run-tests.sh` 1055/0;
> `bash tests/lint.sh` clean. **Not run on a phone.** Merged `origin/main` (#75 Shizuku)
> into that branch.

> **Update 2026-10-19 (Shizuku fleet alignment):** branch `feat/shizuku` (PR #75).
> Aligned with droidcommand-AI PR #231 under RootForge packages. Four states
> `GRANTED`/`DENIED`/`NOT_INSTALLED`/`NOT_RUNNING`; Sui present when
> `managerInstalled || binderAlive`. Sole API `PrivilegedShell` /
> `PrivilegedResult` / `PrivilegedResultCodec`; `NullPrivilegedShell` fallback;
> `PrivilegedProcessRunner` argv `ProcessBuilder`, no stdout/stderr logging,
> 64 KiB cap. `ShizukuManager.requestPermission()` no-op except from `DENIED`;
> `onCreate` only starts listeners. Bind UserService lazily after `GRANTED`,
> off main thread (`daemon false`, `processNameSuffix privileged`,
> `debuggable false`). `IPrivilegedShell.aidl` destroy=16777114, exec=1,
> readDeviceState=2, pmGrant=3. Deps 13.1.5, `ShizukuProvider`, `API_V23`,
> queries for Shizuku + Sui. Every privileged op goes through `ApprovalCenter`;
> `pmGrant` is interop-only (typed `GRANT PERMISSION`, no UI). No applicationId /
> signing / versioning change. No flatDir AAR fetch (comment documents the CI
> gotcha). Verified: `:core:test` **194/0** (`ShizukuStateResolverTest` 8,
> `ShizukuManagerTest` 5, `PrivilegedShellTest` 7, `PrivilegedCommandsTest` 6),
> `:desktop:test` 2/0, `:desktop:compileKotlin`, `:app:testDebugUnitTest` 28/0,
> `:app:assembleDebug` (applicationId still `ai.rootforge.os.debug`; `aapt2`
> lists `moe.shizuku.manager.permission.API_V23`). `tests/run-tests.sh` 1023/0,
> `tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). **Not run:** a
> phone, the desktop window, Paparazzi re-record (settings golden unchanged:
> Shizuku card is hidden when `shizuku == null`). Merged `main` (#74 launcher icon)
> into this branch (docs-only conflicts).

> **Update 2026-10-19 (launcher icon from logo):** branch `feat/app-icon-from-logo`
> (PR #74). Replaced the orange anvil vector (`drawable/ic_launcher_foreground.xml`)
> with the emblem cropped from `assets/logo/rootforge-os-logo.jpg` (fist + anvil +
> rings, no wordmark). Adaptive: foreground in the 66% (72/108 dp) safe zone,
> background `#0C1D36` (logo field), monochrome stencil for API 33+ themed icons;
> legacy `ic_launcher` / `ic_launcher_round` PNGs mdpi–xxxhdpi. Regenerator
> `android-app/tools/generate_launcher_icons.py`. Signing, `applicationId` and
> versioning unchanged (`aapt dump badging` on the debug APK: `ai.rootforge.os.debug`
> / `versionCode=1` / `versionName=0.1.0-debug`). Verified: `tests/test_launcher_icons.py`
> 7/0; `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` → `:core` 168/0,
> `:app` 28/0 (Paparazzi included), assembleDebug; `bash tests/run-tests.sh` 1023/0;
> `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). APK contains the
> new mipmaps and adaptive layers. **Not run:** a phone, the desktop window,
> `:desktop:test`.

> **Update 2026-10-19 (catalog model-number fallback):** branch `ccr-b45e98d7-7wsvwf` (#66 was
> squash-merged; the branch was brought up to `main` with a normal merge, tree identical to `main`,
> no force-push). `BuildLookup` falls back to
> `ro.product.model` vs the catalog's `models` lists and splits codenames on `/ , &` and spaces;
> `TargetInfo.modelNumber` (ADB only, not displayed). Verified with the local SDK: `:core:test` 168/0
> (`BuildCatalogTest` 14), `:desktop:test` 2/0, `:app:testDebugUnitTest` 28/0, `:app:assembleDebug`.
> `:app:verifyPaparazziDebug` fails only on `modules`, identically on unmodified `main` (stale since
> #69). Real-catalog throwaway: `olive`+`M1908C3IG` → 16 ROMs via `mi439`. OnePlus stock names still
> unmatched (no alias table). Not run on a phone.

> **Update 2026-10-19 (Ask):** branch `ccr-b10b0495-thfmfs`. New **Ask** drawer entry: `:core`
> `ai.rootforge.os.ask` (`AskTopics` 20 curated ADB/fastboot/root answers, `AskEngine` keyword
> match + `GuideLibrary.searchAny`) and shared Compose `AskScreen`. Offline keyword matching, **not an
> LLM**; no network, cannot see the phone. `:core:test` `AskEngineTest` 6/0, `:desktop:test` and
> `:desktop:compileKotlin` pass. **Not run:** `:app` compile/Paparazzi (no SDK here; no golden), a phone.
> Also: merged `main` into PR #66's branch (docs-only conflicts) and pushed it.

> **Update 2026-10-19 (Custom RR catalog lookup):** branch `ccr-b45e98d7-7wsvwf`.
> New `:core` package `ai.rootforge.os.catalog`: parser + exact-codename `BuildLookup`,
> `BuildCatalogFetcher` (https only, 8 MiB cap) and `BuildCatalogStore` (parse-then-atomic
> write, keeps last good copy). Dashboard card "Builds for this device" on Android and desktop
> with **Download** and **Import file** (owner chose both, 2026-10-19). **The APK now requests
> `INTERNET`**, used only by that button. Display-only; not read by `DeviceMatchGate`. No Custom
> RR code/data copied (GPL-3.0-only upstream; `:core` Apache-2.0). Verified with an SDK installed
> on this agent: `--rerun-tasks :core:test` 119/0, `:desktop:test` 2/0, `:desktop:compileKotlin`,
> `:app:verifyPaparazziDebug` 28/0 (dashboard + flashTools re-recorded, buildCatalog added),
> `:app:assembleDebug` (aapt2 shows `INTERNET`). Throwaway tests: real bundled catalog parse;
> live `download()` from this JVM (37 entries, 35 freshness). `tests/run-tests.sh`
> 1023/0, `tests/lint.sh` clean (shellcheck 0.11.0). Not run: APK on a phone, desktop window.
> After merging `main` (#65 Root Guide): `:core:test` 138/0, `:desktop:test` 2/0,
> `:app:verifyPaparazziDebug` 28/0 and `:app:assembleDebug` all pass, so the Root Guide's `:app`
> build is now verified too (not on a phone). Its two "no INTERNET permission" lines were corrected.

> **Update 2026-10-19 (modules):** Modules screen now has Create / Repositories tabs. Creator: `boot-completed.sh`,
> `action.sh`, custom script text (`ModuleSpec.bodies`), hedged compatibility notes. Repositories:
> `ModuleRepos` link-out list (checked 2026-10-19: Alt-Repo, Magisk-Modules-Repo, Xposed-Modules-Repo, MMRL
> exist; KernelSU/LSPosed sites unreachable here, flagged "not checked"). **Installing any module now needs
> typed `INSTALL MODULE`** (SHA-256 shown) on Android and desktop. `:core:test` 138/0 (+13); desktop compiles.
> **Not run:** `:app` compile (CI `apk`), a phone, a real manager. Modules Paparazzi golden stale. Landed with
> Kitsune in #69 (head `f27a9e5`, all 9 CI checks green, including the `apk` Android compile). A split
> attempt (#70) was closed as redundant: merging it would have removed Kitsune.

> **Update 2026-10-19 (Kitsune manager):** `RootManager.KITSUNE` added, [UNSUPPORTED], package supplied
> by the owner (`io.github.huskydg.magisk`) and unverified; `homepage` now nullable (none), new `caveat`
> shown in Root / Patching; patched prefix and module command assumed Magisk-like. `:core:test` 125/0
> (+4), `:desktop:compileKotlin` builds; `:app` compile and a phone run NOT done (CI `apk` is the check).

> **Update 2026-10-19 (terminal app default):** `TerminalApp.DEFAULT_PACKAGE = "com.termux"` is the
> default for Settings → Terminal app (owner's request); empty clears it. `:core:test` +1 test. Launch
> still unverified on a phone; whether VictorSuite is `com.termux` or a separate package is unknown.

> **Update 2026-10-19 (terminal app hand-off):** branch `ccr-76f54fed-xf2wxr` restarted from `main`
> after #65 merged. Android *Settings → Terminal app* (user-entered package, validated, no default) and
> *ADB → Shell* **Open full terminal**: launch only via `getLaunchIntentForPackage`; manifest `<queries>`
> gets a MAIN/LAUNCHER intent. Desktop no-op. Verified: `:core:test` 120/0 (2 new `TerminalAppTest`),
> `:desktop:compileKotlin` builds. **Not run:** `:app` compile/tests here (no Android SDK; CI `apk` job
> is the check), a phone. VictorSuite's package is unknown to the repo and not hard-coded. Kitsune
> remains guide-only (package/download unverified).

> **Update 2026-10-19 (Root Guide):** branch `ccr-76f54fed-xf2wxr`. New **Root Guide**
> drawer entry in the Android app and desktop client (`docs/ROOT_GUIDE.md`). 26 pages adapted
> from Awesome Android Root (MIT, source commit pinned in `manifest.json`) plus three RootForge-written
> pages (screen mapping, Kitsune status, PitchBlack); `:core` `ai.rootforge.os.guide` (parser, search, link
> allow-list); shared Compose `GuideScreen`; importer `android-app/tools/import_root_guide.py`;
> `NOTICE` carries the MIT attribution. The apps-and-modules catalogue was not imported.
> Verified: `:core:test` 118/0 (19 new, incl. every internal link/anchor resolving),
> `:desktop:compileKotlin` builds, `tests/test_guide_import.py` 14 OK inside `bash tests/run-tests.sh`
> 1023/0, `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). Layout was checked by
> rendering the real screen off-screen with Compose desktop (temporary harness, removed).
> Follow-up on the same branch: guide pages for Kitsune (guide-only, unmaintained [Likely]) and
> PitchBlack, a Recovery-screen PBRP link, and shells/VictorSuite notes in the workflow page. No new
> interactive shell and no VictorSuite launch (package/hand-off unknown); see `docs/ROOT_GUIDE.md`.
> **Not run:** `:app` build/tests/Paparazzi (no Android SDK here; no golden recorded), any phone,
> the desktop window. Android loading pages via `getResourceAsStream` on a library jar is [Likely]
> but unverified. The guide text is community reference, not tested on hardware by RootForge.

> **Update 2026-10-19 (desktop API token mode):** PR #64 (on `main`). `DesktopSettings` wrote
> `~/.rootforge/desktop.properties` (the headless `api_token`) with
> `File.writer()` and the process umask, so umask `022` left the bearer
> token mode `0644`. Loopback is shared by local accounts. The file is now
> created mode `0600` before any bytes are written, and an existing
> group/world-readable copy is tightened before load and before rewrite.
> The `~/.rootforge` directory is left at the umask default. Verified:
> `./gradlew :desktop:test --tests ai.rootforge.os.desktop.DesktopSettingsTest`
> → 2 tests, 0 failures; `bash tests/run-tests.sh` 1023/0; `bash tests/lint.sh`
> clean (shellcheck 0.11.0). Not run on a phone, and the headless server was
> not started.

> **Update 2026-10-18 (README refresh):** branch `cursor/refresh-readme-249a`,
> docs only (`README.md`, `CHANGELOG.md`, this file). README re-organised in plain
> language: what it is, fleet placement, host-phone app / Flash Tools, install and
> build per client, Signing. Fleet claims were checked against this repo's code and
> against droidcommand-AI `main` read through the GitHub API on 2026-10-18: DCA's
> `core-security/FleetCatalog.kt` lists RootForge as `EXTERNAL_NODE`; DCA's
> `core-rootforge` mirrors `BRIDGE_PROTOCOL_MAJOR = 1` and has only an SSH transport,
> so no Android Messenger client exists on the DCA side yet. A live DCA ↔ RootForge
> pairing on a phone is still unrun (README marks it [Likely]). The v0.2.0 APK SHA-256
> in the README matches the digest GitHub reports for that asset. Local run:
> `bash tests/run-tests.sh` 1023/0, `bash tests/lint.sh` clean (shellcheck 0.11.0).
> Gradle, ISO, Docker and device paths were not run (no code changed).

> **Update 2026-10-17 (debug signing key):** branch
> `cursor/stable-debug-signing-key-4b63`. The debug build type is signed with a
> committed app-specific keystore `android-app/app/debug.keystore` (SHA-256
> `77:7A:C7:00:87:05:A8:B4:B8:93:AC:EF:0E:03:E9:1C:7F:49:52:C9:52:FE:B2:6E:06:D7:90:B9:14:86:64:6F`).
> Verified: `:core:test :app:testDebugUnitTest :app:assembleDebug` built (126 tests, 0
> failures), `apksigner verify --print-certs` cert SHA-256 equals the keystore's;
> `tests/run-tests.sh` 1023/0, `tests/lint.sh` clean. Not installed on a phone; the
> v0.2.0 debug APK needs a one-time uninstall. Release signing untouched.

> **Update 2026-10-17 (sideload block seeks):** branch
> `cursor/critical-bug-scan-854e`. The Android USB `sideload-host` client
> (`AdbConnection.sideload`) used to read the package only forward and abort
> when recovery asked for an earlier block. Recovery reads the zip central
> directory at the end, then the payload, then the package again to install,
> so an OTA sideload from the host-phone app stopped before the update
> finished. The client now seeks the library file for every requested block
> and treats `FAILFAIL` as failure. Not run on a phone in recovery.
> Module quoting (#59) and the toolkit serial pin (#60) are merged on `main`.
> First published Release is **v0.2.0** (debug APK only). EDL-Commander JNI
> is still plan-only.

**Last updated:** 2026-10-24 · **Branch:** `ccr-4237cfad-065fob`
· **Phase:** active development; ISO/VM/hardware
validation **blocked** on infrastructure. Android host-phone APK, Flash Tools,
desktop/Docker toolkit, DroidCommand bridge, license lock, toolkit API serial pin (#60),
APK-on-tag release (#58), module quoting (#59) and sideload seek (#61) are on `main`.
On `main` from recent work: Root Guide (#65), terminal app hand-off and `com.termux` default (#67, #68), Kitsune manager plus module repositories/creator (#69), the Custom RR build catalog with a Dashboard card (#66), Shizuku/Sui (#75), Desktop mode via VictorSuite (#76 / `b8e7708`), Custom Recovery installer (#77 / `fabc649`), how-to manual + in-app Help (#78 / `44716de`), fleet protocol 1.1 (#79 / `988a941`), Custom RR → Custom Recovery (#80 / `ef088d3`), local module install + Links + Glossary (#81 / `b72d214`), `node.status.get` (#82), fleet `config.export` / `config.import` (#83 / `3f7661d`), and the bug sweep (#84 / `3d05929`). PR #85 (`feat/multi-device-identify`, now on `main`) adds the **Devices** multi-phone identify screen, brand Root wizards, the PC Devices grid, official bootloader unlock portals, ESP32 / Raspberry Pi owner helpers (no FRP / screen-lock bypass), the `BrandCatalog` identity table, the MSM / EDL guide (detect + explain + official links only), and Compose step diagrams. The APK requests `INTERNET` for catalog Download and Custom Recovery lookup/download, both only when tapped. None run on a device. README refresh is #63 and the desktop API token file mode is #64. License lock (#53):
original work GPL-3.0-or-later (`LICENSE`); shared Kotlin `:core` Apache-2.0
(`android-app/core/LICENSE`); third-party/Termux/Debian/SDK code not relicensed (`NOTICE`).

**What is true now** (docs + hermetic suite this session; older bullets were not re-audited unless noted):
- Custom Recovery lookup (this branch): read against the live TWRP and PitchBlack sites on 2026-10-24 and
  fixed (see the 2026-10-24 update above). A lookup and a download ran end to end through the real
  `RecoveryCatalogClient` / `RecoveryFetcher`; nothing was flashed. TWRP checksums are not fetched yet.
  `:core:test` 464/0. **Not run on a phone or a desktop window.**
- Custom Recovery backup gate (this branch): a missing `su` exit status is
  not success, and a pulled backup that is not exactly the partition size
  is discarded. The new image is not written. `:core:test` 427/0,
  `tests/run-tests.sh` 1055/0, `tests/lint.sh` clean. **Not run on a phone
  or a desktop window.**
- Confirmations stay on the phone they started on (this branch): desktop
  `DeviceSerialPin` keeps `adb -s` / `fastboot -s` on the serial captured
  when the action started; `SerialOpLock.connectRefusal` blocks a connect
  while any non-identify action is in flight; fleet/bridge shell and
  reboots compare transport epoch and serial across Allow; Android erase
  and Firehose keep the client captured before the dialog; host `edl`
  refuses unless that 9008 device is alone; approval lines are shown in
  full (`bridge/ApprovalPrompt.kt`: a nested value or one over 12,100 JSON
  characters is refused before any prompt); Docker `adb.shell` uses
  `_select_serial`. `:core:test` 424/0, `:desktop:test` 5/0,
  `tests/run-tests.sh` 1055/0 (Python unittest 406/0), `tests/lint.sh`
  clean. **Not run:** `:app` (no Android SDK here), a phone, or a desktop
  window.
- Brands + MSM/EDL + infographics (PR #85, on `main`): `:core`
  `BrandCatalog` (24 makers), expanded `RootGuides` / `UnlockPortals`,
  `MsmGuides` explain+link for Qualcomm 9008 (never ships MSM/firmware;
  guide never writes), Compose `StepDiagram` on wizards / portals / MSM
  (APK + `:desktop`). Paparazzi `edl` + `devices`. `:core:test` 419/0,
  `:desktop:test` 4/0, `:app:testDebugUnitTest` 36/0, `:app:assembleDebug`
  (`ai.rootforge.os.debug` / `0.1.0-debug`), `tests/run-tests.sh` 1055/0,
  `tests/lint.sh` clean. **Not run on a phone or a desktop window.**
- ESP32 & Pi helpers (this branch): `:core` `BoardHelpers` + Links
  **ESP32 & Pi helpers**. Pi = `:desktop` host adb/fastboot. ESP32 =
  USB-serial bridge. FRP / screen-lock / HID credential injection
  refused. `:core:test` 407/0, `:desktop:test` 4/0,
  `:app:testDebugUnitTest` 35/0, `:app:assembleDebug`
  (`ai.rootforge.os.debug` / `0.1.0-debug`), `tests/run-tests.sh` 1055/0,
  `tests/lint.sh` clean. **Not run on a Pi, ESP32, phone, or desktop
  window.**
- Bootloader unlock portals (this branch): `:core` `UnlockPortals` on
  the Devices Root plan and Links **Bootloader unlock portals**. Live
  https: Motorola, Sony Open Devices, Xiaomi Mi Unlock, HTCdev, OnePlus,
  Fairphone. Pixel / Nothing have no token page; Samsung / ASUS / LG /
  Huawei closed (in-app note). Unlock-data step with dry-run first;
  browser only; never auto-sends device data. `:core:test` 403/0,
  `:desktop:test` 3/0, `:app:testDebugUnitTest` 35/0,
  `:app:assembleDebug` (`ai.rootforge.os.debug` / `0.1.0-debug`),
  `tests/run-tests.sh` 1055/0, `tests/lint.sh` clean. **Not run on a
  phone or a desktop window.**
- Brand root wizards + PC Devices (this branch): `RootGuides` Pixel /
  Samsung / Xiaomi / OnePlus / Motorola / generic; Odin is explanation
  only; same dry-run / typed confirm / no auto-reboot; desktop host
  adb/fastboot + two-column grid. Merged `origin/main` `3d05929` (#84).
  `:core:test` 396/0, `:desktop:test` 3/0, `:app:testDebugUnitTest` 35/0,
  `:app:assembleDebug` (`ai.rootforge.os.debug` / `0.1.0-debug`),
  `tests/run-tests.sh` 1055/0, `tests/lint.sh` clean. **Not run on a
  phone or a desktop window.**
- Multi-device identify (this branch): **Devices** lists every attached
  phone. USB attach + 2 s poll. Read-only identity; serials masked to
  last four characters; Root plan from the Custom RR catalog;
  `SerialOpLock` per serial; EDL identify only. Dry-run, typed confirm,
  backup-first, no auto-reboot unchanged. `applicationId` / signing /
  versioning unchanged. Pre-#84 local suite: `:core:test` 378/0,
  `:desktop:test` 2/0, `:app:testDebugUnitTest` 35/0, `:app:assembleDebug`
  (`ai.rootforge.os.debug` / `0.1.0-debug`), `tests/run-tests.sh` 1055/0,
  `tests/lint.sh` clean. **Not run on a phone.**
- Bug sweep (PR #84, on `main` as `3d05929`): replay cache never
  evicts a live nonce; Custom Recovery refuses unknown partition size;
  config import validates `autoConnect` / `terminalApp`; Android header
  reads use `readPrefix` (minSdk 26). External control stays off. On
  that PR: `:core:test` 363/0, `:desktop:test` 2/0,
  `:app:testDebugUnitTest` 34/0, `:app:assembleDebug`
  (`ai.rootforge.os.debug` / `0.1.0-debug`), `:app:lintDebug` 0
  errors, `tests/run-tests.sh` 1055/0, `tests/lint.sh` clean. **Not
  run on a phone.**
- Fleet config backup (`main` `3f7661d` #83):
  `config.export` / `config.import` advertised with `remoteAllowed=false`;
  remote callers `capability_off`; export omits secrets; import dry-run /
  skip / overwrite / merge-by-id; confirm required; never enables
  `remote.command`. Documented in `docs/MANUAL.md` / Help. After merging
  `3737c12` #82: `:core:test` 355/0, `:desktop:test` 2/0,
  `:app:testDebugUnitTest` 34/0, `:app:assembleDebug`
  (`ai.rootforge.os.debug` / `0.1.0-debug`), `tests/run-tests.sh` 1056/0,
  `tests/lint.sh` clean. **Not run on a phone.**
- Fleet `node.status.get` (this branch): protocol 1.1 STATUS-TRAY read on the
  existing Messenger service. Advertised `{kind:read, remoteAllowed:true,
  requiresConfirm:false}`. Snapshot shape as frozen in droidcommand-AI
  `docs/specs/STATUS_TRAY.md`. Pairing + default-off switch unchanged.
  Protocol minor stays 1. `applicationId` / signing / versioning unchanged.
  Local suite: `:core:test` 341/0, `:desktop:test` 2/0,
  `:app:testDebugUnitTest` 34/0, `:app:assembleDebug`
  (`ai.rootforge.os.debug` / `0.1.0-debug`), `tests/run-tests.sh` 1055/0,
  `tests/lint.sh` clean. **Not run on a phone.**
- Local module install on this phone (PR #81, on `main` as `b72d214`, after merging
  `origin/main` `ef088d3` / #80): Modules **This phone** tab installs a
  library zip via `su` (`magisk --install-module` / `ksud module install` /
  `apd module install`), auto-detects the manager, lists `/data/adb/modules`
  read-only, dry-run default, typed `INSTALL MODULE`, never reboots. LSPosed
  APKs unchanged. Desktop no-op. Same PR: **Links** directory of official
  https pages, browser only; **Glossary** drawer screen (`:core`
  `Glossary`) from Help and Links, same text in `docs/MANUAL.md`. After
  merging `ef088d3` / #80 plus Glossary: `:core:test` 324/0,
  `:desktop:test` 2/0, `:app:testDebugUnitTest` 34/0, `:app:assembleDebug`
  (`ai.rootforge.os.debug` / `0.1.0-debug`), `tests/run-tests.sh` 1055/0,
  `tests/lint.sh` clean. **Not run on a phone.**
- Custom RR → Custom Recovery (PR #80, on `main` as `ef088d3`): matching
  TWRP/PitchBlack catalog rows open Custom Recovery pre-filled; official
  lookup uses the probed phone; `pitchblack.tech` added to the https host
  allow-list. ROMs and other recoveries stay links. Documented in
  `docs/MANUAL.md` / Help. On that PR: `:core:test` 304/0; `:desktop:test`
  2/0; `:app:testDebugUnitTest` 31/0; `:app:assembleDebug`. **Not run on a phone.**
- Fleet protocol 1.1 (PR #79, on `main` as `988a941`): Messenger JSON `op` envelope with
  nonce replay/expiry, package+cert pairing, default-off external control, and
  `ApprovalCenter` for destructive remote actions (including Custom Recovery writes).
  Legacy `rootforge.*` envelope still accepted. Documented in `docs/MANUAL.md`
  Fleet integration (mirrored in in-app Help). After merging #78: `:core:test`
  296/0. Not run on a phone.
- How-to manual (PR #78, on `main` as `44716de`): `docs/MANUAL.md` plus in-app **Help**.
  On that PR: `:core:test` 252/0, `:desktop:test` 2/0, `:app:testDebugUnitTest` 31/0.
- Custom Recovery installer (PR #77, on `main` as `fabc649`): `:core`
  `ai.rootforge.os.recovery` plus Compose **Custom Recovery** screen.
  Dedicated recovery only; dry-run default; `FLASH RECOVERY`; no bundled
  images. On that PR: `:core:test` 248/0 (`recovery/` 41/0). **Not run on a
  phone.** Doc: `docs/CUSTOM_RECOVERY_INSTALLER.md`.
- Desktop mode (PR #76, on `main`): Android Dashboard card for VictorSuite (`com.termux`).
  Pin by package+cert, then RUN_COMMAND with a fixed `rootforge-desktop-mode`
  command, or a dedicated action VictorSuite does not export yet
  (`docs/DESKTOP_MODE.md`). Missing companion is a hint. Not run on a phone.
- Shizuku/Sui (PR #75, on `main`): fleet four-state manager + `PrivilegedShell` sole API, bound
  `IPrivilegedShell` UserService after `GRANTED`, approval-gated read-only Settings
  lookups; `pmGrant` hidden + typed confirm. Deps 13.1.5. `:core:test` 194/0 on that PR,
  `:app:assembleDebug` applicationId `ai.rootforge.os.debug`. **Not run on a phone.**
- Android launcher icon is the repo-logo emblem (adaptive + legacy mipmaps, navy
  `#0C1D36`). `tests/test_launcher_icons.py` 7/0; `:app:assembleDebug` packaged the
  new mipmaps (2026-10-19). Not installed on a phone.
- Custom RR catalog: Dashboard "Builds for this device" card (Android + desktop), Download over
  https or Import file; `:core` `catalog/` tests 20/0 (2026-10-19). Never affects flash gating.
  `INTERNET` is also used by Custom Recovery lookup/download (this branch). Not run on a phone.
  Doc: `docs/CUSTOM_RR_CATALOG.md`.
- Desktop `~/.rootforge/desktop.properties` is created mode `0600` and an existing
  world-readable copy is tightened before load (`DesktopSettings`, #64). `:desktop:test
  --tests ai.rootforge.os.desktop.DesktopSettingsTest` → 2 tests, 0 failures
  (2026-10-19). The headless API was not started, and this was not run on a phone.
- README landing page rewritten 2026-10-18 (docs only). Fleet claims checked against this
  repo's Messenger/pairing code and against droidcommand-AI `main` via the GitHub API:
  `FleetCatalog.kt` lists RootForge as `EXTERNAL_NODE`; `core-rootforge` has
  `BRIDGE_PROTOCOL_MAJOR = 1` and only an SSH transport. Live pairing on a phone is still
  unrun. v0.2.0 APK SHA-256 in the README matches the GitHub asset digest.
- Android `AdbConnection.sideload` seeks the package. `:app:testDebugUnitTest --tests ai.rootforge.os.adb.AdbConnectionTest` → 3 tests, 0 failures (handshake/push/pull, out-of-order block re-read, `FAILFAIL`). Not run against a phone in recovery. Desktop sideload still shells out to host `adb sideload`.
- License lock is in-tree from #53: `LICENSE` (GNU GPL v3 text; applying notice is
  GPL-3.0-or-later in `NOTICE`), `android-app/core/LICENSE` (Apache-2.0), `NOTICE`
  (split + third-party not relicensed). Not re-audited this session.
- Module display names are shell-quoted in generated `customize.sh` (scaffold
  script and `:core` `ModuleBuilder`). Newlines are still rejected. Verified on the
  quoting branch by sourcing a generated script whose name contained `$(touch …)`
  and a `"` breakout: the payload did not run, and `ui_print` received the name as
  text. `:core:test --tests ai.rootforge.os.module.ModuleBuilderTest` → 6 tests, 0
  failures at that time (not re-run this session). The generated module was not
  installed in Magisk.
- `android-apk.yml` on `e1f84ea` (run 38680669216) uploaded the debug APK
  published as v0.2.0 (SHA-256
  `faf743faba1b14aa5288f69883a0a1e2c284e43a77d7b32575ea3aaed8045e2f`). No
  release-signed APK (keystore secrets absent). **Not installed on a phone.**
- Hermetic suite / lint on 2026-10-18 (this README-refresh branch, docs only):
  `bash tests/run-tests.sh` → **1023 passed, 0 failed**. `bash tests/lint.sh` → **lint
  clean** with shellcheck 0.11.0 (pip `shellcheck-py`). Gradle (`:core:test`,
  `:app:testDebugUnitTest`, `assembleDebug`, `:desktop:compileKotlin`) was not run.
  The Flash Tools PR (#56) recorded `:core:test` 98/0, `:app:testDebugUnitTest` 25/0,
  `assembleDebug`, and `:desktop:compileKotlin`. The debug-key branch recorded
  `:core:test :app:testDebugUnitTest :app:assembleDebug` 126/0.
- Flash Tools is in-tree: `docs/FLASH_TOOLS_PLAN.md`, `:core` device-match / SHA-256 /
  persisted `OutputLog` / `UsbScanFilter`, Flash Tools Compose hub, same gate on
  desktop `HostController.fastbootFlash`. EDL-Commander JNI is plan-only.
- `./gradlew :desktop:packageDeb` was **not** re-run. Windows `.exe`/`.msi` not built.
  Docker image **not** built (`docker` is not installed on this agent). **Not run against a
  PC/phone pair.**
- `extract_ota.sh` / `rootforge ota extract` count an image as produced only when this run
  created or replaced it, judged by SHA-256 of the file. A reused output directory used to make
  an empty dump exit 0, and the CLI hashed a leftover `*.img` as if it had come from the payload
  just extracted. An earlier stamp compared `stat` `%Y.%N`, but GNU stat's `%N` is the file
  name, not nanoseconds, so a same-sized rewrite in the same second was reported as "produced
  no files" after the bytes had already been replaced.
- Implemented and stub-tested: device model + `device check`, flash contract, backup
  manifest/verify/import-legacy/restore, layered config, redacted private JSON-lines log with a CLI-side audit trail for state-changing commands and exit-time redaction of script logs,
  doctor severity model, OTA/boot/module/avd dispatch, provisioning with Calamares cleanup,
  Termux verified install + per-release metadata generation + CPU-honest SDK bootstrap,
  Makefile/`auto/build` failure handling, release-asset verifier, release workflow gating.
- **Not run anywhere:** `lb build` locally (no loop device here), ISO boot, Calamares
  install, systemd first boot, real devices, a phone, a desktop USB session,
  `docker build -f docker/Dockerfile`. Tag `v0.2.0` triggered `release.yml` run
  38682276372: checks, `build-iso`, and all four `build-termux-rootfs` jobs
  **succeeded**; the `release` job failed (`termux/make-release-metadata.sh`
  Permission denied — file was `100644`). Cancel was 403. v0.2.0 stayed
  published with the debug APK (`gh release create`). ISO/rootfs were not
  attached. The tree invokes the generator with `bash` (`release.yml`) and the script is mode `755`.
  PR #49 Toolkit CI (run 38523858203) after the `packages: platform-tools` fix: Lint,
  linux-desktop, windows-desktop, and docker all green. PR #50 changed
  `android-apk.yml`; this session did not re-check that workflow's jobs.
- **Known unpinned:** Ollama installer (hook 0020). Claude Code's native npm dependency.
  Nothing is signed.
- **Unsupported/not started:** UEFI/Secure Boot for the live ISO (BIOS only), remote
  administration. EDL-Commander JNI/NDK (Phase 4 of `docs/FLASH_TOOLS_PLAN.md` only).
  Android host-phone GUI APK, Flash Tools, Linux/Windows desktop, and Docker toolkit
  are on `main` (USB Host, not Termux). Toolkit API writes pin `-s` (#60, on
  `main`). Published Release **v0.2.0** has the debug APK only. The Termux
  *rootfs* install path still needs a locally generated or future ISO/rootfs
  release; the Python CLI can run from a git clone.
- Review: `docs/REVIEW.md`. App: `docs/ANDROID_APP.md`. Desktop/Docker: `docs/DESKTOP.md`.
  Bridge: `docs/DROIDCOMMAND_INTEGRATION.md`. Flash Tools: `docs/FLASH_TOOLS_PLAN.md`.
  Custom RR catalog: `docs/CUSTOM_RR_CATALOG.md`. Desktop mode: `docs/DESKTOP_MODE.md`.
  Custom Recovery installer: `docs/CUSTOM_RECOVERY_INSTALLER.md`. How-to: `docs/MANUAL.md`.

**Incident worth remembering:** merge `b02e7c4` silently reverted six build hooks to a flavour
that defined SHA-256 pins and never compared them (one also used an undefined variable),
while this file claimed verification. Repaired in `2253488`; `tests/check-hooks.sh` now
rejects unused pins. **A "done" line in a previous session's notes is not evidence — re-run
the check.**

**Next task (in order):**
1. On a host with loop devices: `sudo make build`; record log + digest. Then a QEMU boot +
   scripted Calamares install test (Stage 5 of the plan).
2. Extend the config schema only where a script consumes a key (Stage 3). Done: shared execution ID, `0600` script logs redacted at exit and by the CLI (pattern-based; SIGKILL outside the CLI and unrecognised secret shapes are the known gaps), and CLI-side audit events for `flash`/`backup`/`module`/`avd`/`boot patch`/`boot flash-last`.
3. Pin/replace the Ollama installer; add release signing (Stage 4).
4. Run the flash/backup/restore contract against a test device the owner agrees to flash.
The Android GUI APK landed in PR #48; Linux/Windows/Docker toolkit in PR #49.
Licensing is PR #53 (now on `main`). DroidCommand Messenger is PR #52 (on `main`).
Flash Tools is PR #56 (now on `main`). README accuracy is #57 (on `main`).
Toolkit API serial pin is #60 (on `main`). Module quoting is #59 (on `main`).
Sideload seek is #61 (on `main`). Debug signing key is #62 (on `main`).
The Root Guide, terminal hand-off, Kitsune, module work, the Custom RR build catalog, Shizuku/Sui (#75), Desktop mode (#76) and Custom Recovery installer (#77) are on `main`. The APK requests `INTERNET` for catalog Download and Custom Recovery lookup/download, both only on a tap, plus `RUN_COMMAND` / `DESKTOP_MODE` for the Dashboard Desktop-mode card. ISO Stage 5 is still blocked
and is still the next hardware task. A live host/target USB session
has not been run.

**Open questions:** the `refusal_message()` wording in `core/device.py` was reconstructed
from `docs/ARCHITECTURE_AUDIT.md`; the "governing directive" it cites was never found in the
repository. Whether Calamares expands `${USER}` in `shellprocess` as documented upstream is
unverified. An unexplained one-off 853 vs 852 shell-check count was seen once (before the
log/boot tests were added) and not reproduced in five subsequent runs.

## SESSION PROTOCOL

1. Read PROJECT STATE. 2. Inspect the real code and run the checks before trusting any prose
(including this file). 3. Plan, then make the smallest correct change. 4. Run
`bash tests/run-tests.sh` and `bash tests/lint.sh`; report real results and say plainly what
could not run. 5. Update PROJECT STATE (date, what changed, what was tested, what remains)
and any doc whose claims changed. 6. Commit; push only the designated branch; open a PR only
when asked.

Source of truth when sources disagree: the implementation > this file > other docs > plans.

## ACCURACY RULES (non-negotiable)

- Never fabricate facts, commands, flags, versions, digests, test results or capabilities.
  Never describe planned work as implemented. Prefer "I don't know / can't verify".
- Tag substantive claims **[Certain]** (verified), **[Likely]** (inferred), **[Guessing]**.
- Distinguish stubs, real tools, builds, VMs and hardware in every report.
- Do not agree to be agreeable; say when the premise is wrong.
- Stubs and placeholders are not features. A feature is done when its behaviour works and is
  tested or otherwise verifiable.

## SCOPE

In: the shared tool layer (`rootforge` CLI + scripts), the Debian ISO, the Termux/PRoot rootfs,
build/release/packaging, tests, docs, and (now on `main`) the `android-app/` Gradle project:
`:core` (shared Kotlin, Apache-2.0), `:app` (Android host-phone APK) and `:desktop`
(Compose desktop; Linux .deb/AppImage, Windows .exe/.msi), plus `docker/` toolkit image —
as clients of the same contracts, not reimplementations. Not started: remote management.
Out: embedding unrelated OSes or projects (integrate, don't copy), committing user data,
credentials, device images, build artifacts, or machine-specific config. Label anything
experimental `[EXPERIMENTAL]`/`[UNSUPPORTED]`. Never silently escalate privilege or assume
root. Discovery never implies authorization.

Before adding a subsystem: what is its purpose and owner, does it belong in core, platform,
client, tooling or an external integration, what does it cost to maintain, and does it
duplicate something external? If unclear, settle the boundary first.

## ARCHITECTURE RULES

- CLI-first: important behaviour is reachable from the CLI; a GUI or remote client is a
  client of the same contracts. Business logic is not duplicated across Bash and Python —
  scripts call `rootforge` for device/backup/config rules.
- Wrap, don't rewrite working scripts. Unknown is not safe (undeterminable slot, lock state or
  product blocks a write). No config key may disable a safety check.
- Destructive actions: one explicit target, a printed plan, a typed confirmation on
  `/dev/tty`, a re-check before writing.
- Downloads at build or install time pin a version/commit and verify a digest (or a key
  fingerprint / registry integrity) and fail on mismatch.

## ASK FIRST vs. PROCEED

Proceed: bug fixes following existing patterns, tests, doc corrections verified against code,
isolated changes. Ask first: destructive operations (disk/partition/volume deletion, history
rewrite, anything that flashes a real device), new major dependencies, core architecture or
API changes, privilege-model changes, large multi-component refactors, anything touching
secrets.

## COMMANDS

```
bash tests/run-tests.sh                                   # everything (no root/device/network)
bash tests/run-tests.sh shell | python                    # one half
PYTHONPATH=config/includes.chroot/usr/local/lib python3 -m unittest discover -s tests -p 'test_*.py'
bash tests/lint.sh                                        # needs shellcheck
bash tests/check-hooks.sh                                 # static hook rules
sudo make build                                           # ISO; needs root, loop device, ~20 GB
tests/verify-release-assets.sh <dir> --tag vX.Y.Z         # a release directory
# android-app/ (Gradle wrapper; same commands CI runs)
cd android-app && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
cd android-app && ./gradlew :app:verifyPaparazziDebug      # screenshot tests (Paparazzi)
cd android-app && ./gradlew :core:test :desktop:packageDeb :desktop:packageAppImage
docker build -f docker/Dockerfile -t rootforge-os-toolkit:ci .
```
`tests/run-tests.sh` needs PyYAML (`python3-yaml`) and several host tools; in a minimal
environment some sections fail for environmental reasons, so compare against CI before
assuming a regression.

## CI (`.github/workflows/`, verified 2026-10-15)

- `lint.yml` ("Lint"): PRs, pushes to `main`, and `workflow_call` from release. Jobs:
  shellcheck (`tests/lint.sh`), yaml-lint, package-lists, tests (`tests/run-tests.sh`),
  python.
- `android-apk.yml` ("Android APK"): PRs, pushes to `main`, `v*` tags, manual. Runs
  `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`; a release APK is built
  only when the `ROOTFORGE_KEYSTORE_*` repository secrets exist.
- `toolkit.yml` ("Toolkit packages"): PRs, pushes to `main`, `v*` tags, manual. Jobs
  linux-desktop, windows-desktop, docker.
- `release.yml`: only on `v*` tags or manual dispatch (`contents: write`); builds a
  debug APK and attaches it to a published release; ISO and Termux rootfs still
  generate and verify assets into a draft. Never trigger the ISO half without being asked.

Key files: `config/includes.chroot/usr/local/lib/rootforge/core/` (CLI package),
`.../sh/common.sh` (shared shell helpers), `config/includes.chroot/usr/local/bin/` (scripts),
`config/hooks/` (chroot hooks), `termux/` (rootfs, launcher, generator), `tests/README.md`.

## RULES

Note: `shellcheck_py-0.11.0.1-…whl` at the repo root was committed in `0aafccd`; it looks like
a tooling artifact rather than source [Likely]. Don't add more; ask before removing it.

Do: inspect before modifying; run tests after changes; report actual results; update docs
when behaviour changes; keep PROJECT STATE current. Don't: fabricate, claim unverified
features, commit secrets or artifacts, leave PROJECT STATE stale, flash real devices,
merge, tag or publish without being asked.

## Don's standing rules (apply to every change)
- Work on a branch and open a PR. Never push to `main`.
- Never merge a PR, and never ask to merge one, until every test/check on the PR passes.
- Never commit secrets (API keys, tokens, keystore passwords, `.env` files, credentials).
- Don't claim something works unless it was actually tested; say what was and wasn't run.

Where older text here or in `AGENTS.md` says "open a PR only when asked", Don's standing
rule above wins: changes go on a branch and through a PR.
