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
#   kernelsu_patch_boot.sh --flash [--serial <serial>]
#                                     # flash the last patched image
#                                     # (through flash_patched_boot.sh)
#
# Requirements: adb, fastboot, curl, python3 (for avbtool), magiskboot
#               (magiskboot is built by 00_bootstrap_distro.sh)

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

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
SERIAL=""

# Each value-taking option needs its argument checked before it is read:
# under `set -u` a bare trailing `--stock-boot` aborted with the raw
# "$2: unbound variable" instead of saying what was actually wrong.
need_value() {
  [[ $2 -ge 2 ]] || die "$1 requires a value"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --stock-boot)      need_value "$1" $#; STOCK_BOOT="$2";      shift 2 ;;
    --android-version) need_value "$1" $#; ANDROID_VER="$2";     shift 2 ;;
    --ksu-version)     need_value "$1" $#; KSU_VERSION="$2";     shift 2 ;;
    --device)          need_value "$1" $#; DEVICE_CODENAME="$2"; shift 2 ;;
    --serial)          need_value "$1" $#; SERIAL="$2";          shift 2 ;;
    --flash)           FLASH_ONLY=1;                             shift   ;;
    -h|--help)
      sed -n '12,20p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
      exit 0
      ;;
    *) die "Unknown argument: $1" ;;
  esac
done

# --- Flash-only path ---
if [[ $FLASH_ONLY -eq 1 ]]; then
  # `LATEST=$(ls -t ... | head -1)` aborted the script here under
  # `set -o pipefail` whenever the glob matched nothing: ls exits non-zero,
  # pipefail propagates it through head, and the failing assignment tripped
  # `set -e` *before* the die() below could explain anything. The user got a
  # silent exit 2. Collect matches with a glob instead, which cannot fail.
  shopt -s nullglob
  CANDIDATES=("$WORK_DIR"/boot-ksu-patched-*.img)
  shopt -u nullglob
  [[ ${#CANDIDATES[@]} -gt 0 ]] || die "No patched boot image found in $WORK_DIR — run without --flash first to build one."

  LATEST=""
  for candidate in "${CANDIDATES[@]}"; do
    [[ -z "$LATEST" || "$candidate" -nt "$LATEST" ]] && LATEST="$candidate"
  done

  [[ -z "$SERIAL" || "$SERIAL" =~ ^[A-Za-z0-9._:-]+$ ]] \
    || die "--serial does not look like a device serial (expected [A-Za-z0-9._:-], got '$SERIAL')"
  rf_require_cmd fastboot "install the fastboot package (android-sdk-platform-tools-common / platform-tools)"

  # Writing the boot partition goes through flash_patched_boot.sh and nothing
  # else: it selects exactly one device, checks that the write is allowed
  # (unlocked, known identity, partition present and large enough), asks for
  # the typed confirmation of the displayed plan, re-checks, writes, and
  # verifies boot. A second, thinner copy of that logic here used to issue a
  # bare `fastboot flash boot` against whatever device fastboot picked.
  FLASH_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/flash_patched_boot.sh"
  FLASH_ARGS=("$LATEST" boot)
  [[ -n "$SERIAL" ]] && FLASH_ARGS+=("$SERIAL")
  log "Flashing most recent patched image via flash_patched_boot.sh: $LATEST"
  exec bash "$FLASH_SCRIPT" "${FLASH_ARGS[@]}"
fi

# --- Validation ---
# KSU_VERSION is interpolated into a GitHub API URL path:
#
#   curl -fsSL "https://api.github.com/repos/tiann/KernelSU/releases/tags/$KSU_TAG"
#
# curl resolves ../ segments in a URL path before sending the request — RFC
# 3986 remove_dot_segments — so an unvalidated tag redirects the query to any
# repository. Verified against the real API:
#
#   --ksu-version '../../../../octocat/Hello-World/releases/latest'
#     > GET /repos/octocat/Hello-World/releases/latest HTTP/1.1
#
# The query has left tiann/KernelSU entirely. What comes back names an asset,
# that asset is downloaded, and it is written in as the KERNEL of a boot image
# the user then flashes. A release tag is [A-Za-z0-9._-]; nothing legitimate
# needs a slash.
[[ "$KSU_VERSION" =~ ^[A-Za-z0-9._-]+$ ]] \
  || die "--ksu-version must be a release tag like v0.9.5 or 'latest' (got '$KSU_VERSION')"

# DEVICE_CODENAME becomes part of the output filename. It is not a traversal —
# a name containing '/' just makes a path whose parent does not exist — but it
# failed at the very last step, with a raw cp error, after the kernel had been
# downloaded and magiskboot had run twice. Say so before any of that happens.
[[ "$DEVICE_CODENAME" =~ ^[A-Za-z0-9._-]+$ ]] \
  || die "--device must be [A-Za-z0-9._-] (got '$DEVICE_CODENAME') — it becomes part of the output filename"

[[ -z "$STOCK_BOOT" ]]   && die "--stock-boot <boot.img> required"
[[ -z "$ANDROID_VER" ]]  && die "--android-version <12|13|14> required"
[[ -f "$STOCK_BOOT" ]]   || die "Boot image not found: $STOCK_BOOT"
[[ -s "$STOCK_BOOT" ]]   || die "Boot image is empty: $STOCK_BOOT"
# ANDROID_VER is interpolated into the release-asset name match below, so
# keep it to the digits that convention actually uses.
[[ "$ANDROID_VER" =~ ^[0-9]{1,2}$ ]] || die "--android-version must be a number like 12, 13 or 14 (got '$ANDROID_VER')"

rf_require_cmd curl "install curl"

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
# curl wrote straight to $KSU_IMAGE, and nothing checked what landed. A
# download cut short by a dropped connection or a full disk left a truncated
# file that was copied in as the kernel and repacked — verified: a 12-byte
# "kernel" produced a patched boot image with no complaint, and the script
# printed "Patched image: ..." as if nothing were wrong. Flashing that leaves
# the device unbootable until it is reflashed, which is the one outcome this
# whole script exists to avoid.
#
# A GKI Image.gz is several megabytes. 1 MB is far below any real one and far
# above an error page.
rf_download_cached "$ASSET_URL" "$KSU_IMAGE" 1048576 \
  || die "Could not download a usable KernelSU kernel image — nothing was patched."

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
log "Then flash the patched boot with the checked flasher (it identifies the device,"
log "confirms the plan with you, and verifies the boot afterwards):"
log "  kernelsu_patch_boot.sh --flash        (or: rootforge boot flash-last)"

# Victorious Framework
