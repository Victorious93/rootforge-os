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
