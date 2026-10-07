# Building RootForge OS
**Victorious Framework | Origin Source Labs**

Don't need to build it yourself? `.github/workflows/release.yml` is meant to build this
same ISO (and the Termux/PRoot rootfs — see README section 17) on every
tagged push, after the lint/test workflow passes on that commit, verify the
whole asset set with `tests/verify-release-assets.sh`, and attach it, with
checksums, to a **draft** [GitHub Release](https://github.com/Victorious93/rootforge-os/releases).
**As of 2026-10-07 no release exists** (the repository has tags `v0.1.0` and
`v0.1.1` but no Release), and the workflow has not been run on GitHub's
infrastructure after these changes. README section 14 covers flashing a
downloaded or locally-built ISO to a USB drive (`make list-usb` /
`sudo make flash USB=/dev/sdX`, both checksum-aware).

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
git clone https://github.com/Victorious93/rootforge-os.git
cd rootforge-os
sudo auto/build
```

The ISO lands as `rootforge-os-amd64.hybrid.iso` in the project root. Build time is 20–60 minutes depending on network speed (hooks fetch NodeSource, Ollama, Claude Code, magiskboot, eza, starship, repo, and payload-dumper-go at build time; see the table below for what is pinned and verified).

`auto/build` writes a timestamped log alongside the ISO. Use `sudo make build` instead of `sudo auto/build` directly and it also writes `rootforge-os-amd64.hybrid.iso.sha256` (or run `make checksum` afterward) — `make flash` verifies against that checksum automatically if it's present.

Failure behavior you can rely on (covered by `tests/run-tests.sh` with a stubbed `lb`): `auto/build` deletes any ISO, digest file and `binary*.iso` left by an earlier build before it starts, and exits with `lb build`'s own status; `make build` stops at a failed build, so a checksum is never written for a stale image. If live-build skips stages because an earlier build left markers in `.build/`, no ISO is produced and `auto/build` says so: run `sudo make clean` and build again.

**Boot firmware: BIOS only.** The live ISO boots through isolinux (`auto/config` explains why GRUB is not used for the image). UEFI boot of the live ISO and Secure Boot are **unsupported and unverified**; see `docs/PLATFORM_SUPPORT.md`.

**Verified here vs. not.** The test suite, the lint pass, the hook static checks and the Makefile/`auto/build` failure paths (with stubs) run without root. The actual `lb build`, the hooks inside a chroot, booting the ISO and running the Calamares installer require a host with loop devices and a VM, and have **not** been run in the environment this documentation was last updated in (no loop device was available).

## What is NOT in the ISO

These are fetched at **first boot** (requires network, ~2–5 GB disk):

| Component | Why deferred |
|---|---|
| Android SDK + cmdline-tools | ~1 GB, version-churn-prone |
| Android NDK | ~1 GB per version |
| Android emulator system images | 1–2 GB each |
| Magisk source tree | Gradle build env needed at runtime |

First-boot provisioning runs via `rootforge-firstboot.service` on the installed system. **It does not run in the live session** — the service is gated on `/etc/rootforge-installed`, which Calamares writes post-install. It provisions the account the installer recorded in `/var/lib/rootforge/install-user`, never root and never a guessed user: `00_bootstrap_distro.sh` resolves the target as `--user`, then `$SUDO_USER`, then that file, then the invoking non-root user, and otherwise refuses. Stages (`packages`, `udev`, `groups` as root; `workspace`, `sdk` as the user) write completion markers, so an offline first boot resumes at the failed stage on the unit's retry (`Restart=on-failure`, every 5 minutes). The SDK stage is amd64-only because Google's tools are x86-64 binaries; on other CPUs use `termux/bootstrap_proot.sh`. Run `00_bootstrap_distro.sh --check` to see what it resolved. **[Likely]** correct under systemd and Calamares; only the commands and the resolution logic are tested here, not a real install.

## What IS in the squashfs

| Component | How | Pinned / verified |
|---|---|---|
| Node.js 22 | NodeSource repo (hook 0010) | Signing-key fingerprints pinned; package version floats within 22.x. Node 20 is end of life and the pinned Claude Code requires >=22 |
| Claude Code CLI | `npm install -g` (hook 0030) | Version `2.1.292` pinned and registry integrity checked; its native per-platform dependency is not separately pinned |
| Ollama binary + service | Official installer script (hook 0020) | **Not pinned.** Downloaded to a file, checked non-empty, then run as root; the installer fetches whatever Ollama serves that day. Deferred: pinning needs the release digests, which could not be fetched here |
| magiskboot | Extracted from Magisk release APK (hook 0060) | Version + SHA-256 pinned |
| Google repo tool | git-repo tag `v2.65` (hook 0061) | SHA-256 pinned; launcher version checked |
| payload-dumper-go | GitHub release binary (hook 0062) | Version + SHA-256 pinned |
| starship, eza | GitHub releases (hook 0050) | Version + SHA-256 pinned |
| rpi-imager | raspberrypi.com .deb (hook 0040) | Version + SHA-256 pinned |
| avbtool, Zygisk headers | Pinned upstream commits (hooks 0085, 0095) | Commit + SHA-256 pinned |
| All 30 automation scripts (incl. `rootforge`, the unified CLI; `brain`, the second-brain CLI; and `rootforge_desktop.sh`, the Termux:X11 desktop launcher) | `/usr/local/bin/` |

## Disk install

Boot the ISO. Click **Install RootForge OS** on the GNOME desktop. Calamares presents the same three choices as Ubuntu's installer: erase disk, install alongside an existing OS (dual-boot, detected via os-prober), or manual partitioning.

After install, `update-grub` will re-run os-prober and add any existing OS to the boot menu.

## Architecture note

GNOME is the configured desktop. On a machine that simultaneously runs an accelerated Android emulator and a kernel build, budget **16 GB+ RAM**. The same box with XFCE would be comfortable at 8 GB — swap by editing `auto/config` (`--bootappend-live`) and the GNOME entries in `config/package-lists/rootforge.list.chroot`.
