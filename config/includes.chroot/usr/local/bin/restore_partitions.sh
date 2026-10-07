#!/usr/bin/env bash
# RootForge OS — partition restore
# Victorious Framework
#
# Restores a backup produced by backup_partitions.sh via fastboot flash.
# Lists available backups if no timestamp is given.
#
# Usage: restore_partitions.sh <device_codename> [backup_timestamp]
#          [--partitions a,b,c] [--accept-legacy-import] [device-serial]
#
# What is flashed is decided by the backup's manifest.json, never by what
# happens to be in the directory:
#   * `rootforge backup verify` must pass: every listed image present, a
#     regular non-empty file (no symlinks), the recorded size and SHA-256, and
#     no *.img the manifest does not list. A single failure refuses the whole
#     restore. Backups with only an older SHA256SUMS are refused until
#     `rootforge backup import-legacy` records them as legacy-imported, and
#     those need --accept-legacy-import (their device and slot are unknown).
#   * Exactly one fastboot device is selected, and `rootforge device check`
#     must allow the writes (unlocked, known identity, partitions present and
#     large enough) and agree with the backup: same product, same active slot,
#     same bootloader version when both are known.
#   * The operator types RESTORE <serial> after seeing the plan; the images
#     are re-verified and the device re-checked immediately before writing.
#   * Writing stops at the first failure and nothing is rebooted.
#
# Exit codes: 0 restored | 1 failure (including a failed write) |
#   3 refused by validation (nothing written) | 130 interrupted during a write.

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

EXIT_BLOCKED=3

usage() {
  echo "Usage: restore_partitions.sh <device_codename> [timestamp] [--partitions a,b,c] [--accept-legacy-import] [serial]" >&2
  exit "${1:-1}"
}

[[ $# -ge 1 ]] || usage
case "$1" in -h|--help) usage 0 ;; esac
CODENAME="$1"; shift
TIMESTAMP=""
SERIAL=""
PARTITIONS_ARG=""
ACCEPT_LEGACY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --partitions)
      [[ $# -ge 2 ]] || { echo "--partitions needs a value" >&2; usage; }
      PARTITIONS_ARG="$2"; shift
      ;;
    --accept-legacy-import) ACCEPT_LEGACY=1 ;;
    -h|--help) usage 0 ;;
    -*) echo "Unknown option: $1" >&2; usage ;;
    *)
      if [[ -z "$TIMESTAMP" ]]; then TIMESTAMP="$1"
      elif [[ -z "$SERIAL" ]]; then SERIAL="$1"
      else echo "Unexpected argument: $1" >&2; usage; fi
      ;;
  esac
  shift
done

# CODENAME and TIMESTAMP are interpolated into a path under
# $ROOTFORGE_HOME/devices/; a value containing ".." or "/" used to make this
# script read and FLASH every .img from an arbitrary directory.
rf_reject_path_component() {
  local label="$1" value="$2"
  case "$value" in
    ""|*/*|*..*)
      echo "Invalid $label '$value' — must not be empty or contain '/' or '..'." >&2
      echo "It is used as a directory name under \$ROOTFORGE_HOME/devices/." >&2
      exit 1
      ;;
  esac
}
rf_reject_path_component "device codename" "$CODENAME"
[[ -n "$TIMESTAMP" ]] && rf_reject_path_component "backup timestamp" "$TIMESTAMP"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
export ROOTFORGE_HOME
DEVICE_BACKUP_ROOT="$ROOTFORGE_HOME/devices/$CODENAME/backups"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR"

if [[ -z "$TIMESTAMP" ]]; then
  echo "Available backups for $CODENAME:"
  ls -1 "$DEVICE_BACKUP_ROOT" 2>/dev/null || echo "  (none found at $DEVICE_BACKUP_ROOT)"
  echo ""
  echo "Usage: restore_partitions.sh $CODENAME <timestamp> [serial]"
  exit 0
fi

BACKUP_DIR="$DEVICE_BACKUP_ROOT/$TIMESTAMP"
[[ -d "$BACKUP_DIR" ]] || { echo "No backup found at $BACKUP_DIR" >&2; exit 1; }

rf_require_cmd jq "install jq (apt install jq)"

LOG_FILE="$LOG_DIR/restore_${CODENAME}_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[restore] $*" | tee -a "$LOG_FILE"; }
blocked() {
  log "REFUSED — nothing was written to the device:"
  local line
  for line in "$@"; do log "  - $line"; done
  exit "$EXIT_BLOCKED"
}

log "Restoring from $BACKUP_DIR"

# --- 1. the backup must verify -------------------------------------------------
VERIFY_JSON=""
run_verify() {
  local args=(backup verify "$CODENAME" "$TIMESTAMP" --json)
  [[ -n "$PARTITIONS_ARG" ]] && args+=(--partitions "$PARTITIONS_ARG")
  VERIFY_JSON="$(rf_rootforge "${args[@]}" 2>>"$LOG_FILE")" || true
  if ! jq -e . >/dev/null 2>&1 <<<"$VERIFY_JSON"; then
    blocked "the backup could not be verified (rootforge or python3 unavailable?); refusing to write unverified images"
  fi
  if [[ "$(jq -r '.ok' <<<"$VERIFY_JSON")" != "true" ]]; then
    mapfile -t PROBLEMS < <(jq -r '.problems[]' <<<"$VERIFY_JSON")
    blocked "the backup failed verification:" "${PROBLEMS[@]}"
  fi
}
run_verify

KIND="$(jq -r '.kind' <<<"$VERIFY_JSON")"
TRUST="$(jq -r '.trust // "unknown"' <<<"$VERIFY_JSON")"
if [[ "$KIND" != "manifest" ]]; then
  blocked "this is a legacy backup (checksums only, no manifest.json)." \
          "Run: rootforge backup import-legacy $CODENAME $TIMESTAMP   (records it as 'legacy-imported'), then restore with --accept-legacy-import."
fi
if [[ "$TRUST" == "legacy-imported" && $ACCEPT_LEGACY -eq 0 ]]; then
  blocked "this backup was imported from a legacy backup, so its device and slot are unknown." \
          "Re-run with --accept-legacy-import if you are certain these images belong to the connected device."
fi
if [[ "$(jq -r '.complete' <<<"$VERIFY_JSON")" == "false" ]]; then
  log "NOTE: this backup is INCOMPLETE — only the images listed below were captured."
fi

PARTS=(); PATHS=(); SHAS=(); SIZES=(); SLOTS=()
while IFS=$'\t' read -r p path sha size slot; do
  PARTS+=("$p"); PATHS+=("$path"); SHAS+=("$sha"); SIZES+=("$size"); SLOTS+=("$slot")
done < <(jq -r '.entries[] | [.partition, .path, .sha256, (.size_bytes|tostring), (.slot // "-")] | @tsv' <<<"$VERIFY_JSON")
[[ ${#PARTS[@]} -gt 0 ]] || blocked "the verified backup contains no images to restore"

# What the backup says about the device it came from (null = not recorded).
EXPECT_PRODUCT="$(jq -r '.device.product // empty' <<<"$VERIFY_JSON")"
EXPECT_BOOTLOADER="$(jq -r '.device.version_bootloader // empty' <<<"$VERIFY_JSON")"
[[ -z "$EXPECT_PRODUCT" && "$TRUST" == "legacy-imported" ]] && EXPECT_PRODUCT="$CODENAME"
EXPECT_SLOT=""
for slot in "${SLOTS[@]}"; do
  [[ "$slot" == "-" ]] && continue
  if [[ -n "$EXPECT_SLOT" && "$EXPECT_SLOT" != "$slot" ]]; then
    blocked "the manifest records images from two different slots ($EXPECT_SLOT and $slot); refusing to guess"
  fi
  EXPECT_SLOT="$slot"
done

# --- 2. exactly one device ---------------------------------------------------
RESOLVE_RC=0
SERIAL="$(rf_fastboot_wait "$SERIAL" 2>>"$LOG_FILE")" || RESOLVE_RC=$?
if [[ $RESOLVE_RC -ne 0 || -z "$SERIAL" ]]; then
  case "$RESOLVE_RC" in
    2) blocked "more than one device is in fastboot mode; pass the serial of the one to restore" ;;
    *) blocked "no device found in fastboot mode (put it in the bootloader first, or pass its serial)" ;;
  esac
fi
log "Target device serial: $SERIAL"

# --- 3. the device must be allowed and must match the backup ------------------
CHECK_JSON=""
run_check() {
  local args=(device check --operation flash-partition --json) i
  for i in "${!PARTS[@]}"; do
    args+=(--partition "${PARTS[$i]}" --image "${PATHS[$i]}")
  done
  [[ -n "$EXPECT_PRODUCT" ]] && args+=(--expect-product "$EXPECT_PRODUCT")
  [[ -n "$EXPECT_SLOT" ]] && args+=(--expect-slot "$EXPECT_SLOT")
  [[ -n "$EXPECT_BOOTLOADER" ]] && args+=(--expect-bootloader-version "$EXPECT_BOOTLOADER")
  args+=("$SERIAL")
  CHECK_JSON="$(rf_rootforge "${args[@]}" 2>>"$LOG_FILE")" || true
  if ! jq -e . >/dev/null 2>&1 <<<"$CHECK_JSON"; then
    blocked "the device could not be validated (rootforge or python3 unavailable?); refusing to write blind"
  fi
  if [[ "$(jq -r '.allowed' <<<"$CHECK_JSON")" != "true" ]]; then
    mapfile -t REASONS < <(jq -r '.blockers[]' <<<"$CHECK_JSON")
    blocked "${REASONS[@]}"
  fi
}
run_check
FIRST_CODENAME="$(jq -r '.profile.codename // "unknown"' <<<"$CHECK_JSON")"
mapfile -t WARNINGS < <(jq -r '.warnings[]?' <<<"$CHECK_JSON")
[[ -z "$EXPECT_PRODUCT" ]] && WARNINGS+=("the backup records no device product, so it could not be matched to this device")

# --- 4. plan and typed confirmation ------------------------------------------
PLAN=("About to restore ${#PARTS[@]} image(s) to the device now in fastboot mode:"
      "  Device:  $FIRST_CODENAME (serial $SERIAL)"
      "  Backup:  $CODENAME / $TIMESTAMP (trust: $TRUST)")
for i in "${!PARTS[@]}"; do
  slot_note=""; [[ "${SLOTS[$i]}" != "-" ]] && slot_note=" slot ${SLOTS[$i]}"
  PLAN+=("    ${PARTS[$i]}$slot_note  <-  $(basename "${PATHS[$i]}")  (${SIZES[$i]} bytes, sha256 ${SHAS[$i]:0:16}...)")
done
for w in "${WARNINGS[@]}"; do [[ -n "$w" ]] && PLAN+=("  WARNING: $w"); done
for line in "${PLAN[@]}"; do log "$line"; done

if [[ "${ROOTFORGE_ASSUME_YES:-0}" == "1" ]]; then
  log "UNATTENDED: ROOTFORGE_ASSUME_YES=1 — typed confirmation skipped for the plan above. Verification and device checks above still ran."
fi
if ! rf_confirm "RESTORE $SERIAL" "${PLAN[@]}"; then
  log "Confirmation not given — aborting. Nothing was flashed."
  exit 1
fi
log "Confirmed — proceeding."

# --- 5. re-verify, re-check, then write ---------------------------------------
run_verify
run_check
if [[ "$(jq -r '.profile.codename // "unknown"' <<<"$CHECK_JSON")" != "$FIRST_CODENAME" ]]; then
  blocked "serial $SERIAL now reports a different device than the one you confirmed"
fi

FB=(fastboot -s "$SERIAL")
CURRENT=""
on_interrupt() {
  log "INTERRUPTED while restoring${CURRENT:+ $CURRENT}. That partition's state is UNKNOWN."
  log "Do not reboot. Re-run the restore (or flash a known-good image) first."
  exit 130
}
trap on_interrupt INT TERM

WRITTEN=()
for i in "${!PARTS[@]}"; do
  part="${PARTS[$i]}"; path="${PATHS[$i]}"; slot="${SLOTS[$i]}"
  CURRENT="$part"
  # The images were verified a moment ago; re-hash this one right before it is
  # written so a file swapped in between is caught.
  if ! rf_sha256_verify "$path" "${SHAS[$i]}"; then
    log "$part: image changed after verification — stopping. Written so far: ${WRITTEN[*]:-none}"
    exit 1
  fi
  cmd=("${FB[@]}")
  [[ "$slot" != "-" ]] && cmd+=(--slot "$slot")
  cmd+=(flash "$part" "$path")
  log "Running: ${cmd[*]}"
  STEP_RC=0
  "${cmd[@]}" 2>>"$LOG_FILE" || STEP_RC=$?
  if [[ $STEP_RC -ne 0 ]]; then
    NOT_ATTEMPTED=("${PARTS[@]:$((i + 1))}")
    log "FAILED to flash $part (exit $STEP_RC). Stopping."
    log "  written:       ${WRITTEN[*]:-none}"
    log "  failed:        $part (may be partly written)"
    log "  not attempted: ${NOT_ATTEMPTED[*]:-none}"
    log "Do NOT reboot into the system until this is resolved; see $LOG_FILE."
    exit 1
  fi
  WRITTEN+=("$part")
done
trap - INT TERM

log "Restore flashing complete: ${WRITTEN[*]}. Nothing was rebooted — reboot with: fastboot -s $SERIAL reboot"

# Victorious Framework
