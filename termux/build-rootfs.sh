#!/usr/bin/env bash
# RootForge OS — Termux/PRoot rootfs builder (non-root variant)
# Victorious Framework | Origin Source Labs
#
# Builds a minimal Debian bookworm rootfs suitable for `proot-distro install`
# inside Termux on Android — no root, no kernel, no bootloader; PRoot fakes
# the root uid via ptrace syscall interception on top of Android's own
# kernel, so this is *not* the same "root" as a Magisk/KernelSU rooted
# phone — see README's Termux section for exactly what that distinction
# means for what does and doesn't work here.
#
# Reuses the same build-time hooks as the full ISO
# (config/hooks/*.hook.chroot) for the tools that work identically
# under PRoot (Node/Claude Code, Ollama, magiskboot, repo, avbtool, Zygisk
# headers, ccache — the arch-hardcoded ones were fixed to be arch-aware
# specifically so this script can reuse them unmodified) plus a pruned
# package list (termux/package-lists/rootforge-proot.list.chroot) that
# drops everything requiring real root/kernel privileges an Android host
# won't grant an unprivileged app.
#
# Must run as root on a Linux host with debootstrap + qemu-user-static +
# binfmt-support installed:
#   apt-get install debootstrap qemu-user-static binfmt-support
# Cross-building arm64 on an amd64 box — the common case, since real
# Android hardware is what this targets — works through qemu-user-static's
# binfmt registration; building natively on an arm64 host skips that step.
#
# Usage: sudo termux/build-rootfs.sh [arm64|amd64] [output-dir]
#                                     [--flavor proot|chroot] [--with-x11]
#   arm64 (default) — real Android hardware, almost always arm64
#   amd64           — x86 Android devices, or a desktop-Linux PRoot sandbox
#
#   --flavor proot  (default) unrooted device: runs under proot-distro.
#   --flavor chroot rooted device: runs in a real chroot via `su`, launched by
#                   termux/rootforge-chroot.sh. The rootfs contents differ:
#                   the chroot flavor keeps the scripts that need real device
#                   nodes (VPN over /dev/net/tun, loop-mounting a partition
#                   image, USB adb/fastboot), which PRoot cannot provide.
#                   It does NOT keep harden_kernel.sh/harden_system.sh: those
#                   need kernel subsystems (AppArmor, auditd, nftables,
#                   USBGuard) that Android kernels do not ship, and root does
#                   not change which kernel you are on.
#
#   --with-x11      also install the XFCE desktop layer, for use with the
#                   Termux:X11 app. Roughly triples the tarball, hence opt-in.

set -euo pipefail

ARCH="arm64"
OUT_DIR="$(pwd)/dist"
FLAVOR="proot"
WITH_X11=0

# Positional arch and output-dir are kept for backward compatibility with the
# documented `build-rootfs.sh arm64 /some/dir` form; flags may follow them in
# any order.
POSITIONAL=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --flavor)
      [[ $# -ge 2 ]] || { echo "--flavor needs a value (proot|chroot)" >&2; exit 1; }
      FLAVOR="$2"; shift 2 ;;
    --with-x11) WITH_X11=1; shift ;;
    -h|--help)
      sed -n '2,50p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    -*) echo "Unknown option: $1" >&2; exit 1 ;;
    *)
      case $POSITIONAL in
        0) ARCH="$1" ;;
        1) OUT_DIR="$1" ;;
        *) echo "Unexpected argument: $1" >&2; exit 1 ;;
      esac
      POSITIONAL=$((POSITIONAL + 1))
      shift ;;
  esac
done

case "$FLAVOR" in
  proot|chroot) ;;
  *) echo "Unknown flavor '$FLAVOR' — expected proot or chroot" >&2; exit 1 ;;
esac
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
STAMP="$(date +%Y%m%d_%H%M%S)"
ROOTFS="$(mktemp -d /tmp/rootforge-proot-rootfs.XXXXXX)"

log() { echo "[proot-build] $*"; }
die() { echo "[proot-build] ERROR: $*" >&2; exit 1; }
cleanup() {
  # Unmount in reverse order, ignoring anything not actually mounted (the
  # script may die before all of these are set up) — must happen before
  # rm -rf, or it either fails on a busy mountpoint or silently deletes the
  # mountpoint out from under the host's /proc, /sys, /dev.
  for m in dev/pts dev sys proc; do
    mountpoint -q "$ROOTFS/$m" 2>/dev/null && umount -l "$ROOTFS/$m"
  done
  rm -rf "$ROOTFS"
}
trap cleanup EXIT

[[ $EUID -eq 0 ]] || die "Must run as root (debootstrap/chroot need it): sudo $0 $*"

case "$ARCH" in
  arm64) DEBOOTSTRAP_ARCH="arm64" ;;
  amd64) DEBOOTSTRAP_ARCH="amd64" ;;
  *) die "Unsupported arch '$ARCH' — expected arm64 or amd64" ;;
esac

command -v debootstrap >/dev/null 2>&1 || die "debootstrap not installed: apt-get install debootstrap qemu-user-static binfmt-support"

HOST_ARCH="$(dpkg --print-architecture 2>/dev/null || true)"
CROSS_BUILD=0
if [[ "$DEBOOTSTRAP_ARCH" != "$HOST_ARCH" ]]; then
  CROSS_BUILD=1
  case "$DEBOOTSTRAP_ARCH" in
    arm64) QEMU_BIN=/usr/bin/qemu-aarch64-static ;;
    amd64) QEMU_BIN=/usr/bin/qemu-x86_64-static ;;
  esac
  [[ -f "$QEMU_BIN" ]] || die "$QEMU_BIN not found — cross-building $DEBOOTSTRAP_ARCH on $HOST_ARCH needs: apt-get install qemu-user-static binfmt-support"
fi

log "Stage 1: debootstrap bookworm ($DEBOOTSTRAP_ARCH) -> $ROOTFS"
debootstrap --arch="$DEBOOTSTRAP_ARCH" --foreign bookworm "$ROOTFS" http://deb.debian.org/debian

if [[ $CROSS_BUILD -eq 1 ]]; then
  log "Cross-build: staging $QEMU_BIN into the rootfs for the second stage"
  cp "$QEMU_BIN" "$ROOTFS/usr/bin/"
fi

log "Stage 2: second-stage debootstrap inside the chroot"
chroot "$ROOTFS" /debootstrap/debootstrap --second-stage

log "Bind-mounting /proc, /sys, /dev, /dev/pts into the chroot"
# Package postinst scripts routinely need these — ca-certificates-java's
# Java cacerts update, dbus/polkitd's service setup, and others fail with
# "Sub-process /usr/bin/dpkg returned an error code (1)" without /proc in
# particular. live-build's own ISO chroot gets this for free via its
# lb_chroot_proc/lb_chroot_devpts hooks; debootstrap+chroot by hand doesn't.
mount -t proc proc "$ROOTFS/proc"
mount -t sysfs sysfs "$ROOTFS/sys"
mount --bind /dev "$ROOTFS/dev"
mount -t devpts devpts "$ROOTFS/dev/pts"

log "Blocking service auto-start during package installs (policy-rc.d)"
cat > "$ROOTFS/usr/sbin/policy-rc.d" <<'POLICY'
#!/bin/sh
exit 101
POLICY
chmod +x "$ROOTFS/usr/sbin/policy-rc.d"

log "Configuring apt sources, hostname, resolver"
cat > "$ROOTFS/etc/apt/sources.list" <<'SOURCES'
deb http://deb.debian.org/debian bookworm main contrib non-free non-free-firmware
deb http://deb.debian.org/debian bookworm-updates main contrib non-free non-free-firmware
deb http://deb.debian.org/debian-security bookworm-security main contrib non-free non-free-firmware
SOURCES
echo "rootforge-proot" > "$ROOTFS/etc/hostname"
echo "nameserver 1.1.1.1" > "$ROOTFS/etc/resolv.conf"

chroot "$ROOTFS" apt-get update -q
PKGLIST="$(grep -v '^#' "$SCRIPT_DIR/package-lists/rootforge-proot.list.chroot" | grep -v '^[[:space:]]*$')"
# shellcheck disable=SC2086
DEBIAN_FRONTEND=noninteractive chroot "$ROOTFS" apt-get install -y --no-install-recommends $PKGLIST

if [[ $WITH_X11 -eq 1 ]]; then
  log "Installing the X11 desktop layer (--with-x11)"
  X11LIST="$(grep -v '^#' "$SCRIPT_DIR/package-lists/rootforge-x11.list.chroot" | grep -v '^[[:space:]]*$')"
  # shellcheck disable=SC2086
  DEBIAN_FRONTEND=noninteractive chroot "$ROOTFS" apt-get install -y --no-install-recommends $X11LIST
fi

log "Stage 3: running build-time hooks shared with the full ISO"
mkdir -p "$ROOTFS/hooks"
SHARED_HOOKS="0010-nodesource 0020-ollama 0030-claude-code 0050-starship-eza
0060-magiskboot 0061-repo-tool 0062-payload-dumper
0070-workspace-skel 0085-avbtool 0095-zygisk-headers 0098-ccache-config"
for h in $SHARED_HOOKS; do
  cp "$REPO_ROOT/config/hooks/${h}.hook.chroot" "$ROOTFS/hooks/${h}.sh"
  chmod +x "$ROOTFS/hooks/${h}.sh"
  log "  running $h"
  chroot "$ROOTFS" "/hooks/${h}.sh" 2>&1 | sed 's/^/    /'
done
rm -rf "$ROOTFS/hooks"

log "Stage 4: copying RootForge scripts (dropping root/kernel-only ones)"
mkdir -p "$ROOTFS/usr/local/bin" "$ROOTFS/usr/local/share/rootforge" "$ROOTFS/etc/profile.d"
# What each flavor can actually run.
#
# Both drop the kernel-hardening scripts and 00_bootstrap_distro.sh. That is
# not about root: harden_kernel.sh edits GRUB and sets sysctls on a kernel
# you control, and harden_system.sh drives AppArmor, auditd, nftables and
# USBGuard. Android kernels ship none of those (SELinux instead), and there
# is no GRUB on a phone. A rooted device is still running Android's kernel,
# so root does not bring them back — shipping them in the chroot flavor
# would just be a promise the environment cannot keep.
#
# The chroot flavor DOES keep the network scripts: a real chroot with /dev
# bind-mounted has /dev/net/tun, which is what a userspace WireGuard or
# tailscaled actually needs.
if [[ "$FLAVOR" == "chroot" ]]; then
  EXCLUDE_SCRIPTS="00_bootstrap_distro.sh harden_kernel.sh harden_system.sh"
else
  EXCLUDE_SCRIPTS="00_bootstrap_distro.sh harden_kernel.sh harden_system.sh setup_vpn.sh join_headscale.sh"
fi
# [Certain] plain *.sh was silently dropping extensionless wrappers like
# rootforge and brain (same convention as avbtool/mkbootimg: a thin wrapper
# in usr/local/bin/ with no extension, calling into usr/local/lib/rootforge/)
# — confirmed by checking this glob against what's actually in
# includes.chroot/usr/local/bin/ now. -type f -maxdepth 1 catches all of them.
while IFS= read -r -d '' script; do
  base="$(basename "$script")"
  case " $EXCLUDE_SCRIPTS " in
    *" $base "*) continue ;;
  esac
  install -m 0755 "$script" "$ROOTFS/usr/local/bin/$base"
done < <(find "$REPO_ROOT/config/includes.chroot/usr/local/bin" -maxdepth 1 -type f -print0)
install -m 0755 "$SCRIPT_DIR/bootstrap_proot.sh" "$ROOTFS/usr/local/bin/bootstrap_proot.sh"
[[ -d "$REPO_ROOT/config/includes.chroot/usr/local/share/rootforge" ]] && \
  cp -r "$REPO_ROOT/config/includes.chroot/usr/local/share/rootforge/." "$ROOTFS/usr/local/share/rootforge/"
# usr/local/lib/rootforge/ — rootforge CLI's core/ package and brain.py's
# second-brain/ package, plus anything else a usr/local/bin/ wrapper execs
# into, same convention as the share/ copy above.
if [[ -d "$REPO_ROOT/config/includes.chroot/usr/local/lib/rootforge" ]]; then
  mkdir -p "$ROOTFS/usr/local/lib/rootforge"
  cp -r "$REPO_ROOT/config/includes.chroot/usr/local/lib/rootforge/." "$ROOTFS/usr/local/lib/rootforge/"
fi
# etc/skel/second-brain/ — the PARA-method vault brain operates on. Not
# etc/skel/ wholesale: most of it (Desktop/, GNOME autostart, etc.) is
# GUI-specific and doesn't belong in this headless variant.
if [[ -d "$REPO_ROOT/config/includes.chroot/etc/skel/second-brain" ]]; then
  mkdir -p "$ROOTFS/etc/skel/second-brain"
  cp -r "$REPO_ROOT/config/includes.chroot/etc/skel/second-brain/." "$ROOTFS/etc/skel/second-brain/"
fi

log "Stage 5: recording build flavor and running container setup"
# A tarball on a phone is easy to lose track of; put the answer inside it.
mkdir -p "$ROOTFS/etc/rootforge"
cat > "$ROOTFS/etc/rootforge/build-info" <<BUILDINFO
flavor=$FLAVOR
arch=$ARCH
x11=$WITH_X11
built=$STAMP
BUILDINFO

log "Stage 5b: PRoot-specific setup (motd, workspace skeleton)"
install -m 0755 "$SCRIPT_DIR/proot-setup.sh" "$ROOTFS/usr/local/bin/proot-setup.sh"
chroot "$ROOTFS" /usr/local/bin/proot-setup.sh
rm -f "$ROOTFS/usr/local/bin/proot-setup.sh"

log "Stage 6: cleanup"
rm -f "$ROOTFS/usr/sbin/policy-rc.d"
chroot "$ROOTFS" apt-get clean
rm -rf "$ROOTFS"/var/lib/apt/lists/* "$ROOTFS"/tmp/* "$ROOTFS/debootstrap"
[[ $CROSS_BUILD -eq 1 ]] && rm -f "$ROOTFS"/usr/bin/qemu-*-static

log "Unmounting /proc, /sys, /dev, /dev/pts before packing"
# Must happen before tar, not just in the exit trap — packing them up would
# ship live kernel runtime state (or a hung/gigantic tar read) instead of an
# empty mountpoint directory.
for m in dev/pts dev sys proc; do
  mountpoint -q "$ROOTFS/$m" 2>/dev/null && umount -l "$ROOTFS/$m"
done

mkdir -p "$OUT_DIR"
# The flavor and the desktop layer are both in the filename: a chroot rootfs
# and a proot rootfs are not interchangeable, and silently overwriting one
# with the other would be a miserable thing to debug on a phone.
X11_SUFFIX=""
[[ $WITH_X11 -eq 1 ]] && X11_SUFFIX="-x11"
TARBALL="$OUT_DIR/rootforge-${FLAVOR}-${ARCH}${X11_SUFFIX}-${STAMP}.tar.xz"
log "Stage 7: packing $TARBALL"
tar -C "$ROOTFS" -cJf "$TARBALL" .
sha256sum "$TARBALL" | awk '{print $1}' > "${TARBALL}.sha256"

# This whole script must run as root (debootstrap/chroot need it), so
# $OUT_DIR and the tarball/checksum land root-owned — confirmed by a real
# CI failure ("Permission denied") in the next, non-sudo workflow step
# that renames the tarball to a stable asset name. Hand ownership back to
# whoever actually invoked sudo so it isn't left inaccessible to them.
if [[ -n "${SUDO_USER:-}" ]]; then
  chown -R "$SUDO_USER" "$OUT_DIR"
fi

log "Done."
log "  Tarball: $TARBALL"
log "  SHA256:  $(cat "${TARBALL}.sha256")"
log ""
log "Publish both to a GitHub Release, then fill TARBALL_URL / TARBALL_SHA256 for"
log "'$ARCH' in termux/proot-distro-plugins/rootforge.sh."

# Victorious Framework
