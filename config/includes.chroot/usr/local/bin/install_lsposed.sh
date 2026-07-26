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

FRAMEWORK="magisk"
SERIAL=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --framework) FRAMEWORK="$2"; shift ;;
    *) SERIAL="$1" ;;
  esac
  shift
done

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
RELEASE_JSON="$(curl -sSL https://api.github.com/repos/LSPosed/LSPosed/releases/latest)"
ASSET_URL="$(echo "$RELEASE_JSON" | jq -r '.assets[] | select(.name | test("zip$")) | .browser_download_url' | head -1)"
ASSET_NAME="$(echo "$RELEASE_JSON" | jq -r '.assets[] | select(.name | test("zip$")) | .name' | head -1)"

if [[ -z "$ASSET_URL" || "$ASSET_URL" == "null" ]]; then
  log "Could not resolve a release asset from the GitHub API response. Rate-limited or repo layout changed — check $LOG_FILE and https://github.com/LSPosed/LSPosed/releases manually."
  echo "$RELEASE_JSON" >> "$LOG_FILE"
  exit 1
fi

LOCAL_ZIP="$CACHE_DIR/$ASSET_NAME"
if [[ -f "$LOCAL_ZIP" ]]; then
  log "Using cached $ASSET_NAME"
else
  log "Downloading $ASSET_NAME"
  curl -sSL -o "$LOCAL_ZIP" "$ASSET_URL"
fi

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
    echo "Unknown framework: $FRAMEWORK (expected magisk|kernelsu)" >&2
    exit 1
    ;;
esac

log "LSPosed module installed. Reboot the device, then open the LSPosed manager app"
log "to enable individual Xposed modules per-app (LSPosed installs disabled by default)."
log "adb shell su -c 'svc power reboot' or: $ADB reboot"

# Victorious Framework
