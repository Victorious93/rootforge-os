<p align="center">
  <img src="assets/logo/rootforge-os-logo.jpg" alt="RootForge OS logo" width="240">
</p>

# RootForge OS

**A Debian 12–based live/install distro and Termux rootfs toolkit for Android root-module development (Magisk, KernelSU, APatch), boot-image work, and device automation.**

**Victorious Framework — Origin Source Labs**

[![Base](https://img.shields.io/badge/base-Debian%2012%20(Bookworm)-A81D33)](https://www.debian.org/)
[![Build](https://img.shields.io/badge/build-live--build-informational)](BUILD.md)
[![CLI](https://img.shields.io/badge/CLI-rootforge-black)](#unified-cli)
![Status](https://img.shields.io/badge/status-active%20·%20ISO%20unverified%20here-orange)

> **Honesty first:** this repository contains a full Debian `live-build` tree, automation scripts, tests, and Termux/PRoot packaging. **ISO production, booting, Calamares install, and on-phone rootfs use have not been independently verified in the environment that last updated these docs** (no loop device / no published GitHub Release assets as of 2026-10-07). Tags `v0.1.0` / `v0.1.1` exist; no published Release yet. See [BUILD.md](BUILD.md) and [docs/PLATFORM_SUPPORT.md](docs/PLATFORM_SUPPORT.md). Artifacts carry a `Victorious Framework` footer; **nothing is cryptographically signed** — see [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md).

## Overview

RootForge OS targets people building and testing Android root modules and boot images:

1. **Host ISO (x86_64 primary)** — Debian live image with GNOME + Calamares, Android SDK/NDK tooling (mostly first-boot), kernel/boot-image toolchain (magiskboot, avbtool, cross compilers), Magisk/KernelSU-oriented scripts, rooted/unrooted AVD helpers, OTA extraction, backups, and a unified `rootforge` CLI.
2. **On-device Debian rootfs (Termux)** — PRoot (unrooted) and chroot (rooted) flavours, optional XFCE via Termux:X11 — same project scripts where the Android kernel allows.
3. **Host-phone Android app** — `android-app/` is a Material 3 toolkit APK that runs on one phone and controls a **target** phone over a USB-C cable (USB Host API, pure-Kotlin ADB/fastboot/Sahara). Not a Termux fork. Compiles and has hermetic protocol tests; **not run on a real host/target pair.** See [docs/ANDROID_APP.md](docs/ANDROID_APP.md).
4. **Linux / Windows desktop** — same GUI (`android-app/desktop`), talking to host `adb`/`fastboot` (optional `edl`). Shared Kotlin core with the APK. **Not run on a real PC/phone pair.** See [docs/DESKTOP.md](docs/DESKTOP.md).
5. **Docker toolkit image** — CLI + adb/fastboot + headless API (`docker/`). USB is a run-time passthrough (`--device /dev/bus/usb`, `--privileged`). See [docker/README.md](docker/README.md).

It does **not** claim a single unlock/root script for every OEM. Unlock/flash flows detect vendor constraints (Samsung/Xiaomi/etc.) and refuse to guess. Live ISO boot is **BIOS/isolinux only**; UEFI/Secure Boot are unsupported/unverified.

## Features

Grounded in `config/`, `termux/`, `android-app/`, and the `rootforge` CLI (see also section-level detail in the long-form docs under `docs/`):

- **Live + installable Debian** — GNOME session, Calamares (erase / alongside / manual), system manifest for build provenance
- **Unified CLI** — `rootforge` groups: `doctor`, `devices`, `device`, `config`, `flash`, `backup`, `module`, `boot`, `ota`, `avd`
- **Module workflow** — Magisk/KernelSU(+APatch) scaffolds, package/push/install helpers, linting, Zygisk header templates
- **Boot & flash safety** — `device check` go/no-go, typed `FLASH <serial>` confirmation, slot-aware fastboot, audit logs + secret redaction
- **Backups** — partition backup/restore with `manifest.json`, verify/import-legacy paths
- **OTA / firmware** — extract/inspect helpers (payload-dumper-go pinned in image hooks)
- **AVD helpers** — create/list/start rooted or unrooted profiles (x86_64 host + KVM; not available on phone arm64)
- **AI tooling hooks** — Node 22, Claude Code (pinned), Ollama installer (unpinned — see BUILD.md), `brain` second-brain CLI
- **Termux delivery** — `termux/build-rootfs.sh` for proot/chroot (± X11); digest-required installers generated per release
- **Host-phone toolkit APK** — ADB, fastboot, EDL, Magisk/KernelSU/APatch patching workflow, module zip builder, recovery install, logs/terminal; typed confirmation on writes; droidcommand-ai bridge ([docs/DROIDCOMMAND_INTEGRATION.md](docs/DROIDCOMMAND_INTEGRATION.md))
- **Linux/Windows desktop GUI** — same screens; host platform-tools; CI uploads `.deb` / `.exe` (and `.msi` / AppImage when the packager produces them)
- **Docker toolkit** — `rootforge` CLI + API on `:8787`; USB passthrough documented, not assumed
- **CI & tests** — lint/unit/static hook checks; `android-apk.yml` builds the debug APK; `toolkit.yml` packages desktop + Docker; `release.yml` builds ISO + rootfs assets into a **draft** release after verification

## Screenshots

| Live GNOME desktop | Calamares installer | `rootforge doctor` | Module scaffold tree | Termux PRoot login | XFCE on Termux:X11 |
|---|---|---|---|---|---|
| *TODO* | *TODO* | *TODO* | *TODO* | *TODO* | *TODO* |

Capture only after a real boot/install; do not mock device serials or secrets in audit log screenshots.

Paparazzi screenshots of the host-phone app (not a real phone): `android-app/screenshots/`.

## Requirements

### Build host (ISO)

- Debian 12 or Ubuntu 22.04+
- Packages: `live-build`, `debootstrap`, `squashfs-tools`, `xorriso`, `isolinux`, `syslinux-utils`
- Root, free loop device, **≥ 20 GB** disk, **≥ 4 GB** RAM (16 GB+ recommended if you will also run emulators + kernel builds)
- Details: [BUILD.md](BUILD.md)

### Runtime (installed system)

- x86_64 machine (arm64 secondary profile has emulator/KVM caveats)
- For Android emulator acceleration: `/dev/kvm`, user in `kvm` group

### Termux rootfs

- Termux on device; `proot-distro` for unrooted flavour; root/`su` for chroot flavour
- Multi-GB free storage; optional Termux:X11 app for desktop

### Host-phone Android app

- A phone with **USB Host / OTG** hardware (the APK declares `android.hardware.usb.host` as required)
- USB-C to USB-C **data** cable (charge-only cables enumerate nothing)
- Android SDK (or CI) to build: JDK 17+, `ANDROID_HOME`, then `cd android-app && ./gradlew :app:assembleDebug`

### Linux / Windows desktop

- JDK 17 to build; a packaged `.deb` / `.exe` to run
- Host `adb`/`fastboot` on `PATH` or `ANDROID_HOME/platform-tools`
- **Windows USB drivers** (not bundled): [Google USB Driver](https://developer.android.com/studio/run/win-usb) for adb/fastboot; Qualcomm HS-USB QDLoader 9008 for EDL (`VID_05C6&PID_9008`). Details: [docs/DESKTOP.md](docs/DESKTOP.md)

### Docker toolkit

- Docker Engine on Linux for USB passthrough (`--device /dev/bus/usb`, usually also `--privileged`)
- `ROOTFORGE_API_TOKEN`; publish `127.0.0.1:8787` only. [docker/README.md](docker/README.md)

## Build

```bash
git clone https://github.com/Victorious93/rootforge-os.git
cd rootforge-os

# Static checks (no root)
./tests/run-tests.sh
./tests/lint.sh

# ISO (as root on a suitable host)
sudo make build
# → rootforge-os-amd64.hybrid.iso (+ .sha256 when using make)
```

Termux rootfs examples:

```bash
sudo termux/build-rootfs.sh arm64
sudo termux/build-rootfs.sh arm64 --flavor chroot --with-x11
```

Host-phone APK:

```bash
cd android-app
./gradlew :app:assembleDebug :app:testDebugUnitTest
# → app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk   # on the host phone
```

CI: `.github/workflows/android-apk.yml` uploads the debug APK (and a release APK if signing secrets are set). `.github/workflows/toolkit.yml` uploads Linux `.deb`, Windows `.exe`, and a `docker save` of the toolkit image.

Linux / Windows desktop:

```bash
cd android-app
./gradlew :desktop:run
./gradlew :desktop:packageDeb          # Linux
# On Windows: gradlew :desktop:packageExe
```

Docker:

```bash
docker build -f docker/Dockerfile -t rootforge-os-toolkit .
docker run --rm -it --privileged --device /dev/bus/usb \
  -v /dev/bus/usb:/dev/bus/usb -p 127.0.0.1:8787:8787 \
  -e ROOTFORGE_API_TOKEN=… rootforge-os-toolkit
```

Release automation: `.github/workflows/release.yml` (draft release + checksum verification). **Has not been run successfully on GitHub’s infrastructure for a published Release yet.**

## Usage (installed or live)

```bash
rootforge doctor              # environment checks
rootforge devices             # adb/fastboot inventory
rootforge device check …      # go/no-go before writes
rootforge module scaffold …   # Magisk/KernelSU templates
rootforge flash boot …        # flash already-patched boot/init_boot (typed confirm)
rootforge backup create …     # partition backup with manifest
rootforge ota extract …       # payload extraction
rootforge avd create …        # rooted/unrooted AVD helpers (x86_64 host)
```

Flash/unlock/backup scripts refuse unsafe vendors/states where possible; they wipe or write only after explicit confirmation. Logs under `${ROOTFORGE_HOME:-$HOME/rootforge}/logs/` are created mode `0600` and redacted on exit (pattern-based — not a reason to print secrets).

USB flash helper (when you have an ISO + checksum): `make list-usb` / `sudo make flash USB=/dev/sdX`.

### Host phone → target phone over USB-C

Install the APK on the **host**. Plug the **target** in with a data cable. On the target enable USB debugging (or reboot to bootloader / EDL). Accept the host's USB-permission prompt, then Dashboard → Connect. On first ADB use, accept the RSA key dialog on the **target** (the fingerprint is under Settings). If the host does not switch into USB-host mode, put a USB-C OTG adapter on the **host** end. Destructive GUI actions print a plan and require a typed phrase.

**droidcommand-ai:** the app is an independent node, not a companion. Pair it under Settings → External control. The JSON envelope matches `core-rootforge`; the Android transport is a bound Messenger service. Canonical spec: [docs/DROIDCOMMAND_INTEGRATION.md](docs/DROIDCOMMAND_INTEGRATION.md).

## Project structure

```text
auto/                 live-build entry (config, build)
config/               package lists, hooks, includes.chroot (scripts under usr/local/bin)
termux/               PRoot/chroot rootfs build + release metadata templates
android-app/          Toolkit: :core (shared), :app (Android APK), :desktop (Linux/Windows)
docker/               Toolkit image (CLI + headless API) + USB passthrough notes
tests/                Hermetic tests + stubs (adb/fastboot/su/…)
docs/                 ARCHITECTURE, PLATFORM_SUPPORT, SECURITY_MODEL, ANDROID_APP, …
Makefile              build, checksum, flash, clean targets
BUILD.md              ISO build guide
HACKING.md · AGENTS.md · CLAUDE.md
```

Deep design notes that used to dominate the homepage README live in `docs/` (including the pre-refresh long-form [docs/README-legacy.md](docs/README-legacy.md)) and [BUILD.md](BUILD.md). Prefer updating those for long rationale; keep this README as the landing page.

## Contributing

1. Run `./tests/run-tests.sh` and hook checks before PRs.
2. Preserve fail-closed device gates and typed confirmations on destructive paths.
3. Pin and verify downloaded artifacts in hooks (`tests/check-hooks.sh` enforces several rules).
4. Do not claim hardware verification you did not perform — use the Certain/Likely/Guessing language from existing docs when needed.
5. See [HACKING.md](HACKING.md) and [AGENTS.md](AGENTS.md).

## License

**No `LICENSE` file is present in this repository.** Add an SPDX license (and decide how Debian package licenses / pinned third-party binaries should be attributed) before advertising the project as open source. Until then, redistribution terms are undefined.

Third-party components fetched at build time (NodeSource, Ollama installer, Magisk APK for magiskboot, GitHub release binaries, etc.) remain under their own licenses — see hooks under `config/hooks/` and [BUILD.md](BUILD.md).

## Documentation map

| Doc | Purpose |
|---|---|
| [BUILD.md](BUILD.md) | How to build the ISO; what is pinned vs not |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture |
| [docs/PLATFORM_SUPPORT.md](docs/PLATFORM_SUPPORT.md) | BIOS-only live ISO; arch caveats |
| [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md) | Trust boundaries, logging, flashing gates |
| [docs/ANDROID_APP.md](docs/ANDROID_APP.md) | Host-phone APK: USB Host, screens, limits |
| [docs/DESKTOP.md](docs/DESKTOP.md) | Linux/Windows desktop, Windows USB drivers, Docker |
| [docker/README.md](docker/README.md) | Toolkit image and USB passthrough |
| [docs/DROIDCOMMAND_INTEGRATION.md](docs/DROIDCOMMAND_INTEGRATION.md) | Bridge protocol for droidcommand-ai |
| [docs/REVIEW.md](docs/REVIEW.md) | Code review of this branch |
| [CHANGELOG.md](CHANGELOG.md) | Unreleased changes (no published Release yet) |
| [tests/README.md](tests/README.md) | Test and release-verifier notes |
| [docs/README-legacy.md](docs/README-legacy.md) | Pre-refresh long-form README (archived) |

## Acknowledgements

Debian live-build, Magisk, KernelSU, AOSP tooling, Termux / proot-distro, and the many pinned upstream projects pulled by image hooks. RootForge OS is part of the Victorious Framework alongside related repos (e.g. droidcommand-AI’s `core-rootforge` client — passive reads only today).
