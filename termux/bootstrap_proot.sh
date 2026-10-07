#!/usr/bin/env bash
# RootForge OS — Termux/PRoot Android SDK/NDK bootstrap (non-root variant)
# Victorious Framework | Origin Source Labs
#
# Trimmed version of 00_bootstrap_distro.sh for the PRoot/chroot rootfs. It
# fetches only the parts of the Android SDK that can actually run on this
# CPU, and says plainly which it skipped and why.
#
# Why that matters: Google's Linux SDK packages are published for x86-64 hosts
# only. platform-tools' adb/fastboot, build-tools and the NDK toolchains are
# x86-64 ELF binaries (checked against platform-tools-latest-linux.zip), and
# the Android Emulator is listed in repository2-3.xml for Linux x64 alone — no
# Linux arm64 build exists. An arm64 phone is the usual target of this rootfs,
# so on arm64 this script does NOT install what cannot run:
#
#                       amd64 host        arm64 host
#   cmdline-tools       installed         installed (Java; CPU-independent)
#   platforms;android-34 installed        installed (android.jar; CPU-independent)
#   platform-tools      installed         skipped — this rootfs already has native
#                                         Debian adb/fastboot
#   build-tools, NDK    installed         skipped (x86-64 only); see below
#   emulator + image    --with-system-image   not available (no Linux arm64 emulator)
#
# --allow-nonnative-sdk installs build-tools and the NDK on arm64 anyway, for
# someone who has made x86-64 binaries runnable (e.g. qemu-user with binfmt).
# This script does not check that they run.
#
# System images are never installed by default; with no /dev/kvm an AVD is
# unaccelerated software emulation at best. KVM is a kernel facility: root
# does not create /dev/kvm.
#
# Usage: bootstrap_proot.sh [--plan] [--capabilities] [--with-system-image]
#                           [--allow-nonnative-sdk]
#   --plan          print what would be installed or skipped, then exit (no network)
#   --capabilities  print the probed runtime capabilities as JSON, then exit

set -euo pipefail

# shellcheck source=../config/includes.chroot/usr/local/lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

PLAN_ONLY=0
CAPS_ONLY=0
WITH_SYSTEM_IMAGE=0
ALLOW_NONNATIVE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --plan) PLAN_ONLY=1 ;;
    --capabilities) CAPS_ONLY=1 ;;
    --with-system-image) WITH_SYSTEM_IMAGE=1 ;;
    --allow-nonnative-sdk) ALLOW_NONNATIVE=1 ;;
    -h|--help) sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

if [[ $CAPS_ONLY -eq 1 ]]; then
  rf_runtime_capabilities_json
  exit 0
fi

HOST_ARCH="$(rf_host_arch)"
PACKAGES=()
SKIPPED=()

# CPU-independent: android.jar is Java.
PACKAGES+=("platforms;android-34")

case "$HOST_ARCH" in
  amd64)
    PACKAGES+=("platform-tools" "build-tools;34.0.0" "ndk;26.1.10909125")
    if [[ $WITH_SYSTEM_IMAGE -eq 1 ]]; then
      # The image ABI follows the host: an arm64 image on an x86-64 host would
      # run under full software CPU emulation.
      PACKAGES+=("emulator" "system-images;android-34;google_apis;x86_64")
    fi
    ;;
  arm64)
    SKIPPED+=("platform-tools: Google ships x86-64 adb/fastboot only; this rootfs already has native Debian adb and fastboot")
    if [[ $ALLOW_NONNATIVE -eq 1 ]]; then
      PACKAGES+=("build-tools;34.0.0" "ndk;26.1.10909125")
      SKIPPED+=("WARNING: build-tools and the NDK are x86-64 binaries; installed on request, not verified to run here")
    else
      SKIPPED+=("build-tools;34.0.0: x86-64 only (pass --allow-nonnative-sdk if you can run x86-64 binaries)")
      SKIPPED+=("ndk;26.1.10909125: x86-64 only (pass --allow-nonnative-sdk if you can run x86-64 binaries); build native modules on an x86-64 host instead")
    fi
    if [[ $WITH_SYSTEM_IMAGE -eq 1 ]]; then
      SKIPPED+=("emulator + system image: Google publishes the Android Emulator for Linux x64 only; there is no Linux arm64 build")
    fi
    ;;
  *)
    SKIPPED+=("everything native: CPU '${HOST_ARCH#unsupported:}' has no Google SDK binaries; only the Java parts are installed")
    ;;
esac

print_plan() {
  echo "host CPU: $HOST_ARCH"
  local p
  for p in "${PACKAGES[@]}"; do echo "install: $p"; done
  for p in "${SKIPPED[@]}"; do echo "skip:    $p"; done
}

if [[ $PLAN_ONLY -eq 1 ]]; then
  print_plan
  exit 0
fi

rf_require_cmd javac "install a JDK (apt install openjdk-17-jdk)"
rf_require_cmd unzip "apt install unzip"
rf_require_cmd curl "apt install curl"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
SDK_ROOT="$ROOTFORGE_HOME/android-sdk"
LOG_DIR="$ROOTFORGE_HOME/logs"
STAMP="$(date +%Y%m%d_%H%M%S)"
mkdir -p "$LOG_DIR" "$ROOTFORGE_HOME"/{devices,modules,kernels,keys,avd-profiles}
chmod 700 "$ROOTFORGE_HOME/keys"
LOG_FILE="$LOG_DIR/bootstrap_proot_${STAMP}.log"
log() { echo "[rootforge-proot] $*" | tee -a "$LOG_FILE"; }

log "Plan for this device:"
print_plan | sed 's/^/  /' | tee -a "$LOG_FILE"

log "Recording runtime capabilities"
rf_runtime_capabilities_json > "$ROOTFORGE_HOME/runtime-capabilities.json"

# Fetch into a staging directory and swap it in, so a failed or repeated run
# never leaves a half-extracted tool tree or trips over an existing one.
STAGE="$(mktemp -d "$ROOTFORGE_HOME/.cmdline-tools.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
CMDTOOLS_ZIP="$STAGE/cmdline-tools.zip"
log "Fetching Android cmdline-tools (pinned, SHA-256 verified)"
rf_fetch_verified "$RF_CMDLINE_TOOLS_URL" "$CMDTOOLS_ZIP" "$RF_CMDLINE_TOOLS_SHA256"
unzip -q "$CMDTOOLS_ZIP" -d "$STAGE/unpacked"
[[ -d "$STAGE/unpacked/cmdline-tools" ]] || { echo "Unexpected archive layout: no cmdline-tools/ directory" >&2; exit 1; }
mkdir -p "$SDK_ROOT/cmdline-tools"
rm -rf "$SDK_ROOT/cmdline-tools/latest"
mv "$STAGE/unpacked/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
JAVAC_PATH="$(command -v javac)"
JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVAC_PATH")")")"
export JAVA_HOME

log "Accepting SDK licenses and installing: ${PACKAGES[*]}"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses > /dev/null || true
"$SDKMANAGER" --sdk_root="$SDK_ROOT" "${PACKAGES[@]}" | tee -a "$LOG_FILE"

# Only add PATH entries for components that were actually installed.
PATH_ADD="$SDK_ROOT/cmdline-tools/latest/bin"
[[ -d "$SDK_ROOT/platform-tools" ]] && PATH_ADD="$SDK_ROOT/platform-tools:$PATH_ADD"
[[ -d "$SDK_ROOT/emulator" ]] && PATH_ADD="$SDK_ROOT/emulator:$PATH_ADD"

log "Environment for this user"
# Per-user, so provisioning never overwrites the shared /etc/profile.d/rootforge.sh
# or another user's setup. That shared profile already exports these for any
# user whose ROOTFORGE_HOME contains an android-sdk directory.
cat <<EOF
  export ROOTFORGE_HOME="$ROOTFORGE_HOME"
  export ANDROID_SDK_ROOT="$SDK_ROOT"
  export ANDROID_HOME="$SDK_ROOT"
  export PATH="\$PATH:$PATH_ADD"
EOF
log "Open a new login shell to pick up the SDK paths (the shared profile does this automatically)."
log "Bootstrap complete. Capabilities recorded in $ROOTFORGE_HOME/runtime-capabilities.json"

# Victorious Framework
