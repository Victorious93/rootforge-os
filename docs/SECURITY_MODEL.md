# RootForge OS — Security model

Status as of 2026-10-07. This document says what the code enforces, what it does not,
and what depends on the operator. It does not describe a security posture the project
does not have.

## 1. What RootForge protects against

RootForge operates on devices that may hold someone's data and on hosts that run it as
root. Its controls target **mistakes and supply-chain drift**, not an adversary with
access to the operator's account:

| Threat | Control | Where | Verified by |
|---|---|---|---|
| Flashing the wrong device | Exactly one device selected explicitly; plan shows serial, product, slot; typed `FLASH <serial>` / `RESTORE <serial>` on `/dev/tty` | `flash_patched_boot.sh`, `restore_partitions.sh`, `common.sh:rf_confirm` | Stub tests |
| Writing to an unknown or locked device | `device check` blocks: probe failed, vendor refused, bootloader locked/unknown (`unlocked` variable only), partition unreported, image too large, slot layout unknown | `core/device.py` | `tests/test_device.py` |
| Writing the wrong slot | Explicit `--slot`; `--both-slots` needs `--slots-same-build` | flash script | Stub tests |
| Restoring a corrupt or foreign backup | Manifest with per-image SHA-256/size, device and slot; restore verifies, re-hashes before writing, matches product/slot | `core/backup.py`, `restore_partitions.sh` | `tests/test_backup_verify.py`, stub tests |
| Path escape from a backup/sums file | Names with separators, `..`, symlinks rejected | `core/backup.py` | Tests |
| Truncated download cached as good | Atomic rename + minimum size; pinned digests for tool downloads | `common.sh:rf_download_cached`, `rf_fetch_verified` | Stub tests |
| Tampered build-time download | Pinned version/commit + SHA-256, hard fail on mismatch; NodeSource key fingerprints; npm integrity | `config/hooks/*` | **Static checks only** (`tests/check-hooks.sh`); not run in a chroot |
| Stale artifact released | `auto/build` removes old ISO/digest; `make` stops on failure; release verifier checks the full set | `auto/build`, `Makefile`, `tests/verify-release-assets.sh` | Stub tests |
| Unverified rootfs on a rooted phone | `rootforge-chroot.sh install` requires `--sha256`/`--sha256-file`, scans for absolute paths, `..`, device nodes; staged install with completion marker | `termux/rootforge-chroot.sh` | Tests |
| Placeholder digests shipped | Templates refuse to run; generator refuses surviving placeholders | `termux/templates/`, `make-release-metadata.sh` | Tests |
| Secrets in logs | Key-name and token-shape redaction in the file and in the terminal echo (CLI logs) | `core/log.py` | `tests/test_log.py` |
| Secrets left in script logs | Logs/reports redacted at script exit (`rf_redact_registered`) and by the CLI after the run | `common.sh:rf_redact*`, `core/log.py`, `core/audit.py` | Parity table through both engines; exit-route tests (0, `exit N`, `set -e`, SIGTERM, SIGHUP); end-to-end `join_headscale.sh` |
| Unrecorded state-changing commands | `flash`/`backup`/`module`/`avd` and `boot patch`/`flash-last` write start/finish events with exit status and script links | `core/audit.py` | `tests/test_audit.py`, shell audit section |
| Logs readable by other users | CLI JSON logs and all script logs/reports created `0600` from creation; handed to `$SUDO_USER` under sudo | `core/log.py`, `common.sh:rf_private_file`/`rf_log_init` | Tests, incl. under `umask 000` |
| Secrets in files | API-key file created 0600 from creation (`rf_write_private`); values shell-quoted (`rf_shell_quote`) | `setup_ai_tools.sh` | Stub tests |
| Provisioning the wrong account / root | Target user resolved explicitly; refuses rather than defaulting to root; live user removed; live sudo rule deleted; installed name cannot be `root`/`rootforge` | `00_bootstrap_distro.sh`, Calamares configs | Executed in a sandbox; **not** under real Calamares |

## 2. What it does not protect against

- **Authentication and authorization do not exist.** Anyone who can run `rootforge` and
  type the confirmation can flash a device. There are no users, roles or tokens.
  `ROOTFORGE_ASSUME_YES=1` bypasses the typed prompt (it prints a notice); it exists for
  `fleet_orchestrate.sh` and must be treated as a root-equivalent switch.
- **The typed confirmation is a speed bump, not a control**: a script or a person who
  pipes `FLASH <serial>` to it will pass.
- **Privilege is not managed by the CLI.** It runs with the invoking user's privileges;
  `make build/flash/clean` check for root, the CLI does not drop it.
- **Ollama's installer (hook 0020) is not pinned.** It is downloaded to a file, checked
  non-empty and run as root at build time; what it installs is whatever Ollama serves that
  day. Pinning needs the release digests, which could not be fetched in the session that
  wrote this.
- **Claude Code's native per-platform dependency (npm optional dependency) is not
  separately pinned**; the main package's integrity is.
- **NodeSource key fingerprints were captured by trust-on-first-use** (2026-10-07) and not
  cross-checked against a second source. The `nodejs` package version floats within 22.x
  (the repository is signed).
- **A release's `SHA256SUMS` and the files it lists are published together.** It detects
  corruption and single-file tampering; it does not protect against an attacker who
  controls the whole release. Nothing is signed (no GPG/minisign/Sigstore).
- **No secret scan has been run** over the tree or history by a dedicated tool.
- **The audit trail is local and editable.** Every state-changing command writes a start and a
  finish event (exit status, scripts run, script logs) tied by one execution ID, but the
  files are ordinary `0600` files owned by the operator: anyone with that account can edit or
  delete them, nothing is signed, chained or shipped off the machine. A command that argument
  parsing rejects records nothing, and if the log cannot be opened the command still runs
  (with a warning).
- **Script-log redaction is best effort.** Logs and reports are redacted when the script
  exits and again by the CLI after the run, using shape/name patterns (token formats, private
  keys, `--authkey X`, `NAME=value` for secret-looking names). A secret with no such shape or
  name passes through; the rules over-redact on purpose; the terminal is not redacted; the
  secret is in the `0600` file until exit; and a script SIGKILLed outside the CLI is never
  redacted. Do not rely on it as a substitute for not printing secrets.
- **Logs are private only for new files.** An existing log or report keeps the mode it has;
  directories keep the umask default.
- **Android-side trust is out of scope**: bootloader unlock wipes data by design; nothing
  here restores a vendor's attestation.

## 3. Privileged operations (where root is used)

| Operation | Why root | Gate |
|---|---|---|
| `auto/build`, `make build/clean/distclean/flash` | loop devices, `lb`, `dd` to a block device | `make check-root` / script check |
| `00_bootstrap_distro.sh` system stages | apt, udev rule, group membership | Refuses unless root for those stages; user stages re-exec as the target user |
| `rootforge-firstboot.service` | runs the above once | Only on an installed system (`/etc/rootforge-installed`) |
| `rootforge-chroot.sh` | `su`, `mount`, `chroot` on a rooted phone | Operator already has root; archive scanned before extraction |
| `harden_kernel.sh`, `harden_system.sh` | sysctl, GRUB, AppArmor, auditd, nftables, USBGuard | Typed confirmation; not shipped in Android rootfs variants |

## 4. Secrets handling rules for contributors

Never commit credentials, keys, tokens, device images or backups. Anything that writes a
secret must use `rf_write_private` and `rf_shell_quote`; anything that logs must go
through `rootforge.core.log` or avoid the value. Tests use stubs and scratch homes; no test
may touch the real `$HOME` or system paths (see `tests/README.md` for the seams).

## 5. Reporting

There is no security contact or disclosure policy file in this repository yet. Until one is
added, open a private channel with the maintainer rather than a public issue.
