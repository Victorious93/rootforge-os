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
# (config/hooks/live/*.hook.chroot) for the tools that work identically
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
#   arm64 (default) — real Android hardware, almost always arm64
#   amd64           — x86 Android devices, or a desktop-Linux PRoot sandbox

set -euo pipefail

ARCH="${1:-arm64}"
OUT_DIR="${2:-$(pwd)/dist}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
STAMP="$(date +%Y%m%d_%H%M%S)"
ROOTFS="$(mktemp -d /tmp/rootforge-proot-rootfs.XXXXXX)"

log() { echo "[proot-build] $*"; }
die() { echo "[proot-build] ERROR: $*" >&2; exit 1; }
cleanup() { rm -rf "$ROOTFS"; }
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

log "Stage 3: running build-time hooks shared with the full ISO"
mkdir -p "$ROOTFS/hooks"
SHARED_HOOKS="0010-nodesource 0020-ollama 0030-claude-code 0050-starship-eza
0060-magiskboot 0061-repo-tool 0062-payload-dumper
0070-workspace-skel 0085-avbtool 0095-zygisk-headers 0098-ccache-config"
for h in $SHARED_HOOKS; do
  cp "$REPO_ROOT/config/hooks/live/${h}.hook.chroot" "$ROOTFS/hooks/${h}.sh"
  chmod +x "$ROOTFS/hooks/${h}.sh"
  log "  running $h"
  chroot "$ROOTFS" "/hooks/${h}.sh" 2>&1 | sed 's/^/    /'
done
rm -rf "$ROOTFS/hooks"

log "Stage 4: copying RootForge scripts (dropping root/kernel-only ones)"
mkdir -p "$ROOTFS/usr/local/bin" "$ROOTFS/usr/local/share/rootforge" "$ROOTFS/etc/profile.d"
EXCLUDE_SCRIPTS="00_bootstrap_distro.sh harden_kernel.sh harden_system.sh setup_vpn.sh join_headscale.sh"
for script in "$REPO_ROOT"/config/includes.chroot/usr/local/bin/*.sh; do
  base="$(basename "$script")"
  case " $EXCLUDE_SCRIPTS " in
    *" $base "*) continue ;;
  esac
  install -m 0755 "$script" "$ROOTFS/usr/local/bin/$base"
done
install -m 0755 "$SCRIPT_DIR/bootstrap_proot.sh" "$ROOTFS/usr/local/bin/bootstrap_proot.sh"
[[ -d "$REPO_ROOT/config/includes.chroot/usr/local/share/rootforge" ]] && \
  cp -r "$REPO_ROOT/config/includes.chroot/usr/local/share/rootforge/." "$ROOTFS/usr/local/share/rootforge/"

log "Stage 5: PRoot-specific setup (motd, workspace skeleton)"
install -m 0755 "$SCRIPT_DIR/proot-setup.sh" "$ROOTFS/usr/local/bin/proot-setup.sh"
chroot "$ROOTFS" /usr/local/bin/proot-setup.sh
rm -f "$ROOTFS/usr/local/bin/proot-setup.sh"

log "Stage 6: cleanup"
rm -f "$ROOTFS/usr/sbin/policy-rc.d"
chroot "$ROOTFS" apt-get clean
rm -rf "$ROOTFS"/var/lib/apt/lists/* "$ROOTFS"/tmp/* "$ROOTFS/debootstrap"
[[ $CROSS_BUILD -eq 1 ]] && rm -f "$ROOTFS"/usr/bin/qemu-*-static

mkdir -p "$OUT_DIR"
TARBALL="$OUT_DIR/rootforge-proot-${ARCH}-${STAMP}.tar.xz"
log "Stage 7: packing $TARBALL"
tar -C "$ROOTFS" -cJf "$TARBALL" .
sha256sum "$TARBALL" | awk '{print $1}' > "${TARBALL}.sha256"

log "Done."
log "  Tarball: $TARBALL"
log "  SHA256:  $(cat "${TARBALL}.sha256")"
log ""
log "Publish both to a GitHub Release, then fill TARBALL_URL / TARBALL_SHA256 for"
log "'$ARCH' in termux/proot-distro-plugins/rootforge.sh."

# Victorious Framework
