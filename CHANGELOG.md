# Changelog

All notable changes to RootForge OS. First published GitHub Release is **v0.2.0**
(tag on `e1f84ea`). Older tags `v0.1.0` and `v0.1.1` have no Release.
Nothing here has been validated on real hardware, a booted ISO, a VM or a phone; see
`docs/PLATFORM_SUPPORT.md`.

## Unreleased

### Custom RR catalog lookup (2026-10-19)
- New `:core` package `ai.rootforge.os.catalog`: parses the `catalog.json` / `freshness.json` format that Custom RR publishes and lists the ROMs, recoveries and root solutions whose entry names the target's product or codename (exact, case-insensitive, `/`-combined codenames split; unknown target → no matches). Links are exposed only if `https://`. Read-only and informational: it does not feed `DeviceMatchGate` or any flash path. No Custom RR code or data is copied in; tests use a synthetic fixture. No screen and no fetch yet (the APK has no `INTERNET` permission). See `docs/CUSTOM_RR_CATALOG.md`. `:core:test` 109/0.

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
