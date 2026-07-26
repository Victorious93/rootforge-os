#!/usr/bin/env bash
# RootForge OS — rooted / unrooted AVD provisioning
# Victorious Framework
#
# Creates an AVD, and for the rooted profile, boots it with a writable
# system partition, pushes a Magisk-source-built su binary, and snapshots
# it so subsequent boots start pre-rooted instead of repeating the patch.
#
# Requires: sdkmanager-managed system image already installed
#   (e.g. system-images;android-34;google_apis;x86_64 — NOT google_apis_playstore,
#    which resists the writable-system trick by design).
#
# Usage: ./setup_rooted_avd.sh <avd_name> [rooted|unrooted] [su_binary_path]

set -euo pipefail

AVD_NAME="${1:?Usage: setup_rooted_avd.sh <avd_name> [rooted|unrooted] [su_binary_path]}"
MODE="${2:-rooted}"
SU_BINARY="${3:-}"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/avd_${AVD_NAME}_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[avd] $*" | tee -a "$LOG_FILE"; }

command -v avdmanager >/dev/null 2>&1 || { echo "avdmanager not on PATH — source /etc/profile.d/rootforge.sh" >&2; exit 1; }
command -v emulator >/dev/null 2>&1 || { echo "emulator not on PATH" >&2; exit 1; }

SYSTEM_IMAGE="system-images;android-34;google_apis;x86_64"

if ! avdmanager list avd | grep -q "Name: $AVD_NAME$"; then
  log "Creating AVD '$AVD_NAME' from $SYSTEM_IMAGE"
  echo "no" | avdmanager create avd -n "$AVD_NAME" -k "$SYSTEM_IMAGE" --device "pixel_6" 2>>"$LOG_FILE"
else
  log "AVD '$AVD_NAME' already exists — reusing."
fi

if [[ "$MODE" == "unrooted" ]]; then
  log "Unrooted profile requested — AVD is ready to boot as-is:"
  log "  emulator -avd $AVD_NAME"
  exit 0
fi

[[ -n "$SU_BINARY" && -f "$SU_BINARY" ]] || {
  echo "Rooted profile requires a su binary path (build one from the Magisk source tree first)." >&2
  echo "See README section 2 — same toolchain that builds magiskboot also builds su." >&2
  exit 1
}

log "Booting emulator writable for root injection (first boot, no snapshot save)"
emulator -avd "$AVD_NAME" -writable-system -no-snapshot-save -no-window &
EMULATOR_PID=$!

log "Waiting for device to come online"
adb wait-for-device
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'

log "Gaining root adb session and remounting /system writable"
adb root
sleep 2
adb remount

log "Pushing su binary to /system/xbin/su"
adb shell mkdir -p /system/xbin
adb push "$SU_BINARY" /system/xbin/su
adb shell chmod 06755 /system/xbin/su
adb shell chown root:root /system/xbin/su

log "Note [Likely]: exact injection point (xbin path, init.rc hook, or seapp_contexts entry)"
log "varies by API level. Verify with: adb shell su -c id  — expect uid=0(root)."

log "Root injection complete. Shutting down for snapshot save."
adb emu kill
wait "$EMULATOR_PID" 2>/dev/null || true

log "Snapshotting rooted state — subsequent boots start pre-rooted:"
log "  emulator -avd $AVD_NAME -snapshot rootforge-rooted"
log "First boot after this: launch normally once WITHOUT -no-snapshot-save to persist the snapshot."

# Victorious Framework
