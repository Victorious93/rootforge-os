<p align="center">
  <img src="assets/logo/rootforge-os-logo.jpg" alt="RootForge OS logo" width="240">
</p>

<h1 align="center">RootForge OS</h1>

<p align="center">
  <strong>A Debian 12 live/install distro, Termux rootfs and USB toolkit for Android root-module development, boot-image work and device automation.</strong>
</p>

<p align="center">
  <a href="https://github.com/Victorious93/rootforge-os/actions/workflows/lint.yml"><img alt="Lint" src="https://github.com/Victorious93/rootforge-os/actions/workflows/lint.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0-or-later" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg"></a>
  <a href="android-app/core/LICENSE"><img alt="License: Apache-2.0 (core)" src="https://img.shields.io/badge/core-Apache--2.0-blue.svg"></a>
  <img alt="Base: Debian 12" src="https://img.shields.io/badge/base-Debian%2012%20(Bookworm)-A81D33">
  <img alt="Languages: Kotlin, Shell, Python" src="https://img.shields.io/badge/code-Kotlin%20%C2%B7%20Shell%20%C2%B7%20Python-informational">
  <img alt="Status: ISO unverified" src="https://img.shields.io/badge/status-active%20%C2%B7%20ISO%20unverified-orange">
</p>

> **Status, plainly:** the `live-build` tree, scripts, tests and packaging are all in this repository, but
> **ISO production, ISO boot, Calamares install and on-phone use have not been verified end to end**
> (no loop device in the build environment, and no published GitHub Release yet; tags `v0.1.0` and `v0.1.1` exist).
> The Android and desktop apps compile and have hermetic tests, but have **not been run on a real phone/PC pair**.
> Nothing is cryptographically signed. See [docs/PLATFORM_SUPPORT.md](docs/PLATFORM_SUPPORT.md) and
> [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md).

## Overview

RootForge OS is one set of device-facing rules (the `rootforge` CLI and its scripts) delivered through several front ends:

| Front end | What it is | Where |
|---|---|---|
| **Host ISO** (x86_64) | Debian live/install image with GNOME, Calamares, Android SDK/NDK tooling, boot-image toolchain and the `rootforge` CLI | `config/`, `auto/`, [BUILD.md](BUILD.md) |
| **Termux rootfs** | Debian rootfs for Android, PRoot (no root) or chroot (rooted), optional XFCE via Termux:X11 | `termux/` |
| **Host-phone APK** | Material 3 app: one phone controls a *target* phone over USB-C (USB Host API, pure-Kotlin ADB/fastboot/Sahara). Not a Termux fork | `android-app/app`, [docs/ANDROID_APP.md](docs/ANDROID_APP.md) |
| **Linux / Windows desktop** | Same GUI, talking to host `adb`/`fastboot` (optional `edl`) | `android-app/desktop`, [docs/DESKTOP.md](docs/DESKTOP.md) |
| **Docker toolkit** | `rootforge` CLI + adb/fastboot + headless JSON API | `docker/`, [docker/README.md](docker/README.md) |

It does **not** claim one unlock/root script for every OEM: flows detect vendor constraints and refuse to guess.
The live ISO boots **BIOS/isolinux only**; UEFI and Secure Boot are unsupported.

## Screenshots

Host-phone app, rendered by Paparazzi unit tests with sample data (these are not captures from a real phone):

| Dashboard | Flash Tools | ADB | Fastboot | Settings |
|---|---|---|---|---|
| <img src="android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_dashboard.png" alt="Dashboard screen" width="160"> | <img src="android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_flashTools.png" alt="Flash Tools screen" width="160"> | <img src="android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_adb.png" alt="ADB screen" width="160"> | <img src="android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_fastboot.png" alt="Fastboot screen" width="160"> | <img src="android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_settings.png" alt="Settings screen" width="160"> |

Live ISO, Calamares installer and Termux screenshots coming soon (they need a real boot/install first).

## Key features

- **Unified CLI** - `rootforge` groups: `doctor`, `devices`, `device`, `config`, `flash`, `backup`, `module`, `boot`, `ota`, `avd`
- **Flash safety** - `device check` go/no-go, typed `FLASH <serial>` confirmation, slot-aware fastboot, unknown state blocks writes
- **Backups** - partition backup/restore with `manifest.json`, verify and legacy-import paths
- **Module workflow** - Magisk / KernelSU / APatch scaffolds, package/push/install helpers, linting, Zygisk header templates
- **OTA and boot images** - payload extraction/inspection (payload-dumper-go pinned in image hooks), boot patch and flash helpers
- **AVD helpers** - create/list/start rooted or unrooted emulator profiles (x86_64 host with KVM)
- **Logging** - private (`0600`) JSON-lines logs with secret redaction and an audit trail for state-changing commands
- **Live + installable Debian** - GNOME session, Calamares installer (erase / alongside / manual), build provenance manifest
- **Termux delivery** - `termux/build-rootfs.sh` for PRoot/chroot (with or without X11); digest-required installers generated per release
- **Phone, desktop and Docker clients** - ADB, fastboot, EDL, boot patching, module zip builder, recovery install, logs/terminal; typed confirmation on writes; [droidcommand-ai bridge](docs/DROIDCOMMAND_INTEGRATION.md)
- **Tests and CI** - hermetic shell/Python tests and static hook checks; workflows for lint, Android APK, desktop + Docker, and a draft-release pipeline

## Install

There is **no published release yet**, so there are no download links. Build the piece you need (see [Build from source](#build-from-source)):

- **ISO:** `sudo make build`, then write it to USB with `sudo make flash USB=/dev/sdX` (verifies the checksum if present; `make list-usb` lists candidates).
- **Phone app:** build the debug APK and `adb install -r app/build/outputs/apk/debug/app-debug.apk` on the **host** phone. It requires USB Host hardware.
- **Desktop:** build the `.deb` (Linux) or `.exe` (Windows) with Gradle; CI also uploads these as `toolkit.yml` artifacts.
- **Docker:** build the image locally.
- **Termux rootfs:** build with `termux/build-rootfs.sh`. Install scripts are generated per release by `termux/make-release-metadata.sh`.

### Requirements

| Target | Needs |
|---|---|
| ISO build | Debian 12 or Ubuntu 22.04+; `live-build`, `debootstrap`, `squashfs-tools`, `xorriso`, `isolinux`, `syslinux-utils`; root, a free loop device, 20 GB+ disk, 4 GB+ RAM |
| Installed system | x86_64; `/dev/kvm` and `kvm` group membership for emulator acceleration |
| Termux rootfs | Termux; `proot-distro` (unrooted) or `su` (chroot); several GB free; optional Termux:X11 |
| Phone app | USB Host/OTG phone, USB-C **data** cable; JDK 17+ and Android SDK (`ANDROID_HOME`) to build |
| Desktop | JDK 17 to build; `adb`/`fastboot` on `PATH` or in `ANDROID_HOME/platform-tools`. On Windows, USB drivers are **not** bundled (see [docs/DESKTOP.md](docs/DESKTOP.md)) |
| Docker | Docker Engine on Linux for USB passthrough; `ROOTFORGE_API_TOKEN` |

## Usage

```bash
rootforge doctor              # environment checks
rootforge devices             # adb/fastboot inventory
rootforge device check …      # go/no-go before writes
rootforge module scaffold …   # Magisk/KernelSU templates
rootforge flash boot …        # flash an already-patched boot/init_boot (typed confirm)
rootforge backup create …     # partition backup with manifest
rootforge ota extract …       # payload extraction
rootforge avd create …        # rooted/unrooted AVD helpers (x86_64 host)
```

Flash, unlock and backup scripts refuse unsafe vendors/states where possible and write only after explicit confirmation.
Logs live under `${ROOTFORGE_HOME:-$HOME/rootforge}/logs/`, are created `0600` and redacted on exit (pattern-based, so do not rely on it to hide secrets).

### Host phone to target phone over USB-C

1. Install the APK on the **host** phone and plug the **target** in with a data cable.
2. On the target, enable USB debugging (or reboot to bootloader/EDL) and accept the host's USB-permission prompt.
3. In the app: Dashboard, then Connect. On first ADB use, accept the RSA key dialog on the **target**.
4. If the host does not switch into USB-host mode, put a USB-C OTG adapter on the **host** end.

Destructive actions print a plan and require a typed phrase. To pair with droidcommand-ai, use Settings, then External control; the protocol is in [docs/DROIDCOMMAND_INTEGRATION.md](docs/DROIDCOMMAND_INTEGRATION.md).

### Docker

```bash
export ROOTFORGE_API_TOKEN="$(openssl rand -hex 16)"
docker run --rm -it --privileged --device /dev/bus/usb \
  -v /dev/bus/usb:/dev/bus/usb -p 127.0.0.1:8787:8787 \
  -e ROOTFORGE_API_TOKEN rootforge-os-toolkit
```

Publish the port on `127.0.0.1` only. Details: [docker/README.md](docker/README.md).

## Build from source

```bash
git clone https://github.com/Victorious93/rootforge-os.git
cd rootforge-os

# Static checks and hermetic tests (no root, device or network)
bash tests/run-tests.sh
bash tests/lint.sh            # needs shellcheck
```

**ISO** (Debian `live-build`, run as root on a suitable host):

```bash
sudo make build               # -> rootforge-os-amd64.hybrid.iso
make checksum                 # -> rootforge-os-amd64.hybrid.iso.sha256
```

**Termux rootfs:**

```bash
sudo termux/build-rootfs.sh arm64
sudo termux/build-rootfs.sh arm64 --flavor chroot --with-x11
```

**Phone APK and desktop** (Gradle, JDK 17):

```bash
cd android-app
./gradlew :core:test :app:assembleDebug :app:testDebugUnitTest
# APK: app/build/outputs/apk/debug/app-debug.apk

./gradlew :desktop:run             # desktop GUI
./gradlew :desktop:packageDeb      # Linux .deb
gradlew :desktop:packageExe        # Windows .exe (run on Windows)
```

**Docker:**

```bash
docker build -f docker/Dockerfile -t rootforge-os-toolkit .
```

CI: `.github/workflows/` holds `lint.yml`, `android-apk.yml`, `toolkit.yml` (desktop + Docker) and `release.yml` (draft release with checksum verification; it has not yet produced a published Release).

## Project structure

```text
auto/                 live-build entry (config, build)
config/               package lists, hooks, includes.chroot (scripts under usr/local/bin)
termux/               PRoot/chroot rootfs build + release metadata templates
android-app/          :core (shared), :app (Android APK), :desktop (Linux/Windows)
docker/               Toolkit image (CLI + headless API)
tests/                Hermetic tests + stubs (adb/fastboot/su/…)
docs/                 Architecture, platform support, security model, app docs, …
Makefile · BUILD.md · HACKING.md · AGENTS.md · CLAUDE.md
```

## Documentation

| Doc | Purpose |
|---|---|
| [BUILD.md](BUILD.md) | ISO build guide; what is pinned vs not |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture |
| [docs/PLATFORM_SUPPORT.md](docs/PLATFORM_SUPPORT.md) | BIOS-only live ISO; architecture caveats |
| [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md) | Trust boundaries, logging, flashing gates |
| [docs/ANDROID_APP.md](docs/ANDROID_APP.md) | Host-phone APK: USB Host, screens, limits |
| [docs/DESKTOP.md](docs/DESKTOP.md) | Linux/Windows desktop, Windows USB drivers, Docker |
| [docs/DROIDCOMMAND_INTEGRATION.md](docs/DROIDCOMMAND_INTEGRATION.md) | Bridge protocol for droidcommand-ai |
| [tests/README.md](tests/README.md) | Test and release-verifier notes |
| [CHANGELOG.md](CHANGELOG.md) | Unreleased changes |
| [LICENSE](LICENSE) · [NOTICE](NOTICE) | GPL-3.0-or-later text; split-license and third-party notices |
| [android-app/core/LICENSE](android-app/core/LICENSE) | Apache-2.0 text for shared Kotlin `:core` |
| [docs/README-legacy.md](docs/README-legacy.md) | Archived long-form README |

## Contributing

1. Run `bash tests/run-tests.sh` and `bash tests/lint.sh` before opening a PR.
2. Keep device gates fail-closed and destructive paths behind typed confirmation.
3. Pin and verify downloaded artifacts in hooks (`tests/check-hooks.sh` enforces several rules).
4. Do not claim hardware verification you did not perform.

See [HACKING.md](HACKING.md) and [AGENTS.md](AGENTS.md).

## License

RootForge OS uses a **split license**. Original work in this repository is
**GPL-3.0-or-later**, except the shared Kotlin module **`android-app/core`
(`:core`)**, which is **Apache License 2.0**.

| Tree | License | Text |
|---|---|---|
| `android-app/core/` (`:core`) | Apache-2.0 | [android-app/core/LICENSE](android-app/core/LICENSE) |
| Everything else (CLI, scripts, ISO/live-build, Termux packaging, `:app`, `:desktop`, Docker toolkit, tests, docs) | GPL-3.0-or-later | [LICENSE](LICENSE) |

Copyright, the GPL applying notice (“version 3 or later”), and third-party
attribution are in [NOTICE](NOTICE). Apache-2.0 is compatible with GPL-3.0:
combining `:core` into the GPL-licensed Android app or desktop client does
**not** relicense the `:core` sources; those stay Apache-2.0 so they can be
reused on their own terms. Combined binaries are GPL-3.0-or-later.

**Third-party code is not relicensed.** Debian packages in the ISO and rootfs,
Termux / PRoot / proot-distro, Magisk / KernelSU / APatch, the Android SDK/NDK,
Node.js, Ollama, Claude Code, Calamares, and other build-time downloads keep
their upstream licenses. This repository does not vendor those projects; it
ships RootForge installers, plugins, branding, and pinned fetchers. See
[BUILD.md](BUILD.md) and `config/hooks/`.

## Credits

Built on Debian live-build, Magisk, KernelSU, APatch, AOSP tooling, Termux and proot-distro, plus the pinned upstream projects pulled in by the image hooks. RootForge OS is not a fork of any of them.
