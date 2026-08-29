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

# Shared helpers. The installed layout puts this script in /usr/local/bin
# with the library at /usr/local/lib/rootforge/sh — the same relative
# arrangement a repo checkout has under config/includes.chroot, so one
# relative path serves both.
# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

usage() {
  echo "Usage: flash_patched_boot.sh <patched_image.img> [boot|init_boot] [--both-slots] [serial]" >&2
  exit "${1:-1}"
}

# `shift 2` was previously used to skip past the image and partition
# arguments, guarded with `|| true` so a one-argument invocation wouldn't
# abort. That guard turned two argument shapes into silent misconfiguration:
#
#   flash_patched_boot.sh boot.img
#     shift 2 fails, "$@" still holds boot.img, the loop below falls into
#     its catch-all and sets SERIAL=boot.img -> `fastboot -s boot.img`.
#   flash_patched_boot.sh boot.img --both-slots
#     PARTITION="${2:-boot}" captured the flag, so the script flashed a
#     partition literally named "--both-slots" and never mirrored slots.
#
# Parse positionally instead, and validate the partition name rather than
# accepting whatever landed in $2.
[[ $# -ge 1 ]] || usage
case "$1" in
  -h|--help) usage 0 ;;
esac

IMG="$1"; shift
PARTITION="boot"
BOTH_SLOTS=0
SERIAL=""

# An optional partition name may follow the image, but only if it is
# actually a partition name — anything starting with '-' is a flag.
if [[ $# -gt 0 && "$1" != -* ]]; then
  PARTITION="$1"
  shift
fi

case "$PARTITION" in
  boot|init_boot) ;;
  *) echo "Unsupported partition '$PARTITION' (expected boot or init_boot)." >&2; usage ;;
esac

while [[ $# -gt 0 ]]; do
  case "$1" in
    --both-slots) BOTH_SLOTS=1 ;;
    -h|--help) usage 0 ;;
    -*) echo "Unknown option: $1" >&2; usage ;;
    *)
      [[ -z "$SERIAL" ]] || { echo "Serial given twice: '$SERIAL' and '$1'" >&2; usage; }
      SERIAL="$1"
      ;;
  esac
  shift
done

[[ -f "$IMG" ]] || { echo "Image not found: $IMG" >&2; exit 1; }
[[ -s "$IMG" ]] || { echo "Image is empty: $IMG" >&2; exit 1; }

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
# rf_confirm prompts on /dev/tty rather than stdout: fleet_orchestrate.sh
# redirects this script's stdout into a per-device log, and a `read -r -p`
# prompt written there is invisible to the operator — the run just looks
# hung. See usr/local/lib/rootforge/sh/common.sh.
if ! rf_confirm FLASH \
    "This overwrites the $PARTITION partition on the device now connected in fastboot mode."; then
  log "Confirmation not given — aborting. Nothing was flashed."
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

    # From here until the active slot is switched back, an abort would
    # leave the device booting the *other* slot — which at this point may
    # still hold a half-written image. `set -e` would do exactly that, so
    # handle the failure explicitly and always restore the original slot.
    MIRROR_STATUS=0
    $FASTBOOT flash "$PARTITION" "$IMG" 2>>"$LOG_FILE" || MIRROR_STATUS=$?

    log "Restoring active slot to $CURRENT_SLOT"
    $FASTBOOT --set-active="$CURRENT_SLOT" 2>>"$LOG_FILE"

    if [[ $MIRROR_STATUS -ne 0 ]]; then
      log "Mirrored flash to slot $OTHER_SLOT FAILED (exit $MIRROR_STATUS)."
      log "Active slot $CURRENT_SLOT was flashed successfully and is still active, so the"
      log "device should boot — but slot $OTHER_SLOT is now in an unknown state. Re-run with"
      log "--both-slots once the failure in $LOG_FILE is resolved, before taking an OTA."
      exit "$MIRROR_STATUS"
    fi
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
