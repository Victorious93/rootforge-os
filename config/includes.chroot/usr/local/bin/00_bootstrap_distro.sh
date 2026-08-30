#!/usr/bin/env bash
# RootForge OS — bootstrap installer
# Victorious Framework
#
# Provisions a Debian 12 (Bookworm) base with the full Android root-module
# development stack: Android Studio, SDK/NDK, KVM emulator acceleration,
# kernel cross-toolchains, and the boot-image manipulation tools Magisk /
# KernelSU module development actually needs.
#
# Usage: sudo ./00_bootstrap_distro.sh [--headless]
#   --headless   skip XFCE desktop + Android Studio GUI install (CI/build-server profile)

set -euo pipefail

HEADLESS=0
[[ "${1:-}" == "--headless" ]] && HEADLESS=1

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
SDK_ROOT="$ROOTFORGE_HOME/android-sdk"
LOG_DIR="$ROOTFORGE_HOME/logs"
STAMP="$(date +%Y%m%d_%H%M%S)"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/bootstrap_${STAMP}.log"

log() { echo "[rootforge] $*" | tee -a "$LOG_FILE"; }

require_root() {
  if [[ $EUID -ne 0 ]]; then
    echo "This script needs root for apt/kvm-group/udev steps. Re-run with sudo." >&2
    exit 1
  fi
}
require_root

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
TARGET_USER="${SUDO_USER:-$USER}"
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
