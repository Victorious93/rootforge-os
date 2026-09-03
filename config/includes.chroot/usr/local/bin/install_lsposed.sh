#!/usr/bin/env bash
# RootForge OS — LSPosed installer
# Victorious Framework
#
# LSPosed ships itself as a Magisk/KernelSU module (a Zygisk-based Xposed
# framework reimplementation). This script fetches the latest release from
# the LSPosed GitHub repo, pushes it, and installs it the same way
# build_magisk_module.sh installs any other module — via the framework CLI.
#
# Requires Zygisk enabled (Magisk Settings > Zygisk, or zygisksu for KernelSU).
#
# Usage: ./install_lsposed.sh [--framework magisk|kernelsu] [device-serial]

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

usage() {
  echo "Usage: install_lsposed.sh [--framework magisk|kernelsu] [device-serial]" >&2
}

# The previous parser had all three of the failure modes these scripts keep
# re-growing, and each was reproduced before this rewrite:
#
#   --framework            "$2" under set -u -> "line 20: $2: unbound variable"
#   --frmework kernelsu    the catch-all arm took the typo'd FLAG as a serial,
#                          then "kernelsu" replaced it. Result: adb -s kernelsu
#                          against a serial that does not exist, the framework
#                          silently left at magisk, and exit 0.
#   --framework bogus      validated only at the very end, so the zip was
#                          downloaded and pushed to /data/local/tmp first.
#
# The last one is why validation moved above the network and device work: an
# argument error should cost nothing and leave nothing behind on the device.
FRAMEWORK="magisk"
SERIAL=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --framework)
      [[ $# -ge 2 ]] || { echo "--framework needs a value (magisk|kernelsu)" >&2; usage; exit 1; }
      FRAMEWORK="$2"; shift 2 ;;
    --framework=*) FRAMEWORK="${1#*=}"; shift ;;
    -h|--help) usage; exit 0 ;;
    -*)
      echo "Unknown option: $1" >&2; usage; exit 1 ;;
    *)
      [[ -z "$SERIAL" ]] || { echo "Unexpected extra argument: $1" >&2; usage; exit 1; }
      SERIAL="$1"; shift ;;
  esac
done

case "$FRAMEWORK" in
  magisk|kernelsu) ;;
  *) echo "Unknown framework: $FRAMEWORK (expected magisk|kernelsu)" >&2; exit 1 ;;
esac

ADB="adb"
[[ -n "$SERIAL" ]] && ADB="adb -s $SERIAL"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
CACHE_DIR="$ROOTFORGE_HOME/modules/.cache"
mkdir -p "$LOG_DIR" "$CACHE_DIR"
LOG_FILE="$LOG_DIR/lsposed_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[lsposed] $*" | tee -a "$LOG_FILE"; }

command -v jq >/dev/null 2>&1 || { echo "jq is required (apt install jq)" >&2; exit 1; }

log "Querying latest LSPosed release from GitHub"
RELEASE_JSON="$(curl -fsSL https://api.github.com/repos/LSPosed/LSPosed/releases/latest)"
# LSPosed publishes several zips per release — zygisk and riru variants,
# debug and release builds. The old selection took whichever the API happened
# to list first, so which flavour got installed was not a decision anyone
# made. Prefer a zygisk release build, fall back to the first zip, and always
# log the choice alongside the alternatives so a wrong one is visible.
#
# `jq ... | head -1` also handed jq a SIGPIPE once the output outgrew the pipe
# buffer, which pipefail turns into a failed assignment and set -e turns into
# a silent exit. `first(...)` inside jq removes the pipe entirely.
ASSET_SELECT='[.assets[] | select(.name | test("zip$"))]'
ASSET_JSON="$(echo "$RELEASE_JSON" | jq -c "
  $ASSET_SELECT as \$z
  | (first(\$z[] | select(.name | test(\"zygisk\") and test(\"release\")))
     // first(\$z[] | select(.name | test(\"zygisk\")))
     // \$z[0])
  // empty")"
ASSET_URL="$(echo "$ASSET_JSON" | jq -r '.browser_download_url // empty')"
ASSET_NAME="$(echo "$ASSET_JSON" | jq -r '.name // empty')"

if [[ -z "$ASSET_URL" || "$ASSET_URL" == "null" ]]; then
  log "Could not resolve a release asset from the GitHub API response. Rate-limited or repo layout changed — check $LOG_FILE and https://github.com/LSPosed/LSPosed/releases manually."
  echo "$RELEASE_JSON" >> "$LOG_FILE"
  exit 1
fi

log "Selected asset: $ASSET_NAME"
ALTERNATIVES="$(echo "$RELEASE_JSON" | jq -r "$ASSET_SELECT | .[].name" | grep -Fxv "$ASSET_NAME" || true)"
[[ -n "$ALTERNATIVES" ]] && log "Other zips in this release (not installed): $(echo "$ALTERNATIVES" | tr '\n' ' ')"

# rf_download_cached downloads to a temp file and renames on success, and
# treats an implausibly small cached file as absent. Writing straight to the
# cache path meant an interrupted download stayed there forever and every
# later run logged "Using cached" and pushed the truncated zip to the device.
LOCAL_ZIP="$CACHE_DIR/$ASSET_NAME"
log "Fetching $ASSET_NAME (reusing the cached copy if it is complete)"
rf_download_cached "$ASSET_URL" "$LOCAL_ZIP" 65536 || {
  log "Could not obtain a usable $ASSET_NAME — nothing was pushed to the device."
  exit 1
}

$ADB wait-for-device
DEVICE_PATH="/data/local/tmp/$ASSET_NAME"
log "Pushing to $DEVICE_PATH"
$ADB push "$LOCAL_ZIP" "$DEVICE_PATH" 2>>"$LOG_FILE"

case "$FRAMEWORK" in
  magisk)
    log "Installing via Magisk CLI — Zygisk must already be enabled in Magisk settings"
    $ADB shell su -c "magisk --install-module $DEVICE_PATH" 2>>"$LOG_FILE"
    ;;
  kernelsu)
    log "Installing via KernelSU CLI — requires the zygisksu overlay for Zygisk support"
    $ADB shell su -c "ksud module install $DEVICE_PATH" 2>>"$LOG_FILE"
    ;;
  *)
    # Unreachable: FRAMEWORK is validated before any download or push.
    echo "Unknown framework: $FRAMEWORK (expected magisk|kernelsu)" >&2
    exit 1
    ;;
esac

log "LSPosed module installed. Reboot the device, then open the LSPosed manager app"
log "to enable individual Xposed modules per-app (LSPosed installs disabled by default)."
log "adb shell su -c 'svc power reboot' or: $ADB reboot"

# Victorious Framework
