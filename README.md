<p align="center">
  <img src="assets/logo/rootforge-os-logo.jpg" alt="RootForge OS logo" width="240">
</p>

<h1 align="center">RootForge OS</h1>

<p align="center">
  <strong>A Debian 12 live/install distro, Termux CLI, and USB toolkit for Android root-module development, boot-image work and device automation.</strong>
</p>

<p align="center">
  <a href="https://github.com/Victorious93/rootforge-os/actions/workflows/lint.yml"><img alt="Lint" src="https://github.com/Victorious93/rootforge-os/actions/workflows/lint.yml/badge.svg"></a>
  <a href="https://github.com/Victorious93/rootforge-os/actions/workflows/android-apk.yml"><img alt="Android APK" src="https://github.com/Victorious93/rootforge-os/actions/workflows/android-apk.yml/badge.svg"></a>
  <a href="https://github.com/Victorious93/rootforge-os/actions/workflows/toolkit.yml"><img alt="Toolkit packages" src="https://github.com/Victorious93/rootforge-os/actions/workflows/toolkit.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: GPL-3.0-or-later" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg"></a>
  <a href="android-app/core/LICENSE"><img alt="License: Apache-2.0 (core)" src="https://img.shields.io/badge/core-Apache--2.0-blue.svg"></a>
  <img alt="Base: Debian 12" src="https://img.shields.io/badge/base-Debian%2012%20(Bookworm)-A81D33">
  <img alt="Languages: Kotlin, Shell, Python" src="https://img.shields.io/badge/code-Kotlin%20%C2%B7%20Shell%20%C2%B7%20Python-informational">
  <img alt="Status: ISO unverified" src="https://img.shields.io/badge/status-active%20%C2%B7%20ISO%20unverified-orange">
</p>

> **Status, plainly:** the `live-build` tree, scripts, tests and packaging are all in this repository, but
> **ISO production, ISO boot, Calamares install and on-phone use have not been verified end to end**
> (no loop device in the build environment; v0.2.0 publishes a debug APK only — no ISO/rootfs).
> The Android and desktop apps compile and have hermetic tests, but have **not been run on a real phone/PC pair**.
> Nothing is cryptographically signed. See [docs/PLATFORM_SUPPORT.md](docs/PLATFORM_SUPPORT.md) and
> [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md).

## Overview

RootForge OS is one set of device-facing rules (the `rootforge` CLI and its scripts) delivered through several front ends:

| Front end | What it is | Where |
|---|---|---|
| **Host ISO** (x86_64) | Debian live/install image with GNOME, Calamares, Android SDK/NDK tooling, boot-image toolchain and the `rootforge` CLI | `config/`, `auto/`, [BUILD.md](BUILD.md) |
| **Termux CLI** | Same Python CLI from a git clone (no Debian rootfs required). A full PRoot/chroot Debian rootfs is a separate, release-generated artifact | clone path below; `termux/` |
| **Host-phone APK** | Material 3 app: one phone controls a *target* phone over USB OTG (USB Host API, pure-Kotlin ADB/fastboot/Sahara). Flash Tools is the guided hub. Not a Termux fork | `android-app/app`, [docs/ANDROID_APP.md](docs/ANDROID_APP.md) |
| **Linux / Windows desktop** | Same Compose screens, talking to host `adb`/`fastboot` (optional `edl`) | `android-app/desktop`, [docs/DESKTOP.md](docs/DESKTOP.md) |
| **Docker toolkit** | `rootforge` CLI + adb/fastboot + headless JSON API | `docker/`, [docker/README.md](docker/README.md) |

It does **not** claim one unlock/root script for every OEM: flows detect vendor constraints and refuse to guess.
The live ISO boots **BIOS/isolinux only**; UEFI and Secure Boot are unsupported.

### Victorious Framework

This repository is **RootForge OS**, part of the Victorious Framework (Origin Source Labs). Shared Kotlin
rules live in `android-app/core` (`:core`) under **Apache-2.0** so other Victorious clients — including
**VictorSuite** — can reuse the flash planner, device-match gate, approvals and DroidCommand envelope
without taking the GPL ISO/app tree. Per [docs/DROIDCOMMAND_INTEGRATION.md](docs/DROIDCOMMAND_INTEGRATION.md),
Termux-native workflows stay with VictorSuite; Sahara / Firehose stay with
EDL-Commander (JNI into that crate is plan-only; see
[docs/FLASH_TOOLS_PLAN.md](docs/FLASH_TOOLS_PLAN.md) Phase 4). This repo's Termux path is the CLI
checkout plus an optional Debian-in-Termux rootfs, not a second copy of those projects.

## Screenshots

Host-phone app, rendered by Paparazzi unit tests with sample data (these are not captures from a real phone):

| Dashboard | Flash Tools | ADB | Fastboot | Settings |
|---|---|---|---|---|
| <img src="android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_dashboard.png" alt="Dashboard screen" width="160"> | <img src="android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_flashTools.png" alt="Flash Tools screen" width="160"> | <img src="android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_adb.png" alt="ADB screen" width="160"> | <img src="android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_fastboot.png" alt="Fastboot screen" width="160"> | <img src="android-app/screenshots/ai.rootforge.os.ui_ScreenshotTest_settings.png" alt="Settings screen" width="160"> |

Live ISO, Calamares installer and Termux screenshots coming soon (they need a real boot/install first).

## Flash Tools

The **Flash Tools** drawer on the host-phone APK (and the same Compose tree on desktop) is a guided
hub: one phone drives a target over **USB OTG**. Buttons call the existing `TargetController` /
desktop `HostController` — there is no second USB stack and no bundled `adb` binary. **Not run on a
real host/target pair.**

| Pane | What it does |
|---|---|
| **Guide** | Cable, OTG adapter, USB debugging, bootloader key combo, first RSA prompt |
| **Scan** | USB Host refresh; ADB / fastboot / EDL targets vs ignored HID/charge-only devices; grant permission and connect |
| **ADB** | Device check (product / slot / fingerprint), shell, packages, push/pull, logcat, screen info, screenshot |
| **Fastboot** | Product and partition info, unlock (typed `UNLOCK`), flash a library image (match gate then typed `FLASH <partition>`), reboot |
| **Inspect** | Image / APK kind, size, SHA-256, sidecar claim, typed-hex verify. Does not talk to the target |
| **Log** | Same redacted `OutputLog` as the output panel; persisted (`command-log.jsonl` in the app files dir; desktop: `~/.rootforge/command-log.jsonl`) |

A flash is **refused** unless a sidecar `<image>.manifest.json` matches the target (product/codename,
slot, and fingerprint *or* a declared product/slot claim). A sidecar SHA-256 is checked when present.
Mismatches are logged as `Refused: …` and nothing is written. There is no in-app override. The same
gate runs on the dedicated Fastboot / Recovery / Root screens and on desktop `HostController.fastbootFlash`.

Unlock still shows a large on-screen warning (factory reset, Knox / Widevine / banking, warranty) and
requires the typed phrase. Discovery (a USB device appeared) is not authorization.

**EDL** on the dedicated EDL screen is an experimental Kotlin Sahara / Firehose client. This app ships
no OEM programmer. A later JNI path that reuses EDL-Commander is **plan only**
([docs/FLASH_TOOLS_PLAN.md](docs/FLASH_TOOLS_PLAN.md) Phase 4); it is not implemented.

## DroidCommand Messenger

RootForge is an **independent node**, not a droidcommand-ai companion. Removing DCA does not disable
any RootForge feature. The phone app exports a bound Messenger service
(`ai.rootforge.os.action.BRIDGE`; debug package `ai.rootforge.os.debug`). Pairing is
**Settings → External control**:

1. Copy the **Node id**.
2. Let DCA bind the action and send `rootforge.capabilities.get`. The first reply is `not_paired` or
   `bridge_disabled`; that is expected. The caller is recorded as pending even if the switch is off.
3. Turn **Allow external controllers** on (default **off**), then **Pair** the pending package +
   signing-certificate hash.

Reads then run. Control operations prompt on the host phone. Destructive operations use the same
typed phrase as the GUI (`FLASH boot`, `UNLOCK`, …). EDL, sideload of a zip, and raw `fastboot oem`
are GUI-only (`unknown_operation` on the bridge). Protocol: [docs/DROIDCOMMAND_INTEGRATION.md](docs/DROIDCOMMAND_INTEGRATION.md).
**Not bound from a live DCA.**

Desktop / Docker reuse the same JSON envelope over HTTP (`docs/DESKTOP.md`); that is not the Messenger path.

## Key features

- **Unified CLI** - `rootforge` groups: `doctor`, `devices`, `device`, `config`, `flash`, `backup`, `module`, `boot`, `ota`, `avd`
- **Flash safety (CLI)** - `device check` go/no-go, typed `FLASH <serial>` confirmation, slot-aware fastboot, unknown state blocks writes
- **Flash safety (app / desktop)** - sidecar device-match gate, SHA-256 inspect/verify, typed `FLASH <partition>` / `UNLOCK`, no skip setting
- **Backups** - partition backup/restore with `manifest.json`, verify and legacy-import paths
- **Module workflow** - Magisk / KernelSU / APatch scaffolds, package/push/install helpers, linting, Zygisk header templates
- **OTA and boot images** - payload extraction/inspection (payload-dumper-go pinned in image hooks), boot patch and flash helpers
- **AVD helpers** - create/list/start rooted or unrooted emulator profiles (x86_64 host with KVM)
- **Logging** - private (`0600`) JSON-lines logs with secret redaction and an audit trail for state-changing commands
- **Live + installable Debian** - GNOME session, Calamares installer (erase / alongside / manual), build provenance manifest
- **Termux delivery** - git-clone CLI now; `termux/build-rootfs.sh` for a PRoot/chroot Debian rootfs when you have a tarball; digest-required installers generated per release
- **Phone, desktop and Docker clients** - ADB and fastboot on every client; experimental in-app EDL; optional host `edl` on desktop; typed confirmation on writes
- **Tests and CI** - hermetic shell/Python tests and static hook checks; workflows for lint, Android APK, desktop + Docker, and a draft-release pipeline

## Install

**v0.2.0** is the first published GitHub Release. The host-phone APK is a **debug**
build (`ai.rootforge.os.debug`, debug-keystore signed — not release-signed):

- Release: https://github.com/Victorious93/rootforge-os/releases/tag/v0.2.0
- APK: https://github.com/Victorious93/rootforge-os/releases/download/v0.2.0/rootforge-os-android-debug.apk

There is still no prebuilt ISO or Termux rootfs. Use a git clone for the CLI, or
build the piece you need (see [Build from source](#build-from-source)).

### CLI from a git clone (Linux or Termux)

The CLI is designed to run from a working tree: `runner.py` finds `usr/local/bin` scripts next to the
Python package. The installed `rootforge` shim hardcodes `PYTHONPATH=/usr/local/lib` and is for the
ISO / rootfs, not a checkout.

```bash
git clone https://github.com/Victorious93/rootforge-os.git
cd rootforge-os
export PYTHONPATH="$PWD/config/includes.chroot/usr/local/lib"
python3 -m rootforge.core.cli doctor
```

Device work also needs `adb`, `fastboot`, `jq`, `curl`, `unzip`, `zip` and `sha256sum` on `PATH`.
PyYAML (`python3-yaml`) is optional except for `rootforge config`. `rootforge doctor` reports what
is missing. This is the Termux CLI path today; it is **not** the Debian-in-Termux rootfs and has
**not been run on a phone**.

### Phone APK, desktop, Docker, ISO, Termux rootfs

- **Phone app:** download the v0.2.0 debug APK from the [GitHub Release](https://github.com/Victorious93/rootforge-os/releases/download/v0.2.0/rootforge-os-android-debug.apk) and `adb install -r` it on the **host** phone, or build with Gradle (`app/build/outputs/apk/debug/app-debug.apk`). It requires USB Host hardware (`android.hardware.usb.host`). Debug applicationId is `ai.rootforge.os.debug`. Not release-signed.
- **Desktop:** build the `.deb` / AppImage (Linux) or `.exe` (Windows) with Gradle; CI uploads these as `toolkit.yml` artifacts. Windows USB drivers are **not** bundled.
- **Docker:** build the image locally.
- **ISO:** `sudo make build`, then write it to USB with `sudo make flash USB=/dev/sdX` (verifies the checksum if present; `make list-usb` lists candidates). No prebuilt ISO exists.
- **Termux rootfs:** build with `termux/build-rootfs.sh`. Install scripts are generated per release by `termux/make-release-metadata.sh` from the real tarballs (the checked-in templates refuse to run). v0.2.0 does not attach a rootfs tarball.

### Requirements

| Target | Needs |
|---|---|
| CLI from a clone | Python 3; for device work: `adb`/`fastboot`, `jq`, `curl`, `unzip`, `zip`, `sha256sum`. Optional: PyYAML |
| ISO build | Debian 12 or Ubuntu 22.04+; `live-build`, `debootstrap`, `squashfs-tools`, `xorriso`, `isolinux`, `syslinux-utils`; root, a free loop device, 20 GB+ disk, 4 GB+ RAM |
| Installed system | x86_64; `/dev/kvm` and `kvm` group membership for emulator acceleration |
| Termux rootfs | Termux; `proot-distro` (unrooted) or `su` (chroot); several GB free; optional Termux:X11 |
| Phone app | USB Host/OTG phone, USB-C **data** cable; JDK 17+ and Android SDK (`ANDROID_HOME`) to build |
| Desktop | JDK 17 to build; `adb`/`fastboot` on `PATH` or in `ANDROID_HOME/platform-tools`. On Windows, USB drivers are **not** bundled (see [docs/DESKTOP.md](docs/DESKTOP.md)) |
| Docker | Docker Engine on Linux for USB passthrough; `ROOTFORGE_API_TOKEN` |

## Usage

```bash
export PYTHONPATH="$PWD/config/includes.chroot/usr/local/lib"   # skip on an installed ISO/rootfs
python3 -m rootforge.core.cli doctor              # environment checks
python3 -m rootforge.core.cli devices             # adb/fastboot inventory
python3 -m rootforge.core.cli device check …      # go/no-go before writes
python3 -m rootforge.core.cli module scaffold …   # Magisk/KernelSU templates
python3 -m rootforge.core.cli flash boot …        # flash an already-patched boot/init_boot (typed confirm)
python3 -m rootforge.core.cli backup create …     # partition backup with manifest
python3 -m rootforge.core.cli ota extract …       # payload extraction
python3 -m rootforge.core.cli avd create …        # rooted/unrooted AVD helpers (x86_64 host)
```

On an installed ISO or Termux rootfs the same verbs are `rootforge doctor`, `rootforge devices`, and so on.

Flash, unlock and backup scripts refuse unsafe vendors/states where possible and write only after explicit confirmation.
CLI confirmations are `FLASH <serial>`, `UNLOCK <serial>`, `RESTORE <serial>` (typed on `/dev/tty`).
Logs live under `${ROOTFORGE_HOME:-$HOME/rootforge}/logs/`, are created `0600` and redacted on exit (pattern-based, so do not rely on it to hide secrets).

### Host phone to target phone over USB OTG

1. Install the APK on the **host** phone and plug the **target** in with a USB-C **data** cable.
2. On the target, enable USB debugging (or reboot to bootloader) and accept the host's USB-permission prompt.
3. In the app: Flash Tools → Scan (or Dashboard → Connect). On first ADB use, accept the RSA key dialog on the **target**.
4. If the host does not switch into USB-host mode, put a USB-C OTG adapter on the **host** end.

Destructive actions print a plan and require a typed phrase. Pairing with droidcommand-ai is [above](#droidcommand-messenger).

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
./gradlew :desktop:packageAppImage # Linux AppImage (also built in CI)
gradlew :desktop:packageExe        # Windows .exe (run on Windows)
```

CI (`toolkit.yml`) also attempts a Windows `.msi` (`:desktop:packageMsi`); that artifact is optional if WiX/jpackage cannot produce it.

**Docker:**

```bash
docker build -f docker/Dockerfile -t rootforge-os-toolkit .
```

CI: `.github/workflows/` holds `lint.yml`, `android-apk.yml`, `toolkit.yml` (desktop + Docker) and `release.yml` (debug APK on `v*` tags; ISO/Termux still a draft after live-build + checksum verification). v0.2.0 published the debug APK via `gh release create` because `release.yml` on that commit did not yet attach an APK.

## Project structure

```text
auto/                 live-build entry (config, build)
config/               package lists, hooks, includes.chroot (scripts under usr/local/bin)
termux/               PRoot/chroot rootfs build + release metadata templates
android-app/          :core (shared Apache-2.0), :app (Android APK), :desktop (Linux/Windows)
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
| [docs/ANDROID_APP.md](docs/ANDROID_APP.md) | Host-phone APK: USB Host, Flash Tools, screens, limits |
| [docs/FLASH_TOOLS_PLAN.md](docs/FLASH_TOOLS_PLAN.md) | Flash Tools hub, device-match gate, EDL-Commander plan |
| [docs/DESKTOP.md](docs/DESKTOP.md) | Linux/Windows desktop, Windows USB drivers, Docker |
| [docs/DROIDCOMMAND_INTEGRATION.md](docs/DROIDCOMMAND_INTEGRATION.md) | Bridge protocol for droidcommand-ai (Messenger + pairing) |
| [tests/README.md](tests/README.md) | Test and release-verifier notes |
| [CHANGELOG.md](CHANGELOG.md) | v0.2.0 and later notes |
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
reused on their own terms (including by VictorSuite). Combined binaries are
GPL-3.0-or-later.

**Third-party code is not relicensed.** Debian packages in the ISO and rootfs,
Termux / PRoot / proot-distro, Magisk / KernelSU / APatch, the Android SDK/NDK,
Node.js, Ollama, Claude Code, Calamares, and other build-time downloads keep
their upstream licenses. This repository does not vendor those projects; it
ships RootForge installers, plugins, branding, and pinned fetchers. See
[BUILD.md](BUILD.md) and `config/hooks/`.

## Credits

Built on Debian live-build, Magisk, KernelSU, APatch, AOSP tooling, Termux and proot-distro, plus the pinned upstream projects pulled in by the image hooks. RootForge OS is not a fork of any of them.
