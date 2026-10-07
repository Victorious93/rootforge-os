# RootForge OS — Architecture

Victorious Framework | Origin Source Labs

Status: describes the repository as of 2026-10-07 (branch `claude/new-session-78u9fc`).
Every statement is about code that exists. Planned work is in
`docs/IMPLEMENTATION_PLAN.md` and is labelled there, never here. Confidence tags:
**[Certain]** read in code or exercised by the test suite, **[Likely]** inferred,
**[Unverified]** depends on something this repository's tests do not run (real
devices, a built ISO, systemd, Calamares, a phone).

## 1. What exists

RootForge OS is three deliverables sharing one tool layer:

1. **A Debian 12 live/installer ISO** (live-build, amd64, BIOS boot) that carries the
   tools below, a GNOME desktop and the Calamares installer.
2. **A Termux/PRoot (and rooted-chroot) Debian rootfs** for Android devices, built
   with debootstrap, carrying the same tools minus what an Android kernel cannot run.
3. **The tools themselves**: a Python CLI (`rootforge`) in front of ~30 Bash scripts
   for Android device work (bootloader, boot images, partition backup/restore, OTA
   extraction, modules, emulators, hardening, VPN, notes).

There is no Windows build, no Android application, no GUI of its own and no remote
administration protocol. See `docs/PLATFORM_SUPPORT.md`.

## 2. Layering

```
operator
  |
  v
rootforge  (usr/local/bin/rootforge: POSIX sh shim -> python3 -m rootforge.core.cli)
  |
  |-- rootforge.core.cli        argparse tree, allow_abbrev=False, dispatch, exit codes
  |-- rootforge.core.device     device probing, DeviceProfile, write_blockers(), compatibility
  |-- rootforge.core.backup     manifest v1, verification, legacy import
  |-- rootforge.core.config     layered, validated YAML configuration
  |-- rootforge.core.log        JSON-lines audit log with redaction
  |-- rootforge.core.doctor     environment checks with declared severity
  |-- rootforge.core.{flashing,boot,ota,module,avd,devices}   command groups
  |-- rootforge.core.runner     finds and executes the wrapped scripts
  |
  v
usr/local/bin/*.sh            the device-facing work; also directly invocable
  |
  v
usr/local/lib/rootforge/sh/common.sh   confirmation gate, hashing, fastboot/adb waits,
                                       verified downloads, device-profile bridge
  |
  v
adb / fastboot / magiskboot / avbtool / payload-dumper-go / Android SDK
```

**[Certain]** The CLI wraps the scripts; it does not reimplement the device work. What
the CLI owns is what a shell script is bad at: argument validation, the device model,
the backup manifest, configuration, and structured logging. The scripts call back into
the CLI (`rootforge device check`, `rootforge backup verify --json`) for those, so there
is one implementation of each rule, not a Python copy and a Bash copy.

## 3. Contracts

### 3.1 Device model and the write gate (`device.py`)

- `fastboot getvar all` writes to **stderr** in real fastboot; the parser reads stdout
  and stderr, splits each `(bootloader) key: value` on the first `: `, and keeps
  argument-bearing names (`partition-size:boot_a`) intact.
- Bootloader state is taken from the `unlocked` variable only. `secure` is a different
  property and is not treated as lock state.
- Slot layout is derived from `slot-count`/`current-slot`/`has-slot:<partition>`; an
  undeterminable layout is a **blocker**, not a default of "A/B".
- `DeviceProfile` carries `probe_ok`, `refused` (vendor workflows RootForge declines to
  automate) and `supported`. `write_blockers(partition, image_size, both_slots)` returns
  the reasons a write must not proceed: probe failed, vendor refused, bootloader locked or
  unknown, partition not reported, image larger than the partition, slot layout unknown.
- `compatibility_findings(profile, expect_product, expect_slot, expect_bootloader_version)`
  is what restore uses to refuse a backup taken from a different device or slot.
- `rootforge device check` prints the findings and exits **3** when blocked.

### 3.2 Flashing (`flash_patched_boot.sh`, `rootforge flash boot`)

Order of operations: validate the image (exists, `ANDROID!` header) → select exactly one
device → `device check` → print the plan → typed `FLASH <serial>` on `/dev/tty` →
re-check → `fastboot --slot <explicit> flash` per target slot → reboot → wait for
`sys.boot_completed`. Both slots require `--slots-same-build` (an operator assertion
RootForge cannot verify). Exit codes: 0 booted, 2 usage, 3 blocked, 4 flashed but boot
unverified, 5 reboot failed, 130 interrupted. **[Certain]** against stubs;
**[Unverified]** on hardware.

### 3.3 Backup and restore (`backup.py`, `backup_partitions.sh`, `restore_partitions.sh`)

A backup directory contains `<partition>.img` files and `manifest.json` (version 1):
device (product, slot, bootloader), per-entry partition/file/sha256/size/method/slot, and a
**trust** field — `captured` (written by `backup_partitions.sh` from a probed device) or
`legacy-imported` (converted from an old `SHA256SUMS`-only backup; device and slot
unknown). Verification rejects: missing manifest, unlisted files, symlinks, empty files,
size or digest mismatch, missing files, duplicate entries, and names that escape the
directory. Restore flashes **only verified manifest entries**, to a device whose product
and slot match, after typed `RESTORE <serial>`, re-verifies and re-hashes immediately
before writing, stops at the first failed write, and never reboots. Legacy imports
require `--accept-legacy-import`. Backup exit codes: 0 complete, 4 partial, 1 failure.

### 3.4 Configuration (`config.py`)

Precedence, lowest to highest: built-in defaults < `~/.config/rootforge/config.yaml` <
project `rootforge.yaml` (found by walking up) < `$ROOTFORGE_HOME/devices/<codename>/rootforge.yaml`
< command-line option. Each layer is schema-validated before merging; an invalid value is
an error naming the file. The only schema key today is `backup.partitions`. **No key can
disable a safety check** (device validation, typed confirmation, integrity verification) —
this is deliberate. `rootforge config show [--json] [--codename CODENAME]` prints the
effective configuration. `backup_partitions.sh` reads its default partition list from it,
and falls back to a built-in list (with a notice) if the CLI or config is unavailable.

### 3.5 Logging and diagnostics (`log.py`, `doctor.py`, `common.sh`)

One `rootforge` invocation is one **execution**: `main()` runs the command inside
`execution_scope()`, which sets `ROOTFORGE_EXECUTION_ID` (a valid inherited value is kept;
anything but a 4–32 character alphanumeric token is ignored). Every `Logger` the command
opens shares that ID, writing one JSON-lines file per command under `$ROOTFORGE_HOME/logs/`
(`rootforge-<command>-<id>.jsonl`), and every wrapped script inherits it. A script's
`rf_log_init <file>` (in `common.sh`) creates its log `0600` from creation, hands a new file
to `$SUDO_USER` when run under sudo, and writes `# rootforge execution <id>: <script> ...` as
the first line, so a script log can be matched to the CLI's JSON log for the same run. Reports
(`rf_private_file`) are `0600` without the header. A script started directly generates its
own ID.

Secret-looking field names and known token shapes are redacted in the file **and** in
anything echoed to the terminal. `doctor` runs independent checks; each declares whether its
absence is an error or a warning via `@optional_check`, and a check that raises keeps its
declared severity.

**Audit trail (`audit.py`).** State-changing commands — everything under `flash`, `backup`,
`module` and `avd`, plus `boot patch` and `boot flash-last` — run inside `audited()`, which
writes `command started` (command, redacted argument list, euid, `SUDO_USER`, cwd) and
`command finished` (exit status, duration, the scripts that ran with their exit statuses,
and the script logs stamped with this execution ID) to `rootforge-<command>-<id>.jsonl`.
Python-native commands (`backup verify`, `backup import-legacy`) are covered the same way.
The exit status is returned untouched (non-zero is logged at `warn`, since 3 = blocked and
4 = partial are outcomes, not crashes); an exception, including Ctrl-C, is recorded as
`command crashed` and re-raised; if the log cannot be opened the command still runs and a
warning says it is unrecorded. `doctor`, `ota extract` and `boot inspect/unpack/repack/cpio/
verify` log themselves; read-only commands (`devices`, `device`, `config`) are not audited.
**Limits:** script log *contents* are not redacted, and a command rejected by argument
parsing runs nothing and records nothing.

### 3.6 Dispatch (`runner.py`)

`find_script` looks beside the installed package first, then `/usr/local/bin`, then
`PATH`, so a checkout runs its own scripts. Output is not captured by default, because the
scripts prompt on `/dev/tty` and capturing would hide the prompt. Script exit codes pass
through unchanged: several use non-zero to report a finding.

## 4. Provisioning (installed system)

```
Calamares users page (account name)  ->  users.conf: forbidden_names [root, rootforge]
Calamares removeuser                 ->  deletes the live user "rootforge"
Calamares shellprocess               ->  touch /etc/rootforge-installed
                                         rm /etc/sudoers.d/rootforge-live
                                         write /var/lib/rootforge/install-user  (= ${USER})
                                         systemctl enable rootforge-firstboot.service
first boot: rootforge-firstboot.service (root, oneshot, Restart=on-failure)
  00_bootstrap_distro.sh
    target user: --user | $SUDO_USER | install-user file | invoking non-root user | refuse
    system stages (root):  packages, udev, groups        markers: /var/lib/rootforge/provision/
    user stages  (target): workspace, sdk                markers: <workspace>/.provision/
```

The target is never defaulted to root. Stages are idempotent and resumable; the SDK is
fetched into a user-owned staging directory against a pinned SHA-256 and swapped in
atomically. **[Certain]** the shellprocess commands are executed in a sandbox by the test
suite and the provisioner's `--check` then runs against the result. **[Unverified]**
that Calamares expands `${USER}` in `shellprocess` as documented upstream, that
`removeuser` runs in the order assumed, and everything under a real systemd.

## 5. Build and release

- `sudo make build` → `auto/build` → `lb build noauto` → rename to
  `rootforge-os-amd64.hybrid.iso` → `make checksum`. `auto/build` removes stale outputs
  first and propagates `lb`'s exit status; `make` no longer masks it.
- Hooks (`config/hooks/*.hook.chroot`, flat directory, POSIX sh, `set -e`) pin and verify
  what they download — except Ollama's installer (hook 0020), which is unpinned. A static
  check (`tests/check-hooks.sh`) rejects unused pins, "latest" resolution beside a pin,
  `curl | sh`, `curl` without `-f`, and unpinned `npm install -g`.
- `release.yml` (tag `v*`): `checks` (the lint workflow, same commit) → ISO build and
  four Termux rootfs builds → `termux/make-release-metadata.sh` generates `install.sh`, the
  proot-distro plugin, `rootforge-chroot.sh`, `release-metadata.json` and `SHA256SUMS`
  from the real tarballs → `tests/verify-release-assets.sh` → **draft** release.
  **[Unverified]** the workflow has not run on GitHub since these changes.
- The live ISO boots **BIOS only** (isolinux). UEFI and Secure Boot are unsupported.

## 6. Trust boundaries

See `docs/SECURITY_MODEL.md`. In one sentence: nothing here authenticates *who* is
operating RootForge; it prevents *mistakes* (wrong device, wrong slot, corrupt image,
stale artifact, unverified download) and makes destructive actions explicit.

## 7. Design decisions

| # | Decision | Reason |
|---|---|---|
| D1 | The CLI is the single implementation of device, backup, config and log rules; scripts call it | A Bash copy and a Python copy of "is it safe to flash" will diverge; they did |
| D2 | Unknown ≠ safe: an undeterminable slot layout, lock state or product is a blocker | The old code defaulted to A/B and treated `secure` as `unlocked` |
| D3 | Slot is always explicit (`--slot`), and `--both-slots` needs an operator assertion | "Both slots same build" is not knowable from fastboot |
| D4 | Restore trusts a manifest, not a sidecar of hashes | A hash list cannot say which device or slot an image came from |
| D5 | The provisioner resolves its user from the installer's record and refuses otherwise | The first-boot service runs as root; guessing leaves SDK trees owned by root |
| D6 | Provisioning is staged with markers; the unit retries | A first boot without network must not leave a half-provisioned system marked done |
| D7 | Install metadata is generated per release from the real artifacts; checked-in copies are templates that refuse to run | Hand-typed digests/URLs drifted from the artifacts and shipped placeholders |
| D8 | Termux installs require a digest; arm64 hosts install only what runs there | Google ships x86-64 SDK/NDK/emulator only; claiming otherwise installed unrunnable binaries |
| D9 | A failed build must be a failed `make`, and a stale ISO must not survive | `tee` masked the status and `checksum` blessed a leftover image |
| D10 | Wrap, don't rewrite, working scripts | Their behaviour is proven by real incidents recorded in `common.sh` comments |
