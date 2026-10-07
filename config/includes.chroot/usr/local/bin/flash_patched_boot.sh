#!/usr/bin/env bash
# RootForge OS — patched boot image flasher
# Victorious Framework
#
# Writes a Magisk- or KernelSU-patched boot.img / init_boot.img to one
# fastboot device, after the device has been identified and checked.
#
# Usage:
#   flash_patched_boot.sh <patched_image.img> [boot|init_boot]
#       [--both-slots --slots-same-build] [--no-boot-check] [serial]
#
# What happens, in order — nothing is written before step 3 passes:
#   1. Arguments and the image are validated (Android boot image header).
#   2. Exactly one fastboot device is selected and its serial is kept for the
#      rest of the run. Two devices with no serial is an error, not a guess.
#   3. `rootforge device check` must allow the write: fastboot mode, a probe
#      that returned data, a known identity, an UNLOCKED bootloader, a known
#      slot layout, the target partition present and large enough. Missing
#      evidence blocks the write (exit 3). Read-only commands stay usable.
#   4. The operator confirms the displayed plan by typing FLASH <serial>.
#   5. The device is re-checked and must be the same one, then written.
#   6. The device is rebooted and, unless --no-boot-check, watched until it
#      reports sys.boot_completed=1.
#
# The active slot is never changed. Slots are targeted explicitly with
# `fastboot --slot`. --both-slots writes the SAME image to both slots, which
# is only correct if both slots hold the same build; RootForge cannot verify
# that, so --slots-same-build must be passed to say you did. It is not an
# "OTA safety" feature: after an OTA the inactive slot holds a different
# build and must not carry an image patched from the old one.
#
# Exit codes: 0 written and boot verified | 1 usage/write failure |
#   3 blocked by validation (nothing written) | 4 written, boot NOT verified |
#   5 written, reboot request failed | 130 interrupted during a write.

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

EXIT_BLOCKED=3
EXIT_BOOT_UNVERIFIED=4
EXIT_REBOOT_FAILED=5

usage() {
  echo "Usage: flash_patched_boot.sh <patched_image.img> [boot|init_boot] [--both-slots --slots-same-build] [--no-boot-check] [serial]" >&2
  exit "${1:-1}"
}

# Parse positionally: the image, an optional partition name (never a flag),
# then flags and at most one serial.
[[ $# -ge 1 ]] || usage
case "$1" in
  -h|--help) usage 0 ;;
esac

IMG="$1"; shift
PARTITION="boot"
BOTH_SLOTS=0
SLOTS_SAME_BUILD=0
BOOT_CHECK=1
SERIAL=""

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
    --slots-same-build) SLOTS_SAME_BUILD=1 ;;
    --no-boot-check) BOOT_CHECK=0 ;;
    -h|--help) usage 0 ;;
    -*) echo "Unknown option: $1" >&2; usage ;;
    *)
      [[ -z "$SERIAL" ]] || { echo "Serial given twice: '$SERIAL' and '$1'" >&2; usage; }
      SERIAL="$1"
      ;;
  esac
  shift
done

if [[ $BOTH_SLOTS -eq 1 && $SLOTS_SAME_BUILD -eq 0 ]]; then
  echo "--both-slots writes the same image to both slots. That is only correct if both slots" >&2
  echo "hold the same build, which RootForge cannot verify. Re-run with --slots-same-build to" >&2
  echo "confirm that you know they do." >&2
  exit 1
fi

[[ -f "$IMG" ]] || { echo "Image not found: $IMG" >&2; exit 1; }
[[ -s "$IMG" ]] || { echo "Image is empty: $IMG" >&2; exit 1; }
# boot and init_boot images both start with the "ANDROID!" magic. Anything
# else (a zip, a sparse image, a vendor_boot) must not reach these partitions.
if [[ "$(head -c 8 "$IMG" | tr -d '\0')" != "ANDROID!" ]]; then
  echo "$IMG is not an Android boot image (missing the ANDROID! header); refusing to flash it to $PARTITION." >&2
  exit 1
fi

LOG_DIR="${ROOTFORGE_HOME:-$HOME/rootforge}/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/flash_$(date +%Y%m%d_%H%M%S).log"
rf_log_init "$LOG_FILE"
log() { echo "[flash] $*" | tee -a "$LOG_FILE"; }

rf_require_cmd jq "install jq (apt install jq)"

blocked() {
  log "BLOCKED — nothing was written to the device:"
  local line
  for line in "$@"; do log "  - $line"; done
  exit "$EXIT_BLOCKED"
}

# --- 2. exactly one device ---------------------------------------------------
RESOLVE_RC=0
SERIAL="$(rf_fastboot_wait "$SERIAL" 2>>"$LOG_FILE")" || RESOLVE_RC=$?
if [[ $RESOLVE_RC -ne 0 || -z "$SERIAL" ]]; then
  case "$RESOLVE_RC" in
    2) blocked "more than one device is in fastboot mode; pass the serial of the one to flash" ;;
    *) blocked "no device found in fastboot mode (put it in the bootloader first, or pass its serial)" ;;
  esac
fi
log "Target device serial: $SERIAL"

# --- 3. validation -----------------------------------------------------------
CHECK_JSON=""
run_check() {
  local args=(device check --operation flash-partition --partition "$PARTITION" --image "$IMG" --json)
  [[ $BOTH_SLOTS -eq 1 ]] && args+=(--both-slots)
  args+=("$SERIAL")
  CHECK_JSON="$(rf_rootforge "${args[@]}" 2>>"$LOG_FILE")" || true
  if ! jq -e . >/dev/null 2>&1 <<<"$CHECK_JSON"; then
    blocked "the device could not be validated (rootforge or python3 unavailable?); refusing to write blind"
  fi
}

run_check
if [[ "$(jq -r '.allowed' <<<"$CHECK_JSON")" != "true" ]]; then
  mapfile -t REASONS < <(jq -r '.blockers[]' <<<"$CHECK_JSON")
  blocked "${REASONS[@]}"
fi

PRODUCT="$(jq -r '.profile.codename // "unknown"' <<<"$CHECK_JSON")"
SLOT_MODE="$(jq -r '.profile.slot_mode' <<<"$CHECK_JSON")"
CURRENT_SLOT="$(jq -r '.profile.current_slot // empty' <<<"$CHECK_JSON")"
log "Device profile: $(jq -c '.profile | del(.raw)' <<<"$CHECK_JSON")"

TARGET_SLOTS=()
if [[ "$SLOT_MODE" == "ab" ]]; then
  TARGET_SLOTS=("$CURRENT_SLOT")
  if [[ $BOTH_SLOTS -eq 1 ]]; then
    OTHER_SLOT="b"; [[ "$CURRENT_SLOT" == "b" ]] && OTHER_SLOT="a"
    TARGET_SLOTS+=("$OTHER_SLOT")
  fi
fi

IMG_BYTES="$(wc -c < "$IMG" | tr -d ' ')"
IMG_SHA="$(rf_sha256_file "$IMG")"

# --- 4. operator confirmation ------------------------------------------------
SLOT_NOTE=""
if [[ ${#TARGET_SLOTS[@]} -gt 0 ]]; then SLOT_NOTE=" — slot(s): ${TARGET_SLOTS[*]}"; fi
PLAN=("About to write to the device now connected in fastboot mode:"
      "  Device:     $PRODUCT (serial $SERIAL)"
      "  Partition:  $PARTITION$SLOT_NOTE"
      "  Image:      $IMG ($IMG_BYTES bytes)"
      "  SHA-256:    $IMG_SHA"
      "  Active slot is left unchanged.")
if [[ $BOTH_SLOTS -eq 1 ]]; then
  PLAN+=("  NOTE: the same image goes to both slots; you asserted they hold the same build (not verified).")
fi
for line in "${PLAN[@]}"; do log "$line"; done

if [[ "${ROOTFORGE_ASSUME_YES:-0}" == "1" ]]; then
  log "UNATTENDED: ROOTFORGE_ASSUME_YES=1 — typed confirmation skipped for the plan above. Validation above still ran."
fi
# rf_confirm prompts on /dev/tty: fleet_orchestrate.sh redirects stdout to a
# per-device log, where a stdout prompt would be invisible.
if ! rf_confirm "FLASH $SERIAL" "${PLAN[@]}"; then
  log "Confirmation not given — aborting. Nothing was flashed."
  exit 1
fi
log "Confirmed — proceeding."

# --- 5. re-validate, then write ---------------------------------------------
FIRST_CODENAME="$PRODUCT"
run_check
if [[ "$(jq -r '.allowed' <<<"$CHECK_JSON")" != "true" ]]; then
  mapfile -t REASONS < <(jq -r '.blockers[]' <<<"$CHECK_JSON")
  blocked "the device changed state after confirmation:" "${REASONS[@]}"
fi
if [[ "$(jq -r '.profile.codename // "unknown"' <<<"$CHECK_JSON")" != "$FIRST_CODENAME" ]]; then
  blocked "serial $SERIAL now reports a different device than the one you confirmed"
fi

FB=(fastboot -s "$SERIAL")

on_interrupt() {
  log "INTERRUPTED while writing $PARTITION on $SERIAL. The partition state is UNKNOWN."
  log "Do not reboot into it. Re-run this script with a known-good image."
  exit 130
}
trap on_interrupt INT TERM

write_partition() {  # write_partition [slot]
  local slot="${1:-}" cmd=("${FB[@]}")
  [[ -n "$slot" ]] && cmd+=(--slot "$slot")
  cmd+=(flash "$PARTITION" "$IMG")
  log "Running: ${cmd[*]}"
  "${cmd[@]}" 2>>"$LOG_FILE"
}

WRITE_FAILED_RC=0
if [[ ${#TARGET_SLOTS[@]} -eq 0 ]]; then
  write_partition || WRITE_FAILED_RC=$?
  if [[ $WRITE_FAILED_RC -ne 0 ]]; then
    log "Write of $PARTITION FAILED (exit $WRITE_FAILED_RC). The partition may be partly written."
  fi
else
  for slot in "${TARGET_SLOTS[@]}"; do
    SLOT_RC=0
    write_partition "$slot" || SLOT_RC=$?
    if [[ $SLOT_RC -ne 0 ]]; then
      WRITE_FAILED_RC=$SLOT_RC
      log "Write of ${PARTITION}_$slot FAILED (exit $WRITE_FAILED_RC). That slot may be partly written."
      break
    fi
  done
fi
trap - INT TERM

if [[ $WRITE_FAILED_RC -ne 0 ]]; then
  log "Not rebooting. The active slot was not changed. Re-run with a known-good image, or flash stock $PARTITION."
  exit "$WRITE_FAILED_RC"
fi
log "Write succeeded for: ${TARGET_SLOTS[*]:-$PARTITION}"

# --- 6. reboot and verify ----------------------------------------------------
REBOOT_RC=0
"${FB[@]}" reboot 2>>"$LOG_FILE" || REBOOT_RC=$?
if [[ $REBOOT_RC -ne 0 ]]; then
  log "The write succeeded but 'fastboot reboot' failed (exit $REBOOT_RC). Reboot the device by hand."
  exit "$EXIT_REBOOT_FAILED"
fi
log "Reboot requested."

if [[ $BOOT_CHECK -eq 0 ]]; then
  log "Boot check skipped (--no-boot-check). Boot was NOT verified."
  exit "$EXIT_BOOT_UNVERIFIED"
fi
if ! command -v adb >/dev/null 2>&1; then
  log "adb is not installed, so boot cannot be verified. Boot was NOT verified."
  exit "$EXIT_BOOT_UNVERIFIED"
fi

log "Waiting up to ${ROOTFORGE_BOOT_WAIT:-180}s for $SERIAL to finish booting (adb + sys.boot_completed)..."
BOOT_RC=0
rf_adb_wait_boot "$SERIAL" || BOOT_RC=$?
case "$BOOT_RC" in
  0) log "Boot verified: $SERIAL reconnected over adb and reports sys.boot_completed=1." ;;
  2)
    log "$SERIAL reconnected over adb but never reported sys.boot_completed=1. Boot NOT verified — check the device."
    exit "$EXIT_BOOT_UNVERIFIED"
    ;;
  *)
    log "$SERIAL did not reconnect over adb in time (USB debugging off, or it is not booting). Boot NOT verified."
    exit "$EXIT_BOOT_UNVERIFIED"
    ;;
esac

log "Done. Keep the original stock $PARTITION.img noted in devices/<codename>/ for a fast revert via this script."

# Victorious Framework
