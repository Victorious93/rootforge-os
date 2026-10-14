# RootForge OS — codebase review

**Date:** 2026-10-14 · **Scope:** `main` at `cec7f7e` plus the changes on this branch. Claims tagged **[Certain]** were verified by reading the code and running hermetic tests. **[Likely]** is inferred. **[Guessing]** is not verified.

## What it is

RootForge OS is a Debian 12 live-build distro plus a Termux/PRoot (and rooted-chroot) rootfs, aimed at people who write Magisk / KernelSU / APatch modules and patch Android boot images. The product is a **CLI and a set of scripts**, not a desktop GUI in the ISO: `rootforge` wraps device checks, flashing, backup/restore, module scaffolding, boot unpack/repack, OTA extract, and a rooted AVD helper. Destructive writes use one explicit target, a printed plan, a typed confirmation on `/dev/tty`, and a re-check immediately before the write.

This branch adds a **host-phone Android app** (`android-app/`) that talks to a **target phone** over USB-C using Android's USB Host API and a pure-Kotlin ADB / fastboot / Sahara implementation. droidcommand-ai can drive it over the same JSON bridge envelope `core-rootforge` already uses, via a bound Messenger service instead of SSH.

## Layout

| Path | Role |
|---|---|
| `config/includes.chroot/usr/local/lib/rootforge/core/` | Python CLI (`rootforge`) |
| `config/includes.chroot/usr/local/bin/` | Device-facing Bash scripts |
| `config/includes.chroot/usr/local/lib/rootforge/sh/common.sh` | Shared helpers (confirm, downloads, serials) |
| `config/hooks/` | live-build chroot hooks (pinned downloads, with exceptions) |
| `termux/` | PRoot/chroot rootfs build + install metadata |
| `tests/` | Hermetic shell + Python tests, no device/network |
| `.github/workflows/` | `lint.yml` (PR), `release.yml` (tags), `android-apk.yml` |
| `android-app/` | Kotlin / Compose host-phone toolkit APK |
| `docs/` | Architecture, security model, this review |

## What works **[Certain]**

Hermetic suite after the bugfixes on this branch: `bash tests/run-tests.sh` → **1009 shell + 367 Python, 0 failed**. `bash tests/lint.sh` clean with shellcheck 0.9.0. GitHub `Lint` on `main` was green at `cec7f7e`.

The flash / backup / restore / unlock scripts are the strongest part of the repo: argument validation, vendor refusal (Samsung), slot handling, `ANDROID!` header check, exit codes that distinguish "blocked" from "written but boot unverified". The Python CLI is a thin argv-safe wrapper around those scripts, with real work of its own in `backup` verify, `boot` unpack/repack/cpio, `ota` extract (leftover images no longer count as this run), device profiling, audit JSONL, and log redaction.

The Termux install path generates `install.sh` and the proot-distro plugin **from the real artifacts** at release time (`termux/make-release-metadata.sh` + `tests/verify-release-assets.sh`). There is no checked-in copy with placeholder digests.

The Android app **compiles** (`assembleDebug`), and its protocol / planner / module-builder / bridge tests run without a device. Paparazzi snapshots of Dashboard, ADB, Fastboot, Modules, Settings are in `android-app/screenshots/`. **Not run on a phone or emulator.**

## What does not work / has not been run **[Certain]**

- `lb build` / `sudo make build` — no loop-device ISO in this environment; GitHub `release.yml` has succeeded in the past on `workflow_dispatch`, but there is **no published GitHub Release**.
- Calamares install, systemd first boot, QEMU boot of the ISO.
- Any real phone, fastboot, Magisk/KernelSU patch, or EDL device.
- The Android app's USB path: unit-tested against in-memory pipes, not against `UsbManager`.
- UEFI/Secure Boot for the live ISO (BIOS/isolinux only).
- Windows / APK (before this branch) / remote admin — previously listed as not started.

## Code quality

The shell and Python that sit on a destructive path are unusually careful: comments record the bug they close, tests are named after that bug, and `tests/check-tests.sh` refuses a section that never invokes the code it names. Idiom is "fail closed": unknown vendor, unknown slot, unknown lock state all block a write. Config cannot disable a safety check.

Weaker areas: fleet orchestration (until this branch, a serial listed in both adb and fastboot was run twice), network installers (`join_headscale.sh` still pipes `curl` to `sh`), and several chroot hooks that **exit 0 on a failed download** so a "green" ISO can lack magiskboot / avbtool / payload-dumper. The second-brain CLI (`brain.py`) is a separate, lightly tested sqlite + Ollama tool.

The Android app follows the same fail-closed rules in `FlashPlanner` and `BridgeExecutor`. UI is Material 3 / Compose, dark by default, with a typed-phrase dialog for writes. The ADB stack is classic `shell:` (exit status recovered with an echo marker), not shell_v2.

## Security

**Good**

- Destructive device ops: one target, printed plan, typed confirmation, re-check.
- Downloads that go through `rf_download_cached` / hook `record_verified` pin a digest.
- CLI audit trail for state-changing commands; script logs redacted at exit (pattern-based).
- Live ISO `NOPASSWD: ALL` is removed by Calamares (`removeuser` + sudoers cleanup) — covered by tests.
- The Android bridge is off until the owner enables it, identifies the caller by package **and** signing certificate (from the kernel UID, not from JSON), and still prompts on every control/destructive call. RootForge is **not** a droidcommand-ai companion: it has no `DependencyGuard`.

**Concerns**

| Sev | Issue |
|---|---|
| High | `config/hooks/0020-ollama.hook.chroot` downloads and runs `https://ollama.com/install.sh` with no pin. Same class of bug as the 2026-10 merge that restored hooks which defined SHA-256 pins and never compared them. |
| High | `join_headscale.sh` still `curl \| sh` from tailscale.com. |
| Med | Several hooks treat a failed fetch as non-fatal (`exit 0`). |
| Med | Live udev `MODE=0666` on Android USB nodes — any local user on the live session can talk to a plugged-in phone. Intentional for a lab ISO; wrong if it survived install (Calamares removes the live sudoers; udev rules are a different file). |
| Med | Android EDL/Firehose is experimental and untested on hardware. A wrong programmer is a brick. The UI says so; the code still lets you send one. |
| Low | Log redaction is pattern-based; SIGKILL and unrecognised secret shapes bypass it (already documented). |

Nothing is cryptographically signed (ISO, APK, modules). README previously said "Signed artifacts"; that line now matches `docs/SECURITY_MODEL.md`.

## Gaps

1. No hardware or ISO-boot validation — the implementation plan's Stage 5 is still blocked on infrastructure.
2. Ollama / NodeSource / Claude Code npm are unpinned.
3. No release signing, no published `v*` release with installable Termux tarballs.
4. Scaffolded Magisk `update-binary` was a no-op (called `grep_prop` before sourcing `util_functions.sh`, never called `install_module`). Fixed on this branch; still untested in recovery.
5. AVD `cmd_stop` used to report success when `emu kill` failed. Fixed; the CLI `rootforge avd stop` still goes through the shell script, not that function.
6. Desktop (Linux/Windows) and Docker toolkit images were out of scope of the original ISO; they are being added after the APK.

## Prioritized recommendations

1. **Pin or replace the Ollama installer** and stop `curl|sh` in `join_headscale.sh`. `tests/check-hooks.sh` already rejects unused pins; extend it so a hook that `curl`s without `record_verified` fails the build, with an explicit allow-list if something must stay unpinned.
2. **Fail the ISO build** when magiskboot / avbtool / payload-dumper cannot be fetched. A missing tool at runtime is worse than a red hook.
3. **Run Stage 5** on a host with loop devices: `sudo make build`, QEMU boot, scripted Calamares. Until that happens, "hardened distro" is a claim about scripts, not about an image.
4. **Flash/backup contract on a device the owner agrees to brick-risk.** Stub tests cannot catch a vendor `getvar` dialect.
5. **Keep the Android app's USB path honest:** protocol tests against a fake are not a substitute for one Pixel in fastboot over OTG. EDL should stay labelled experimental until a Sahara HELLO is captured from a real 9008 device.
6. Do not start a Windows/APK/GUI **reimplementation** of the flash contract; desktop and Docker should call the same planner and the same confirmation rules.

## Fixes on this branch (verified in hermetic tests)

- `boot cpio` refuses a ramdisk path that escapes the unpack directory.
- Module display names and `brain new` titles cannot inject `module.prop` / YAML keys via newlines.
- Adb+fastboot serial lists are deduplicated in `devices.py` and `fleet_orchestrate.sh`.
- A truncated cached ADBKeyboard APK is discarded instead of installed.
- Fleet treats `flash_patched_boot.sh` exit 4 as "written, boot unverified", not FAIL.
- Live-groups systemd unit now `usermod`s `rootforge`, not `user`.
- Chroot rootfs hostname/motd no longer claim to be unrooted PRoot.
- Scaffolded `update-binary` sources Magisk's `util_functions.sh` and calls `install_module`.
- Desktop launcher test no longer depends on the host lacking `xfce4-session`.
