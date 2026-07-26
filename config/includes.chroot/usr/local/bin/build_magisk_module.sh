#!/usr/bin/env bash
# RootForge OS — module build, deploy, and test-loop automation
# Victorious Framework
#
# Zips a module directory correctly (no parent-folder nesting, which is
# the #1 reason a manually-zipped module fails to install), pushes it to
# a connected device or emulator, and installs it via the framework's
# CLI so you're not tapping through the manager app UI every iteration.
#
# Usage: ./build_magisk_module.sh <module_id> [--install] [--framework magisk|kernelsu]

set -euo pipefail

MODULE_ID="${1:?Usage: build_magisk_module.sh <module_id> [--install] [--framework magisk|kernelsu]}"
shift || true

DO_INSTALL=0
FRAMEWORK="magisk"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --install) DO_INSTALL=1 ;;
    --framework) FRAMEWORK="$2"; shift ;;
  esac
  shift
done

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
MODULE_DIR="$ROOTFORGE_HOME/modules/$MODULE_ID"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/build_${MODULE_ID}_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[build] $*" | tee -a "$LOG_FILE"; }

[[ -d "$MODULE_DIR" ]] || { echo "No module at $MODULE_DIR — run new_module_scaffold.sh first." >&2; exit 1; }
[[ -f "$MODULE_DIR/module.prop" ]] || { echo "Missing module.prop in $MODULE_DIR" >&2; exit 1; }

OUT_ZIP="$ROOTFORGE_HOME/modules/${MODULE_ID}-build.zip"
log "Packaging $MODULE_ID -> $OUT_ZIP"
rm -f "$OUT_ZIP"
( cd "$MODULE_DIR" && zip -r -q "$OUT_ZIP" . -x "*.git*" )
log "Package size: $(du -h "$OUT_ZIP" | cut -f1)"

if [[ $DO_INSTALL -eq 0 ]]; then
  log "Build-only run complete. Pass --install to push + install on a connected device."
  exit 0
fi

command -v adb >/dev/null 2>&1 || { echo "adb not found in PATH" >&2; exit 1; }
adb wait-for-device
DEVICE_PATH="/data/local/tmp/${MODULE_ID}-build.zip"
log "Pushing to $DEVICE_PATH"
adb push "$OUT_ZIP" "$DEVICE_PATH" 2>>"$LOG_FILE"

case "$FRAMEWORK" in
  magisk)
    log "Installing via Magisk CLI"
    adb shell su -c "magisk --install-module $DEVICE_PATH" 2>>"$LOG_FILE" || \
      log "magisk --install-module failed — confirm the device has Magisk's magisk32/64 binary in PATH under su."
    ;;
  kernelsu)
    log "Installing via KernelSU daemon CLI (ksud)"
    adb shell su -c "ksud module install $DEVICE_PATH" 2>>"$LOG_FILE" || \
      log "ksud module install failed — confirm ksud is present and the kernel has KernelSU built in."
    ;;
  *)
    echo "Unknown framework: $FRAMEWORK (expected magisk|kernelsu)" >&2
    exit 1
    ;;
esac

log "Install command issued. Reboot to apply, then tail logs with:"
log "  adb logcat -s Magisk:* KernelSU:*"

# Victorious Framework
