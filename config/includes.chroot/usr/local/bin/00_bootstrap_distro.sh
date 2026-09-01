#!/usr/bin/env bash
# RootForge OS — bootstrap installer
# Victorious Framework
#
# Provisions a Debian 12 (Bookworm) base with the full Android root-module
# development stack: Android Studio, SDK/NDK, KVM emulator acceleration,
# kernel cross-toolchains, and the boot-image manipulation tools Magisk /
# KernelSU module development actually needs.
#
# Usage: sudo ./00_bootstrap_distro.sh [--headless] [--check]
#   --headless   skip the GNOME desktop install (CI/build-server profile)
#   --check      resolve and print the paths this would use, then exit without
#                touching the system. Runs without root.

set -euo pipefail

usage() {
  echo "Usage: sudo $0 [--headless] [--check]" >&2
  echo "  --headless   skip the GNOME desktop install (CI/build-server profile)" >&2
  echo "  --check      print the resolved paths and exit, changing nothing" >&2
}

# `[[ "${1:-}" == "--headless" ]] && HEADLESS=1` silently ignored anything
# else, so `--headles` on a build server installed the full desktop — about
# 2 GB and an hour of apt, with no indication the flag had been dropped.
HEADLESS=0
CHECK_ONLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --headless) HEADLESS=1; shift ;;
    --check)    CHECK_ONLY=1; shift ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

# The workspace belongs to the person who ran sudo, not to root.
#
# This used to be `${ROOTFORGE_HOME:-$HOME/rootforge}`, and sudo sets HOME to
# the target user's home — /root. So ROOTFORGE_HOME became /root/rootforge,
# and the very next use of it is
#
#   sudo -u "$TARGET_USER" mkdir -p "$ROOTFORGE_HOME"/{devices,modules,...}
#
# i.e. the unprivileged user creating directories inside /root, which is mode
# 0700. That fails, and `set -e` ends the run there — after apt upgrade, the
# whole cross-toolchain, GNOME and the udev rules have already been installed.
# The bootstrap could not complete on a normal machine.
#
# getent, not $HOME: it answers for the user we are actually provisioning for,
# and it is right whether this is reached through sudo or as root directly.
# `${SUDO_USER:-$USER}` is itself an unbound-variable crash under set -u when
# USER is not exported — cron, a bare `sh -c`, some CI runners. `id -un` always
# answers.
TARGET_USER="${SUDO_USER:-$(id -un)}"
TARGET_HOME="$(getent passwd "$TARGET_USER" 2>/dev/null | cut -d: -f6 || true)"
if [[ -z "$TARGET_HOME" ]]; then
  echo "Could not resolve a home directory for '$TARGET_USER' via getent." >&2
  echo "Set ROOTFORGE_HOME explicitly and re-run." >&2
  exit 1
fi
# System accounts have a home of /nonexistent or /. Installing a 15 GB SDK
# there is not what anyone meant, and the failure would surface much later.
if [[ -z "${ROOTFORGE_HOME:-}" && ! -d "$TARGET_HOME" ]]; then
  echo "'$TARGET_USER' has home '$TARGET_HOME', which does not exist." >&2
  echo "That is usually a system account. Run this as the account that will use" >&2
  echo "RootForge, or set ROOTFORGE_HOME explicitly." >&2
  exit 1
fi

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$TARGET_HOME/rootforge}"
SDK_ROOT="$ROOTFORGE_HOME/android-sdk"
LOG_DIR="$ROOTFORGE_HOME/logs"
STAMP="$(date +%Y%m%d_%H%M%S)"

if [[ $CHECK_ONLY -eq 1 ]]; then
  echo "target user:     $TARGET_USER"
  echo "target home:     $TARGET_HOME"
  echo "ROOTFORGE_HOME:  $ROOTFORGE_HOME"
  echo "SDK_ROOT:        $SDK_ROOT"
  echo "desktop install: $([[ $HEADLESS -eq 1 ]] && echo skipped || echo GNOME)"
  exit 0
fi

require_root() {
  if [[ $EUID -ne 0 ]]; then
    echo "This script needs root for apt/kvm-group/udev steps. Re-run with sudo." >&2
    exit 1
  fi
}
require_root

# Created as the target user so the whole tree is theirs from the start —
# creating it as root and chowning afterwards leaves a window, and gets the
# ownership of anything written in between wrong.
install -d -o "$TARGET_USER" -g "$TARGET_USER" "$ROOTFORGE_HOME" "$LOG_DIR"
LOG_FILE="$LOG_DIR/bootstrap_${STAMP}.log"

log() { echo "[rootforge] $*" | tee -a "$LOG_FILE"; }

log "Updating base system"
apt-get update -y | tee -a "$LOG_FILE"
apt-get upgrade -y | tee -a "$LOG_FILE"

log "Installing core build + kernel cross-toolchain packages"
apt-get install -y --no-install-recommends \
  build-essential git curl wget unzip zip rsync ccache \
  openjdk-17-jdk \
  python3 python3-pip python3-venv \
  clang lld llvm binutils-aarch64-linux-gnu gcc-aarch64-linux-gnu \
  bc bison flex libssl-dev libelf-dev dwarves cpio kmod \
  qemu-kvm libvirt-daemon-system virtinst bridge-utils cpu-checker \
  android-sdk-platform-tools-common adb fastboot \
  android-sdk-libsparse-utils abootimg e2fsprogs \
  jq docker.io \
  gnupg lsb-release \
  | tee -a "$LOG_FILE"

if [[ $HEADLESS -eq 0 ]]; then
  log "Installing GNOME desktop profile"
  apt-get install -y --no-install-recommends \
    gnome-session gnome-shell gnome-terminal gnome-control-center \
    gdm3 gnome-tweaks | tee -a "$LOG_FILE"
fi

log "Adding invoking user to kvm, plugdev, and docker groups"
usermod -aG kvm,plugdev,docker "$TARGET_USER" || true

log "Installing udev rules for Android fastboot/adb device access"
cat > /etc/udev/rules.d/51-android.rules <<'EOF'
# RootForge OS — generic Android device access (adb + fastboot)
# Victorious Framework
SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666", GROUP="plugdev"
SUBSYSTEM=="usb", ATTR{idVendor}=="04e8", MODE="0666", GROUP="plugdev"
SUBSYSTEM=="usb", ATTR{idVendor}=="22b8", MODE="0666", GROUP="plugdev"
SUBSYSTEM=="usb", ATTR{idVendor}=="2717", MODE="0666", GROUP="plugdev"
SUBSYSTEM=="usb", ATTR{idVendor}=="12d1", MODE="0666", GROUP="plugdev"
EOF
udevadm control --reload-rules
udevadm trigger

log "Creating RootForge workspace layout at $ROOTFORGE_HOME"
sudo -u "$TARGET_USER" mkdir -p \
  "$ROOTFORGE_HOME"/{devices,modules,kernels,keys,avd-profiles,logs}
chmod 700 "$ROOTFORGE_HOME/keys"

log "Fetching Android cmdline-tools"
sudo -u "$TARGET_USER" mkdir -p "$SDK_ROOT/cmdline-tools"
# A fixed /tmp path is predictable and this script runs partly as root.
CMDTOOLS_ZIP="$(mktemp)"
# -f: without it curl exits 0 on an HTTP error and writes the error page
# into the zip, which then fails as "not a zipfile" several lines later.
curl -fsSL -o "$CMDTOOLS_ZIP" \
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
sudo -u "$TARGET_USER" unzip -q "$CMDTOOLS_ZIP" -d "$SDK_ROOT/cmdline-tools"
sudo -u "$TARGET_USER" mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
rm -f "$CMDTOOLS_ZIP"

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(which javac)")")")"

log "Accepting SDK licenses and installing platform-tools, build-tools, NDK, emulator"
yes | sudo -u "$TARGET_USER" env JAVA_HOME="$JAVA_HOME" \
  "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses > /dev/null || true

sudo -u "$TARGET_USER" env JAVA_HOME="$JAVA_HOME" "$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "emulator" \
  "build-tools;34.0.0" \
  "platforms;android-34" \
  "ndk;26.1.10909125" \
  "system-images;android-34;google_apis;x86_64" \
  | tee -a "$LOG_FILE"

log "Writing environment profile"
PROFILE_D="/etc/profile.d/rootforge.sh"
cat > "$PROFILE_D" <<EOF
export ROOTFORGE_HOME="$ROOTFORGE_HOME"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export PATH="\$PATH:$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$SDK_ROOT/cmdline-tools/latest/bin"
EOF
chmod 644 "$PROFILE_D"

log "Checking KVM acceleration availability"
if kvm-ok >/dev/null 2>&1; then
  log "KVM acceleration: available"
else
  log "WARNING: KVM not available — emulator will fall back to software rendering. Check virtualization is enabled in BIOS/hypervisor."
fi

log "Bootstrap complete. Log the target user out/in to pick up kvm/plugdev group membership."
log "Next: clone Magisk source into \$ROOTFORGE_HOME/modules and run build_magisk_module.sh, or run setup_rooted_avd.sh."

# Victorious Framework
