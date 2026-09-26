#!/usr/bin/env bash
# RootForge OS — module build, deploy, and test-loop automation
# Victorious Framework
#
# Zips a module directory correctly (no parent-folder nesting, which is
# the #1 reason a manually-zipped module fails to install), pushes it to
# a connected device or emulator, and installs it via the framework's
# CLI so you're not tapping through the manager app UI every iteration.
#
# Usage: ./build_magisk_module.sh <module_id> [--install] [--framework magisk|kernelsu] [--serial <device-serial>]

set -euo pipefail

MODULE_ID="${1:?Usage: build_magisk_module.sh <module_id> [--install] [--framework magisk|kernelsu]}"
shift || true

DO_INSTALL=0
FRAMEWORK="magisk"
SERIAL=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --install) DO_INSTALL=1 ;;
    --framework)
      [[ $# -ge 2 ]] || { echo "--framework needs a value (magisk|kernelsu)" >&2; exit 1; }
      FRAMEWORK="$2"; shift
      ;;
    # Without a serial, every adb call here targets "the" device and fails
    # with "more than one device/emulator" as soon as a second one is
    # attached — which is exactly what fleet_orchestrate.sh does.
    --serial)
      [[ $# -ge 2 ]] || { echo "--serial needs a device serial" >&2; exit 1; }
      SERIAL="$2"; shift
      ;;
    -*) echo "Unknown option: $1" >&2; exit 1 ;;
    *) echo "Unexpected argument: $1" >&2; exit 1 ;;
  esac
  shift
done

# Validate before doing any work rather than after the zip is already built.
case "$FRAMEWORK" in
  magisk|kernelsu) ;;
  *) echo "Unknown framework: $FRAMEWORK (expected magisk|kernelsu)" >&2; exit 1 ;;
esac

ADB=(adb)
[[ -n "$SERIAL" ]] && ADB=(adb -s "$SERIAL")

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
"${ADB[@]}" wait-for-device
DEVICE_PATH="/data/local/tmp/${MODULE_ID}-build.zip"
log "Pushing to $DEVICE_PATH${SERIAL:+ (device $SERIAL)}"
"${ADB[@]}" push "$OUT_ZIP" "$DEVICE_PATH" 2>>"$LOG_FILE"

# The install command's failure used to be logged and then discarded, so a
# module that never installed still exited 0 — and fleet_orchestrate.sh
# recorded "OK" for it.
INSTALL_STATUS=0
case "$FRAMEWORK" in
  magisk)
    log "Installing via Magisk CLI"
    "${ADB[@]}" shell su -c "magisk --install-module $DEVICE_PATH" 2>>"$LOG_FILE" || INSTALL_STATUS=$?
    [[ $INSTALL_STATUS -eq 0 ]] || \
      log "magisk --install-module failed — confirm the device has Magisk's magisk32/64 binary in PATH under su."
    ;;
  kernelsu)
    log "Installing via KernelSU daemon CLI (ksud)"
    "${ADB[@]}" shell su -c "ksud module install $DEVICE_PATH" 2>>"$LOG_FILE" || INSTALL_STATUS=$?
    [[ $INSTALL_STATUS -eq 0 ]] || \
      log "ksud module install failed — confirm ksud is present and the kernel has KernelSU built in."
    ;;
esac

# Leave nothing behind on the device; a stale zip in /data/local/tmp is easy
# to mistake for the current build on the next iteration.
"${ADB[@]}" shell rm -f "$DEVICE_PATH" 2>>"$LOG_FILE" || true

if [[ $INSTALL_STATUS -ne 0 ]]; then
  log "Module install FAILED (exit $INSTALL_STATUS) — see $LOG_FILE."
  exit "$INSTALL_STATUS"
fi

log "Install command issued. Reboot to apply, then tail logs with:"
log "  adb${SERIAL:+ -s $SERIAL} logcat -s Magisk:* KernelSU:*"

# Victorious Framework
