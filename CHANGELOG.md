# Changelog

All notable changes to RootForge OS. The project has tags `v0.1.0` and `v0.1.1` but no
published GitHub Release; entries below are under **Unreleased** until one is cut.
Nothing here has been validated on real hardware, a booted ISO, a VM or a phone; see
`docs/PLATFORM_SUPPORT.md`.

## Unreleased — 2026-10-07 (architecture repair)

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
