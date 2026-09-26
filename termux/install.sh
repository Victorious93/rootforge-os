#!/usr/bin/env bash
# RootForge OS — Termux one-command installer
# Victorious Framework | Origin Source Labs
#
# Installs proot-distro (if needed), fetches the RootForge OS plugin
# definition, and installs the rootfs. Run from inside Termux:
#
#   curl -fsSL https://raw.githubusercontent.com/Victorious93/rootforge-os/main/termux/install.sh | bash
#
# This only automates the client-side steps — it still needs the plugin's
# TARBALL_URL to point at a real published tarball (see
# termux/proot-distro-plugins/rootforge.sh and .github/workflows/release.yml,
# which builds and publishes one on a tagged release).

set -euo pipefail

PLUGIN_URL="https://raw.githubusercontent.com/Victorious93/rootforge-os/main/termux/proot-distro-plugins/rootforge.sh"
PLUGIN_DIR="${PREFIX:-/data/data/com.termux/files/usr}/etc/proot-distro"

log() { echo "[rootforge-termux] $*"; }
die() { echo "[rootforge-termux] ERROR: $*" >&2; exit 1; }

command -v pkg >/dev/null 2>&1 || die "This doesn't look like Termux (no 'pkg' command) — run this inside the Termux app."

# There are two variants, and picking the wrong one wastes a multi-GB
# download. Say which this device can use before starting it.
if command -v su >/dev/null 2>&1 && su -c 'id -u' 2>/dev/null | grep -q '^0$'; then
  log "This device appears to be ROOTED (su returned uid 0)."
  log ""
  log "You can use either variant:"
  log "  - PRoot (what this script installs): no root needed, ptrace-emulated."
  log "  - chroot (rootforge-chroot.sh): real chroot, noticeably faster, and"
  log "    it gets real device nodes — loop-mounting partition images, USB"
  log "    adb/fastboot without Termux:API, and /dev/net/tun for a userspace VPN."
  log ""
  log "For the chroot variant instead, fetch the rooted tarball and launcher:"
  log "  curl -fsSLO https://github.com/Victorious93/rootforge-os/releases/latest/download/rootforge-chroot-\$(uname -m | sed 's/aarch64/arm64/;s/x86_64/amd64/').tar.xz"
  log "  curl -fsSLO https://raw.githubusercontent.com/Victorious93/rootforge-os/main/termux/rootforge-chroot.sh"
  log "  chmod +x rootforge-chroot.sh && ./rootforge-chroot.sh install rootforge-chroot-*.tar.xz"
  log ""
  log "Neither variant runs harden_kernel.sh/harden_system.sh: those need"
  log "kernel subsystems Android does not ship, and root does not change"
  log "which kernel you are on. See README section 17."
  log ""
  log "Continuing with the PRoot variant in 5s — Ctrl-C to stop and use chroot instead."
  sleep 5
else
  log "No root detected — installing the PRoot variant, which is the right"
  log "  choice for an unrooted device."
fi

log "Installing proot-distro"
pkg install -y proot-distro

log "Fetching the RootForge OS plugin definition"
mkdir -p "$PLUGIN_DIR"
curl -fsSL -o "$PLUGIN_DIR/rootforge.sh" "$PLUGIN_URL"

log "Installing the rootfs (downloads a multi-GB tarball — Wi-Fi recommended)"
proot-distro install rootforge

log "Done. Log in with:"
log "  proot-distro login rootforge"
log ""
log "First thing inside the container: bootstrap_proot.sh, to fetch the Android SDK/NDK."
log "See README section 17 for what does and doesn't work in this non-root variant."
log ""
log "For a graphical desktop (optional):"
log "  # in Termux, outside the container:"
log "  pkg install x11-repo && pkg install termux-x11-nightly"
log "  # plus the Termux:X11 APK from github.com/termux/termux-x11 releases"
log "  termux-x11 :0 &"
log "  proot-distro login rootforge --shared-tmp"
log "  # then inside the container:"
log "  rootforge_desktop.sh --check     # confirm the server is reachable"
log "  rootforge_desktop.sh             # start XFCE"

# Victorious Framework
