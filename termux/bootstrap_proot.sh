#!/usr/bin/env bash
# RootForge OS — Termux/PRoot Android SDK/NDK bootstrap (non-root variant)
# Victorious Framework | Origin Source Labs
#
# Trimmed version of 00_bootstrap_distro.sh for the PRoot rootfs: fetches
# the Android cmdline-tools, platform-tools, build-tools, and NDK the same
# way, but skips everything 00_bootstrap_distro.sh does that needs real
# root/kernel access this container doesn't have — GNOME/qemu-kvm/docker.io
# apt installs, udev rules, kvm/plugdev/docker group membership (00_bootstrap
# isn't shipped in this rootfs at all; this script replaces it).
#
# System images are NOT installed by default — no /dev/kvm means no
# acceleration, so an AVD here is unaccelerated software rendering at best.
# Pass --with-system-image if you want one anyway (fine for a basic
# install/boot smoke test against a module, just slow).
#
# Usage: ./bootstrap_proot.sh [--with-system-image]

set -euo pipefail

WITH_SYSTEM_IMAGE=0
[[ "${1:-}" == "--with-system-image" ]] && WITH_SYSTEM_IMAGE=1

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
SDK_ROOT="$ROOTFORGE_HOME/android-sdk"
LOG_DIR="$ROOTFORGE_HOME/logs"
STAMP="$(date +%Y%m%d_%H%M%S)"
mkdir -p "$LOG_DIR" "$ROOTFORGE_HOME"/{devices,modules,kernels,keys,avd-profiles}
chmod 700 "$ROOTFORGE_HOME/keys"
LOG_FILE="$LOG_DIR/bootstrap_proot_${STAMP}.log"
log() { echo "[rootforge-proot] $*" | tee -a "$LOG_FILE"; }

log "Fetching Android cmdline-tools"
mkdir -p "$SDK_ROOT/cmdline-tools"
CMDTOOLS_ZIP="$(mktemp)"
# -f: an HTTP error otherwise lands in the zip and surfaces as a
# confusing "not a zipfile" from unzip instead of a download failure.
curl -fsSL -o "$CMDTOOLS_ZIP" \
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
unzip -q "$CMDTOOLS_ZIP" -d "$SDK_ROOT/cmdline-tools"
mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
rm -f "$CMDTOOLS_ZIP"

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
JAVAC_PATH="$(command -v javac)"
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVAC_PATH")")")"

log "Accepting SDK licenses and installing platform-tools, build-tools, NDK"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses > /dev/null || true

PACKAGES=(platform-tools "build-tools;34.0.0" "platforms;android-34" "ndk;26.1.10909125")
if [[ $WITH_SYSTEM_IMAGE -eq 1 ]]; then
  PACKAGES+=("emulator" "system-images;android-34;google_apis;arm64-v8a")
fi
"$SDKMANAGER" --sdk_root="$SDK_ROOT" "${PACKAGES[@]}" | tee -a "$LOG_FILE"

log "Writing environment profile"
PROFILE_D="/etc/profile.d/rootforge.sh"
if {
  echo "export ROOTFORGE_HOME=\"$ROOTFORGE_HOME\""
  echo "export ANDROID_SDK_ROOT=\"$SDK_ROOT\""
  echo "export ANDROID_HOME=\"$SDK_ROOT\""
  echo "export PATH=\"\$PATH:$SDK_ROOT/platform-tools:$SDK_ROOT/cmdline-tools/latest/bin\""
} > "$PROFILE_D" 2>/dev/null; then
  chmod 644 "$PROFILE_D"
else
  log "Could not write $PROFILE_D (no write access?) — add these exports to ~/.bashrc manually:"
  log "  export ANDROID_SDK_ROOT=$SDK_ROOT"
  log "  export ANDROID_HOME=$SDK_ROOT"
  log "  export PATH=\$PATH:$SDK_ROOT/platform-tools:$SDK_ROOT/cmdline-tools/latest/bin"
fi

if [[ $WITH_SYSTEM_IMAGE -eq 0 ]]; then
  log "Skipped system-image/emulator install — no /dev/kvm under Termux without root,"
  log "so an AVD here would be unaccelerated software rendering at best. Pass"
  log "--with-system-image if you want one anyway (adb/module testing against it still"
  log "works, just slow)."
fi

log "Bootstrap complete. Open a new shell (or 'source $PROFILE_D') to pick up the SDK PATH."

# Victorious Framework
