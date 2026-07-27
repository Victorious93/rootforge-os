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

# Victorious Framework
