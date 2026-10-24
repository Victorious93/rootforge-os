# RootForge OS — Security model

Status as of 2026-10-07. The desktop API-token row was added 2026-10-19; the approval-prompt,
USB-length and toolkit-API rows, and the redaction gap in §2, were added 2026-10-24; the rest of
this document was not re-audited then. This document says what the code enforces, what it does not,
and what depends on the operator. It does not describe a security posture the project
does not have.

## 1. What RootForge protects against

RootForge operates on devices that may hold someone's data and on hosts that run it as
root. Its controls target **mistakes and supply-chain drift**, not an adversary with
access to the operator's account:

| Threat | Control | Where | Verified by |
|---|---|---|---|
| Installing an untrusted module as root | Typed `INSTALL MODULE` approval that shows the zip's name, size, SHA-256 and the manager, before anything is pushed (USB target) or before `su` runs (this phone). Dry-run is the default for this-phone install and only logs the command. RootForge cannot inspect a module's scripts; the creator's custom text is the author's own. This-phone install never reboots. | `TargetController.installModuleOnTarget`, `TargetController.installModuleLocally`, `HostController.installModuleOnTarget`, `:core` `LocalModules` | `:core` unit tests; not run on a device |
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
| Desktop API token readable by other local users | `~/.rootforge/desktop.properties` created `0600` before the bearer token is written; an existing group/world-readable file is tightened before load and before rewrite. The directory keeps the umask | `android-app/desktop` `DesktopSettings` | `:desktop:test` `DesktopSettingsTest` (POSIX). Not run on a phone |
| Remote build catalog treated as authority | Custom RR catalog is not read by `DeviceMatchGate`/`FlashSafety`; fetched only on a tap, https only, 8 MiB cap, parsed before it replaces the stored copy; links opened only if `https://`. A matching TWRP/PitchBlack row can open Custom Recovery; that path still uses the probed phone, the installer host allow-list, dry-run, SHA-256, published checksums, backup, and typed `FLASH RECOVERY`. ROMs and other recoveries stay links | `:core` `catalog/` | `:core:test` `BuildCatalogTest`, `BuildCatalogStoreTest`, `CatalogRecoveryBridgeTest` |
| Writing the wrong recovery (or boot) with su | Dedicated `recovery`/`recovery_a`/`recovery_b` only; unknown slot, unknown partition size, and recovery-in-boot refuse the write; dry-run default; backup to app storage; typed `FLASH RECOVERY`; official hosts only; MD5 when TWRP publishes one, SHA-256 always shown | `:core` `recovery/`, Custom Recovery screen | `:core:test` `RecoveryLayoutTest`, `RecoveryCommandsTest`, `RecoveryPlanTest`, `RecoveryInstallerTest`, `RecoveryCatalogTest`. Not run on a phone |
| A different app pretending to be VictorSuite | Desktop mode pins VictorSuite by package name **and** signing-certificate SHA-256 before any intent is sent. A cert change needs a new pin. The intent is either the dedicated Desktop-mode action or Termux RUN_COMMAND with a fixed `rootforge-desktop-mode <op>` line; the UI never interpolates a shell. No shared signing key. | `:core` `desktopmode/`, Android Dashboard card | `:core:test` `DesktopModeTest`. Not run on a phone |
| Privileged host-phone reads via Shizuku | Sole API is `PrivilegedShell` (argv `ProcessBuilder`, 64 KiB cap, never logs stdout/stderr). UI ops are read-only (`exec` / `readDeviceState`) and each one goes through `ApprovalCenter`. `pmGrant` exists for fleet interop only: typed `GRANT PERMISSION`, not shown in Settings. No flash/unlock/wipe. Output stays on the Settings card. | `:core` `shizuku/`, `:app` `BoundPrivilegedShell` / `IPrivilegedShell.aidl` | `:core:test` `ShizukuStateResolverTest`, `ShizukuManagerTest`, `PrivilegedShellTest`, `PrivilegedCommandsTest`. Not run on a phone |
| Fleet remote-control impersonation / replay | Protocol 1.1 authorizes only by caller package + signing-cert SHA-256 (Binder/`Message.sendingUid`, never envelope `trigger` / `sourceNodeRef`). External control default off. Expired (`timestampMs+ttlMs` or >120 s skew) and duplicate nonces keyed `(sourceNodeRef, nonce)` are refused; the seen-nonce cache is kept ≥ 10 minutes and never evicts a still-live nonce (a full cache is `internal`). Destructive ops go through `ApprovalCenter` (typed phrase); a remote trigger never auto-approves. | `:core` `bridge/FleetExecutor`, `ReplayGuard`, `RootForgeBridgeService` | `:core:test` `FleetEnvelopeTest`, `ReplayGuardTest`, `FleetAuthTest`, `FleetCapabilitiesTest`, `FleetExecutorTest`. Not run on a phone |
| Fleet settings dump / restore | `config.export` / `config.import` are advertised with `remoteAllowed=false`; a remote caller is `capability_off`. Export omits tokens, passwords, signing material and pairing secrets. Import needs on-device confirm, never enables `remote.command` / external control, rejects unknown or oversized payloads, and refuses `autoConnect` / `terminalApp` values that Settings would not accept. | `:core` `config/ConfigBackup`, `bridge/FleetExecutor` | `:core:test` `ConfigBackupTest`, `FleetCapabilitiesTest`, `FleetExecutorTest`. Not run on a phone |
| A paired controller shows less than it runs | Approval prompts for control-tier actions list every parameter whole. A value over 12,100 JSON characters, or any nested value, is refused (`invalid_parameters` / `bad_parameters`) before anyone is asked, so nothing is cut off and nothing deep is stringified | `:core` `bridge/ApprovalPrompt`, `FleetExecutor`, `BridgeExecutor` | `:core:test` `ApprovalPromptTest`, `FleetExecutorTest`, `BridgeExecutorTest`. Not run on a phone |
| A faulty or hostile USB target sizes a buffer | Main ADB payloads are bounded to 256 KiB; sync `DATA` / `FAIL` lengths (1 MiB / 64 KiB) and Sahara command-response lengths (64 KiB) are checked before they size an allocation | `:app` `AdbConnection`, `SaharaClient` | `:app:testDebugUnitTest` `AdbConnectionTest`, `SaharaFirehoseTest` (in-memory pipes). Not run on a phone |
| Toolkit / desktop API token read by timing or by other local users | Docker toolkit API: token compared with `hmac.compare_digest`; a generated token file is created `0600` with `O_EXCL` and never through a symlink (a stale file is replaced); a bad `Content-Length` or over-deep JSON is a `400`. Desktop `HeadlessApi` compares in constant time | `docker/api/rootforge_api.py`, `android-app/desktop` `HeadlessApi` | `tests/test_toolkit_api.py`, `:desktop:test` `HeadlessApiAuthTest`. The container was not built |
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
- **Redaction misses a name glued to a non-ASCII letter.** The `NAME=value` rule needs a word
  boundary before the name. Under a UTF-8 locale neither the Python nor the sed engine redacts
  `密码password=hunter2` or `éapi_key=abc` (sed under `LC_ALL=C` does). The Python pattern is also
  quadratic on long runs of `[A-Za-z0-9_.-]` (40 KB of `a-a-a-…` took 63 s; sed 0.03 s). Reproduced
  2026-10-24, not fixed: a verified replacement is described in `docs/REVIEW.md`.
- **Logs are private only for new files.** An existing log or report keeps the mode it has;
  directories keep the umask default.
- **The Custom RR catalog is unauthenticated third-party data.** It comes from
  `monsiu/Custom-RR` on raw.githubusercontent.com over TLS, with no signature or pinned
  digest, and has no schema version. Whoever controls that repository decides what the
  "Builds for this device" card lists and which download pages it links. It cannot skip
  `FLASH RECOVERY` or choose a write target. A TWRP/PitchBlack row can only open
  Custom Recovery; official files are looked up from the probed phone and must still
  pass the installer host allow-list. Tapping Download tells GitHub this phone's IP address.
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
