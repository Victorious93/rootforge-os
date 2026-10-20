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

> **Update 2026-10-20 (Custom Recovery installer):** branch `feat/recovery-installer`.
> New drawer screen **Custom Recovery** (Android + desktop) installs official TWRP
> (`twrp.me/search.json` + `dl.twrp.me/<codename>/`) and PitchBlack
> (`pitchblackrecovery.com`, GitHub releases, SourceForge) on a **rooted** phone
> with `su`/`dd`. Detects `ro.product.device` / `ro.build.product`, `su -c id`,
> A/B vs A-only, and dedicated `recovery`/`recovery_a`/`recovery_b` vs recovery-in-boot
> (`vendor_boot`/`boot`). Writes only the dedicated recovery partition; never `boot`
> or `vendor_boot`. Dry-run default; typed `FLASH RECOVERY` via `ApprovalCenter`;
> backup of the current partition into the library first; reboot to recovery is a
> separate button. Images are not bundled; https official hosts only; TWRP `.md5`
> checked when present, SHA-256 always shown. `applicationId` / signing / versioning
> unchanged (`aapt dump badging`: `ai.rootforge.os.debug` / `versionCode=1` /
> `versionName=0.1.0-debug`). On main this installer did **not** exist: Recovery
> is USB fastboot (Boot once / Flash) plus browser links; sideload is ADB
> `sideload-host`. Merged `origin/main` (`cda8e3f`, #75 Shizuku) — docs-only
> conflicts. Re-verified on merge commit `904d9fa`: `:core:test` 236/0
> (`recovery/` 41, `shizuku/` 26), `:desktop:test` 2/0, `:desktop:compileKotlin`,
> `:app:testDebugUnitTest` 29/0 (Paparazzi `customRecovery` + `settings`
> recorded), `:app:assembleDebug` (`aapt dump badging`: `ai.rootforge.os.debug`
> / `versionCode=1` / `versionName=0.1.0-debug`; `INTERNET` +
> `moe.shizuku.manager.permission.API_V23`). `bash tests/run-tests.sh` → 1023/0;
> `bash tests/lint.sh` clean (shellcheck 0.11.0 via the repo wheel). CI on
> `904d9fa` (PR #77): all 9 checks green (apk, tests, shellcheck, python,
> yaml-lint, package-lists, linux-desktop, windows-desktop, docker). **Not run:**
> a phone, a live TWRP/PBRP download+write, `:app:verifyPaparazziDebug` full set
> (fails on `modules`, stale on `main` since #69). Doc:
> `docs/CUSTOM_RECOVERY_INSTALLER.md`.

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

**Last updated:** 2026-10-20 · **Branch:** `feat/recovery-installer`
· **Phase:** active development; ISO/VM/hardware
validation **blocked** on infrastructure. Android host-phone APK, Flash Tools,
desktop/Docker toolkit, DroidCommand bridge, license lock, toolkit API serial pin (#60),
APK-on-tag release (#58), module quoting (#59) and sideload seek (#61) are on `main`.
On `main` from recent work: Root Guide (#65), terminal app hand-off and `com.termux` default (#67, #68), Kitsune manager plus module repositories/creator (#69), the Custom RR build catalog with a Dashboard card (#66), and Shizuku/Sui (#75). Custom Recovery installer is this branch (not on `main` yet). The APK requests `INTERNET` for catalog Download and for Custom Recovery lookup/download, both only when tapped. None run on a device. README refresh is #63 and the desktop API token file mode is #64. License lock (#53):
original work GPL-3.0-or-later (`LICENSE`); shared Kotlin `:core` Apache-2.0
(`android-app/core/LICENSE`); third-party/Termux/Debian/SDK code not relicensed (`NOTICE`).

**What is true now** (docs + hermetic suite this session; older bullets were not re-audited unless noted):
- Custom Recovery installer (this branch): `:core` `ai.rootforge.os.recovery` plus
  Compose **Custom Recovery** screen. Dedicated recovery only; dry-run default;
  `FLASH RECOVERY`; no bundled images. After merging #75: `:core:test` 236/0
  (`recovery/` 41/0, `shizuku/` 26/0); `:desktop:test` 2/0;
  `:app:testDebugUnitTest` 29/0; `:app:assembleDebug` (`ai.rootforge.os.debug`
  / `0.1.0-debug`). Paparazzi `customRecovery` and `settings` recorded.
  `tests/run-tests.sh` 1023/0; `tests/lint.sh` clean (shellcheck 0.11.0).
  PR #77 CI on `904d9fa`: 9/0. **Not run on a phone.** Doc:
  `docs/CUSTOM_RECOVERY_INSTALLER.md`.
- Shizuku/Sui (PR #75, on `main`): fleet four-state manager + `PrivilegedShell` sole API, bound
  `IPrivilegedShell` UserService after `GRANTED`, approval-gated read-only Settings
  lookups; `pmGrant` hidden + typed confirm. Deps 13.1.5. `:core:test` 194/0,
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
  Custom RR catalog: `docs/CUSTOM_RR_CATALOG.md`. Custom Recovery installer:
  `docs/CUSTOM_RECOVERY_INSTALLER.md`.

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
The Root Guide, terminal hand-off, Kitsune, module work, the Custom RR build catalog and Shizuku/Sui (#75) are on `main` (see Updates 2026-10-19). Custom Recovery installer is on `feat/recovery-installer` (not on `main` yet). The APK requests `INTERNET` for catalog Download and Custom Recovery lookup/download, both only on a tap. ISO Stage 5 is still blocked
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
