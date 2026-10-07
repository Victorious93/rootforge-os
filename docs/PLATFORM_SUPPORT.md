# RootForge OS — Platform support matrix

Status as of 2026-10-07. "Verified" means a test or run in this repository's own
tooling exercised it. **No row below has been verified on real hardware, a booted ISO,
a VM, or a real phone** — the hermetic test suite uses stubs for `adb`, `fastboot`,
`su`, `curl`, `lb`, `systemctl`-adjacent paths and Calamares inputs. The environment
this was written in had no loop device, so `lb build` could not run.

Legend: **Implemented** (code exists, covered by stub tests) · **Partial** · **Unsupported**
(will not work; documented) · **Not started** (no code) · **Unverified** (implemented,
never run for real).

## Hosts that run RootForge

| Platform | What it is | Status | Notes |
|---|---|---|---|
| **Desktop Linux — the RootForge ISO** | Debian 12, amd64, live + Calamares install | Implemented, **Unverified** | Boots **BIOS only** (isolinux). **UEFI boot of the live ISO and Secure Boot are unsupported.** The installed system carries `grub-efi-amd64`, but Calamares picks the install mode from the firmware the live session booted under, so today's ISO installs in BIOS mode. No ISO has been built or booted in this environment. |
| **Desktop Linux — the tools only** | `rootforge` CLI and scripts on an existing Debian/Ubuntu | Implemented | Needs `python3`, `python3-yaml`, `jq`, `adb`/`fastboot`. `rootforge doctor` checks them. Not packaged (no `.deb`/wheel): run from a checkout or copy `usr/local/`. |
| **Windows-hosted** | Windows PC | **Not started** | No Windows code exists. Realistic paths are WSL2 with `usbipd-win` for USB passthrough, or a Linux VM; **neither has been tried and neither is claimed to work**. Flashing through USB passthrough adds failure modes (mid-flash disconnects) that this project has not assessed. A native Windows build is out of scope until the Linux contracts are validated on hardware. |
| **Android, unrooted — Termux + PRoot** | Debian rootfs under `proot-distro` | Implemented, **Unverified** | arm64 and amd64 rootfs. PRoot is ptrace-emulated: no loop devices, no `/dev/kvm`, no real device nodes. Install requires a published release or a locally generated metadata set (see README §17). No release exists yet. |
| **Android, rooted — Termux + chroot** | Same rootfs, real `chroot` via `su` | Implemented, **Unverified** | `rootforge-chroot.sh install` requires a SHA-256 and scans the archive; `login` refuses an incomplete install. Gains faster execution, loop mounts and USB `adb`; does **not** gain a bootable system, GRUB, AppArmor/auditd/nftables/USBGuard, or `/dev/kvm`. |
| **Android APK / on-device app** | Native app with GUI, terminal, headless, remote | **Not started** | No Gradle project, manifest or source. Do not infer one from the Termux rootfs. |
| **Remote / multi-node administration** | Controller managing nodes | **Not started** | `fleet_orchestrate.sh` is sequential USB/fastboot orchestration on one host, not a client/server protocol. No node identity, discovery, or transport exists. |
| **GUI** | RootForge management GUI | **Not started** | Calamares is the only GUI and is a third-party installer. |

## What the Android SDK parts need (CPU honesty)

Google's Linux SDK downloads are **x86-64 only** for `platform-tools` (adb/fastboot),
`build-tools`, the NDK toolchains and the Android Emulator (checked against the
repository manifest and an ELF inspection of `platform-tools`, 2026-10). There is no Linux
arm64 emulator.

| Component | amd64 host | arm64 host (phone) |
|---|---|---|
| cmdline-tools | installed | installed (Java) |
| `platforms;android-34` | installed | installed (`android.jar` is Java) |
| platform-tools | installed | skipped — Debian's native `adb`/`fastboot` are in the rootfs |
| build-tools, NDK | installed | skipped; `--allow-nonnative-sdk` installs them for someone who has made x86-64 binaries runnable, **unverified** |
| Emulator + system image | on request (`--with-system-image`) | **not available** |

`termux/bootstrap_proot.sh --plan` prints this for the CPU it runs on;
`--capabilities` prints JSON that is also saved as `runtime-capabilities.json`. The
first-boot provisioner on the ISO (`00_bootstrap_distro.sh`) installs the SDK stage on
amd64 only.

## Feature availability by environment

| Feature | ISO / desktop Linux | Rooted chroot | PRoot |
|---|---|---|---|
| Flash / backup / restore / device check (`adb`/`fastboot`) | yes (stub-tested) | USB `adb`/`fastboot` possible | no USB device nodes in PRoot; networked adb only (**unverified**) |
| Boot image tools, OTA extraction, module scaffold/lint/build | yes | yes | yes (builds need the SDK parts above) |
| Loop-mounting images (`inspect_partition_image.sh`) | yes | yes | **no** |
| Emulator (`setup_rooted_avd.sh`) | x86-64 with `/dev/kvm` for acceleration | **no** | **no** |
| `harden_kernel.sh`, `harden_system.sh` | yes | **no** (not shipped) | **no** (not shipped) |
| GNOME + Calamares | yes | no | no |
| XFCE via Termux:X11 | — | optional | optional |

## Where each claim is checked

- Device, backup, config, log, doctor, dispatch: `tests/test_*.py` (329 tests).
- Scripts, provisioning, installer cleanup, Termux generator/launcher, Makefile and
  `auto/build` failure paths, release-asset verifier: `tests/run-tests.sh` (909 checks, one
  of which wraps the Python suite).
- Hooks (static only): `tests/check-hooks.sh`.
- Nothing here runs `lb build`, boots an ISO, or talks to a device.
