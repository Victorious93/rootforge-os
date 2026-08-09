#!/usr/bin/env bash
# RootForge OS — patched boot image flasher
# Victorious Framework
#
# Flashes a Magisk- or KernelSU-patched boot.img / init_boot.img to the
# active slot, with an A/B-aware option to mirror the flash to the other
# slot for OTA-safety. Keeps the original stock image path logged so a
# revert is a one-line command away.
#
# Usage: ./flash_patched_boot.sh <patched_image.img> [boot|init_boot] [--both-slots] [device-serial]

set -euo pipefail

IMG="${1:?Usage: flash_patched_boot.sh <patched_image.img> [boot|init_boot] [--both-slots] [serial]}"
PARTITION="${2:-boot}"
BOTH_SLOTS=0
SERIAL=""

shift 2 || true
for arg in "$@"; do
  case "$arg" in
    --both-slots) BOTH_SLOTS=1 ;;
    *) SERIAL="$arg" ;;
  esac
done

[[ -f "$IMG" ]] || { echo "Image not found: $IMG" >&2; exit 1; }

FASTBOOT="fastboot"
[[ -n "$SERIAL" ]] && FASTBOOT="fastboot -s $SERIAL"

LOG_DIR="${ROOTFORGE_HOME:-$HOME/rootforge}/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/flash_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[flash] $*" | tee -a "$LOG_FILE"; }

log "Target partition: $PARTITION"
log "Image: $IMG"

$FASTBOOT wait-for-device

CURRENT_SLOT="$($FASTBOOT getvar current-slot 2>&1 | grep -oP '(?<=current-slot: ).*' || true)"
PRODUCT="$($FASTBOOT getvar product 2>&1 | grep -oP '(?<=product: ).*' || echo unknown)"

log "About to flash:"
log "  Device:     ${SERIAL:-$PRODUCT}"
log "  Partition:  $PARTITION"
log "  Image:      $IMG"
if [[ -n "$CURRENT_SLOT" ]]; then
  log "  Slot:       $CURRENT_SLOT$([[ $BOTH_SLOTS -eq 1 ]] && echo " (and mirroring to the other slot)")"
fi
log "This overwrites the $PARTITION partition on the device now connected in fastboot mode."
read -r -p "Type FLASH to proceed: " CONFIRM
if [[ "$CONFIRM" != "FLASH" ]]; then
  log "Confirmation not given (got: '${CONFIRM:-<empty>}') — aborting. Nothing was flashed."
  exit 1
fi
log "Confirmed by operator — proceeding with flash."

if [[ -n "$CURRENT_SLOT" ]]; then
  log "Device uses A/B slots. Current active slot: $CURRENT_SLOT"
  log "Flashing $PARTITION to active slot"
  $FASTBOOT flash "$PARTITION" "$IMG" 2>>"$LOG_FILE"

  if [[ $BOTH_SLOTS -eq 1 ]]; then
    OTHER_SLOT="b"
    [[ "$CURRENT_SLOT" == "b" ]] && OTHER_SLOT="a"
    log "Mirroring flash to slot $OTHER_SLOT for OTA safety"
    $FASTBOOT --set-active="$OTHER_SLOT" 2>>"$LOG_FILE"
    $FASTBOOT flash "$PARTITION" "$IMG" 2>>"$LOG_FILE"
    $FASTBOOT --set-active="$CURRENT_SLOT" 2>>"$LOG_FILE"
  fi
else
  log "No A/B slot reported — single-partition device. Flashing directly."
  $FASTBOOT flash "$PARTITION" "$IMG" 2>>"$LOG_FILE"
fi

log "Flash complete. Rebooting."
$FASTBOOT reboot

log "Waiting for adb to come back online to confirm boot succeeded..."
if command -v adb >/dev/null 2>&1; then
  adb wait-for-device shell getprop ro.build.fingerprint 2>>"$LOG_FILE" || \
    log "adb didn't come back within default timeout — check the device manually before assuming success."
fi

log "Done. Keep the original stock $PARTITION.img path noted in devices/<codename>/ for a fast revert via this same script."

# Victorious Framework
