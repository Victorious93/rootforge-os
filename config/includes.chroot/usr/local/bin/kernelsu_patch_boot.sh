#!/usr/bin/env bash
# RootForge OS — KernelSU GKI boot image patcher
# Victorious Framework | Origin Source Labs
#
# Wraps the "fetch GKI kernel with KernelSU built in → patch → flash boot"
# flow for GKI (Generic Kernel Image) devices (most Pixels, growing set of
# Treble devices since Android 12).
#
# Unlike Magisk patching (which runs on-device via the Magisk app),
# KernelSU patching happens at the kernel/boot image level on the host.
#
# Usage:
#   kernelsu_patch_boot.sh --stock-boot <boot.img> --android-version <12|13|14>
#                          [--ksu-version <tag>] [--device <codename>]
#   kernelsu_patch_boot.sh --flash    # flash the last patched image
#
# Requirements: adb, fastboot, curl, python3 (for avbtool), magiskboot
#               (magiskboot is built by 00_bootstrap_distro.sh)

set -euo pipefail

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
WORK_DIR="$ROOTFORGE_HOME/kernelsu-work"
mkdir -p "$LOG_DIR" "$WORK_DIR"
STAMP="$(date +%Y%m%d_%H%M%S)"
LOG="$LOG_DIR/kernelsu_patch_${STAMP}.log"

log()  { echo "[kernelsu-patch] $*" | tee -a "$LOG"; }
die()  { echo "[kernelsu-patch] ERROR: $*" | tee -a "$LOG" >&2; exit 1; }

STOCK_BOOT=""
ANDROID_VER=""
KSU_VERSION="latest"
DEVICE_CODENAME="unknown"
FLASH_ONLY=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --stock-boot)      STOCK_BOOT="$2";        shift 2 ;;
    --android-version) ANDROID_VER="$2";       shift 2 ;;
    --ksu-version)     KSU_VERSION="$2";       shift 2 ;;
    --device)          DEVICE_CODENAME="$2";   shift 2 ;;
    --flash)           FLASH_ONLY=1;           shift   ;;
    *) die "Unknown argument: $1" ;;
  esac
done

# --- Flash-only path ---
if [[ $FLASH_ONLY -eq 1 ]]; then
  LATEST=$(ls -t "$WORK_DIR"/boot-ksu-patched-*.img 2>/dev/null | head -1)
  [[ -z "$LATEST" ]] && die "No patched boot image found in $WORK_DIR"
  log "Flashing most recent patched image: $LATEST"
  fastboot flash boot "$LATEST"
  log "Done. Reboot with: fastboot reboot"
  exit 0
fi

# --- Validation ---
[[ -z "$STOCK_BOOT" ]]   && die "--stock-boot <boot.img> required"
[[ -z "$ANDROID_VER" ]]  && die "--android-version <12|13|14> required"
[[ -f "$STOCK_BOOT" ]]   || die "Boot image not found: $STOCK_BOOT"

MAGISKBOOT="$(command -v magiskboot 2>/dev/null || echo "$ROOTFORGE_HOME/bin/magiskboot")"
[[ -x "$MAGISKBOOT" ]] || die "magiskboot not found. Run 00_bootstrap_distro.sh first."

# --- Fetch KernelSU GKI prebuilt kernel for this Android version ---
log "Fetching KernelSU GKI kernel (Android $ANDROID_VER, version: $KSU_VERSION)"

KSU_RELEASE_API="https://api.github.com/repos/tiann/KernelSU/releases"
if [[ "$KSU_VERSION" == "latest" ]]; then
  KSU_TAG=$(curl -fsSL "${KSU_RELEASE_API}/latest" | python3 -c "import sys,json; print(json.load(sys.stdin)['tag_name'])")
else
  KSU_TAG="$KSU_VERSION"
fi
log "KernelSU tag: $KSU_TAG"

# GKI kernel asset name convention: android<ver>-<tag>-Image.gz-dtb or similar
# We download the Image.gz for the target Android version
ASSET_PATTERN="android${ANDROID_VER}"
ASSET_URL=$(curl -fsSL "${KSU_RELEASE_API}/tags/${KSU_TAG}" \
  | python3 -c "
import sys, json
assets = json.load(sys.stdin)['assets']
pattern = '${ASSET_PATTERN}'
matches = [a['browser_download_url'] for a in assets if pattern in a['name'] and 'Image' in a['name']]
print(matches[0] if matches else '')
")

[[ -z "$ASSET_URL" ]] && die "No GKI Image asset found for Android $ANDROID_VER in $KSU_TAG. Check releases manually."

KSU_IMAGE="$WORK_DIR/Image-ksu-android${ANDROID_VER}-${KSU_TAG}"
log "Downloading: $ASSET_URL"
curl -fsSL -o "$KSU_IMAGE" "$ASSET_URL"

# --- Unpack stock boot image ---
UNPACK_DIR="$WORK_DIR/unpack-${STAMP}"
mkdir -p "$UNPACK_DIR"
cp "$STOCK_BOOT" "$UNPACK_DIR/boot.img"

log "Unpacking stock boot image with magiskboot"
(cd "$UNPACK_DIR" && "$MAGISKBOOT" unpack boot.img) | tee -a "$LOG"

# Identify the kernel slot in the unpacked image
KERNEL_FILE="$UNPACK_DIR/kernel"
[[ -f "$KERNEL_FILE" ]] || die "magiskboot unpack did not produce a kernel file — check boot.img format"

# --- Replace kernel with KernelSU GKI build ---
log "Replacing kernel with KernelSU GKI build"
cp "$KSU_IMAGE" "$KERNEL_FILE"

# --- Repack ---
log "Repacking boot image"
(cd "$UNPACK_DIR" && "$MAGISKBOOT" repack boot.img) | tee -a "$LOG"

PATCHED_IMG="$WORK_DIR/boot-ksu-patched-${DEVICE_CODENAME}-${STAMP}.img"
cp "$UNPACK_DIR/new-boot.img" "$PATCHED_IMG"
log "Patched image: $PATCHED_IMG"

# --- AVB / vbmeta note ---
log ""
log "NOTE: If the device enforces AVB (Verified Boot), you may need to"
log "disable verification before flashing:"
log "  fastboot flash vbmeta --disable-verity --disable-verification vbmeta.img"
log "  fastboot reboot bootloader"
log "Then flash the patched boot:"
log "  fastboot flash boot $PATCHED_IMG"
log "  fastboot reboot"
log ""
log "Or flash directly if your device's bootloader is already unlocked and"
log "AVB enforcement is already off:"
log "  fastboot flash boot $PATCHED_IMG && fastboot reboot"

# Victorious Framework
