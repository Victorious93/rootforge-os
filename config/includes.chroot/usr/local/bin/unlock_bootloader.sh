#!/usr/bin/env bash
# RootForge OS — bootloader unlock automation
# Victorious Framework
#
# Detects the connected device's vendor via fastboot getvar and runs the
# correct unlock command for AOSP-standard bootloaders. Refuses to guess
# on vendors that require an out-of-band tool (Samsung Download Mode /
# Odin, Xiaomi Mi Unlock permit flow, etc.) rather than failing silently.
#
# Usage: ./unlock_bootloader.sh [device-serial]

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

LOG_DIR="${ROOTFORGE_HOME:-$HOME/rootforge}/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/unlock_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[unlock] $*" | tee -a "$LOG_FILE"; }

SERIAL="${1:-}"
FASTBOOT="fastboot"
[[ -n "$SERIAL" ]] && FASTBOOT="fastboot -s $SERIAL"

log "Waiting for device in fastboot mode..."
$FASTBOOT wait-for-device

log "Reading device variables"
VARS="$($FASTBOOT getvar all 2>&1 || true)"
echo "$VARS" >> "$LOG_FILE"

# Several bootloaders terminate getvar lines with CRLF, so an untrimmed
# value never compares equal to "yes" below and an already-unlocked device
# gets walked through the unlock prompt again. Strip CRs and surrounding
# whitespace. `grep -m1` rather than `| head -1` also avoids handing grep a
# SIGPIPE, which `pipefail` would turn into a spurious failure.
PRODUCT="$(echo "$VARS" | grep -m1 -oP '(?<=product: ).*' | tr -d '\r' | xargs || true)"
UNLOCK_ABILITY="$(echo "$VARS" | grep -m1 -oP '(?<=unlocked: ).*' | tr -d '\r' | xargs || true)"

log "Detected product: ${PRODUCT:-unknown}"

# Vendor heuristics — fastboot getvar output and known bootloader strings
IS_SAMSUNG=0
IS_XIAOMI=0
echo "$VARS" | grep -qi "samsung" && IS_SAMSUNG=1
echo "$VARS" | grep -qiE "xiaomi|redmi" && IS_XIAOMI=1

if [[ $IS_SAMSUNG -eq 1 ]]; then
  log "REFUSING to proceed automatically: Samsung devices unlock OEM bootloader in Settings > Developer Options > OEM Unlocking, then flash via Download Mode with Odin/Heimdall — not fastboot. Automating this risks tripping Knox permanently with no rollback. See devices/<codename>/hardware-notes.md."
  exit 2
fi

if [[ $IS_XIAOMI -eq 1 ]]; then
  log "REFUSING to proceed automatically: Xiaomi/Redmi devices require a Mi Unlock permit tied to your Mi account, with a vendor-enforced waiting period (often 7+ days for new accounts). Complete that via the official Mi Unlock tool first; this script can flash afterward once fastboot reports unlocked: yes."
  exit 2
fi

if [[ "$UNLOCK_ABILITY" == "yes" ]]; then
  log "Device already reports unlocked: yes — nothing to do."
  exit 0
fi

# rf_confirm prompts on /dev/tty, so the gate stays visible when this script
# is driven by fleet_orchestrate.sh with stdout redirected to a log.
if ! rf_confirm UNLOCK \
    "Device: ${PRODUCT:-unknown}${SERIAL:+ (serial $SERIAL)}" \
    "This device appears to use a standard AOSP-lineage bootloader (Pixel/Nexus/OnePlus-class)." \
    "Unlocking WILL WIPE ALL USER DATA on the device. This is a firmware-enforced behavior, not a script choice."; then
  log "Confirmation not given — aborting."
  exit 1
fi

log "Attempting standard unlock sequence"
if $FASTBOOT flashing unlock 2>>"$LOG_FILE"; then
  log "flashing unlock issued — confirm on-device prompt with volume/power keys."
elif $FASTBOOT oem unlock 2>>"$LOG_FILE"; then
  log "oem unlock issued (legacy bootloader path) — confirm on-device prompt."
else
  log "Both 'fastboot flashing unlock' and 'fastboot oem unlock' failed. This device may need OEM unlocking enabled in Developer Options first, or use a vendor-specific tool. See log: $LOG_FILE"
  exit 1
fi

log "Waiting for device to reboot post-unlock..."
$FASTBOOT wait-for-device || true
log "Unlock sequence complete. Verify with: fastboot getvar unlocked"

# Victorious Framework
