# Building RootForge OS
**Victorious Framework | Origin Source Labs**

Don't need to build it yourself? `.github/workflows/release.yml` builds this
same ISO (and the Termux/PRoot rootfs — see README section 17) on every
tagged push and attaches both, with `.sha256` checksums, to a draft
[GitHub Release](https://github.com/Victorious93/rootforge-os/releases).
README section 14 covers flashing a downloaded or locally-built ISO to a
USB drive (`make list-usb` / `sudo make flash USB=/dev/sdX`, both checksum-
aware).

## Prerequisites

On a Debian 12 (Bookworm) or Ubuntu 22.04+ host:

```bash
sudo apt-get install -y live-build debootstrap squashfs-tools xorriso isolinux syslinux-utils
```

Ensure at least one free loop device is available:
```bash
losetup -f          # should print /dev/loopN without error
modprobe loop       # if not
```

The build must run as root. A minimum of **20 GB free disk space** and **4 GB RAM** are recommended; the GNOME squashfs compresses to ~3–4 GB.

## Build

```bash
git clone https://github.com/origin-source-labs/rootforge-os.git
cd rootforge-os
sudo auto/build
```

The ISO lands as `rootforge-os-amd64.hybrid.iso` in the project root. Build time is 20–60 minutes depending on network speed (hooks fetch NodeSource, Ollama, Claude Code, magiskboot, eza, starship, repo, and payload-dumper-go at build time).

A timestamped log is written alongside the ISO. Use `sudo make build` instead of `sudo auto/build` directly and it also writes `rootforge-os-amd64.hybrid.iso.sha256` (or run `make checksum` afterward) — `make flash` verifies against that checksum automatically if it's present.

## What is NOT in the ISO

These are fetched at **first boot** (requires network, ~2–5 GB disk):

| Component | Why deferred |
|---|---|
| Android SDK + cmdline-tools | ~1 GB, version-churn-prone |
| Android NDK | ~1 GB per version |
| Android emulator system images | 1–2 GB each |
| Magisk source tree | Gradle build env needed at runtime |

First-boot provisioning runs automatically via `rootforge-firstboot.service` on the installed system. **It does not run in the live session** — the service is gated on `/etc/rootforge-installed`, which Calamares writes post-install.

## What IS in the squashfs

| Component | How |
|---|---|
| Node.js LTS | NodeSource repo (hook 0010) |
| Claude Code CLI | `npm install -g` (hook 0030) |
| Ollama binary + service | Official installer (hook 0020) |
| magiskboot | Extracted from Magisk release APK (hook 0060) |
| Google repo tool | storage.googleapis.com (hook 0061) |
| payload-dumper-go | GitHub release binary (hook 0062) |
| starship | starship.rs installer (hook 0050) |
| eza | GitHub release .tar.gz (hook 0050) |
| rpi-imager | raspberrypi.com .deb (hook 0040) |
| All 29 automation scripts (incl. `rootforge`, the unified CLI, and `brain`, the second-brain CLI) | `/usr/local/bin/` |

## Disk install

Boot the ISO. Click **Install RootForge OS** on the GNOME desktop. Calamares presents the same three choices as Ubuntu's installer: erase disk, install alongside an existing OS (dual-boot, detected via os-prober), or manual partitioning.

After install, `update-grub` will re-run os-prober and add any existing OS to the boot menu.

## Architecture note

GNOME is the configured desktop. On a machine that simultaneously runs an accelerated Android emulator and a kernel build, budget **16 GB+ RAM**. The same box with XFCE would be comfortable at 8 GB — swap by editing `auto/config` (`--bootappend-live`) and the GNOME entries in `config/package-lists/rootforge.list.chroot`.
