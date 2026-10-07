#!/usr/bin/env bash
# RootForge OS — partition backup
# Victorious Framework
#
# Captures partition images from ONE device and records them in a versioned
# manifest (manifest.json, see rootforge/core/backup.py) that restore and
# `rootforge backup verify` enforce. Tries, per partition, in order:
#   1. `fastboot fetch` — devices whose bootloader supports it (fetches the
#      current slot's partition on A/B devices)
#   2. adb root + dd from /dev/block/by-name/<partition>[_<slot>] — needs a
#      rooted or userdebug device; the pulled copy is checked against a
#      checksum taken on the device when the device can compute one
#   3. neither — the partition is reported as NOT captured; a backup is never
#      silently padded or reported complete when it is not
#
# Usage: backup_partitions.sh <device_codename> [--partitions a,b,c] [device-serial]
#
# The partition list comes from `backup.partitions` in the RootForge config
# (default: boot init_boot vendor_boot dtbo vbmeta vbmeta_system), overridden
# by --partitions.
#
# Exit codes: 0 every requested partition captured | 1 nothing captured, no
#   usable device, or bad arguments | 4 PARTIAL — some captured, some not
#   (the manifest says which; "complete": false).
# The last line on stdout is BACKUP_DIR=<exact directory> so callers never
# have to guess which directory was written.

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

usage() {
  echo "Usage: backup_partitions.sh <device_codename> [--partitions a,b,c] [serial]" >&2
  exit "${1:-1}"
}

[[ $# -ge 1 ]] || usage
case "$1" in -h|--help) usage 0 ;; esac
CODENAME="$1"; shift
SERIAL=""
PARTITIONS_ARG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --partitions)
      [[ $# -ge 2 ]] || { echo "--partitions needs a value" >&2; usage; }
      PARTITIONS_ARG="$2"; shift
      ;;
    -h|--help) usage 0 ;;
    -*) echo "Unknown option: $1" >&2; usage ;;
    *)
      [[ -z "$SERIAL" ]] || { echo "Serial given twice: '$SERIAL' and '$1'" >&2; usage; }
      SERIAL="$1"
      ;;
  esac
  shift
done

# CODENAME becomes a directory name under $ROOTFORGE_HOME/devices/. A value
# containing ".." or "/" used to write the backup outside that tree.
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

valid_partition() { [[ "$1" =~ ^[a-z0-9_]+$ ]]; }

rf_require_cmd jq "install jq (apt install jq)"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
STAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP_DIR="$ROOTFORGE_HOME/devices/$CODENAME/backups/$STAMP"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/backup_${CODENAME}_${STAMP}.log"
log() { echo "[backup] $*" | tee -a "$LOG_FILE"; }

# --- which partitions ---------------------------------------------------------
# Precedence: --partitions > device/project/user config > built-in default.
PARTITIONS=()
if [[ -n "$PARTITIONS_ARG" ]]; then
  IFS=',' read -r -a PARTITIONS <<<"$PARTITIONS_ARG"
else
  CFG_ERR="$(mktemp)"
  CFG_RC=0
  CONFIG_JSON="$(rf_rootforge config show --json --codename "$CODENAME" 2>"$CFG_ERR")" || CFG_RC=$?
  if [[ $CFG_RC -eq 0 ]] && jq -e '.backup.partitions | type == "array"' >/dev/null 2>&1 <<<"$CONFIG_JSON"; then
    mapfile -t PARTITIONS < <(jq -r '.backup.partitions[]' <<<"$CONFIG_JSON")
  elif grep -q '^Config error' "$CFG_ERR" 2>/dev/null; then
    log "$(cat "$CFG_ERR")"
    log "Fix the configuration (or pass --partitions); refusing to back up on an invalid config."
    rm -f "$CFG_ERR"
    exit 1
  else
    log "rootforge config unavailable — using the built-in default partition list."
    PARTITIONS=(boot init_boot vendor_boot dtbo vbmeta vbmeta_system)
  fi
  rm -f "$CFG_ERR"
fi
[[ ${#PARTITIONS[@]} -gt 0 ]] || { echo "No partitions selected." >&2; exit 1; }
for part in "${PARTITIONS[@]}"; do
  valid_partition "$part" || { echo "Invalid partition name '$part' (lowercase letters, digits, underscores)." >&2; exit 1; }
done

# --- exactly one device -----------------------------------------------------------
# `adb devices` prints a header and a trailing blank line, which the old
# `grep -qv "List of devices"` mistook for a device. rf_*_serials parse the
# state column; only the 'device' (adb) and fastboot states count.
mapfile -t FB_SERIALS < <(rf_fastboot_serials)
mapfile -t ADB_SERIALS < <(rf_adb_serials)
MODE=""
if [[ -n "$SERIAL" ]]; then
  for s in "${FB_SERIALS[@]}"; do [[ "$s" == "$SERIAL" ]] && MODE="fastboot"; done
  for s in "${ADB_SERIALS[@]}"; do [[ "$s" == "$SERIAL" ]] && MODE="adb"; done
else
  ALL=("${FB_SERIALS[@]}" "${ADB_SERIALS[@]}")
  if [[ ${#ALL[@]} -eq 1 ]]; then
    SERIAL="${ALL[0]}"
    [[ ${#FB_SERIALS[@]} -eq 1 ]] && MODE="fastboot" || MODE="adb"
  elif [[ ${#ALL[@]} -gt 1 ]]; then
    log "More than one device is connected (${ALL[*]}). Pass the serial of the one to back up."
    exit 1
  fi
fi

if [[ -z "$MODE" ]]; then
  if [[ -n "$SERIAL" ]]; then
    log "Device '$SERIAL' is not in fastboot mode and not reporting 'device' over adb."
    log "Connected adb devices:      ${ADB_SERIALS[*]:-}"
    log "Connected fastboot devices: ${FB_SERIALS[*]:-}"
  else
    log "No device found in fastboot or adb mode. Connect the device and put it in"
    log "bootloader mode (adb reboot bootloader) or ensure adb sees it, then retry."
    log "A device shown as 'unauthorized' by 'adb devices' still needs the USB-debugging"
    log "prompt accepted on-screen — it does not count as connected here."
  fi
  exit 1
fi
log "Device $SERIAL reachable via: $MODE"

FASTBOOT=(fastboot -s "$SERIAL")
ADB=(adb -s "$SERIAL")
BOUND=()
command -v timeout >/dev/null 2>&1 && BOUND=(timeout "${ROOTFORGE_TOOL_TIMEOUT:-300}")

# --- device facts for the manifest (read-only; unknown stays null) ------------
PROFILE_JSON=""
PROFILE_JSON="$(rf_rootforge device info "$SERIAL" --json 2>>"$LOG_FILE")" || true
if ! jq -e '.probe_ok == true' >/dev/null 2>&1 <<<"$PROFILE_JSON"; then
  log "Device profile unavailable — the manifest will record the device facts as unknown."
  PROFILE_JSON='{}'
fi
DEVICE_JSON="$(jq -c '{
    product: (.codename // null),
    slot_mode: (.slot_mode // null),
    current_slot: (.current_slot // null),
    bootloader_unlocked: (.bootloader_unlocked // null),
    version_bootloader: (.raw["version-bootloader"] // null)
  }' <<<"$PROFILE_JSON")"
DEVICE_PRODUCT="$(jq -r '.product // empty' <<<"$DEVICE_JSON")"
CURRENT_SLOT="$(jq -r '.current_slot // empty' <<<"$DEVICE_JSON")"
if [[ -n "$DEVICE_PRODUCT" && "$DEVICE_PRODUCT" != "$CODENAME" ]]; then
  log "WARNING: the directory label '$CODENAME' differs from the device-reported product '$DEVICE_PRODUCT'."
  log "         Restore compares against the device-reported product recorded in the manifest."
fi

mkdir -p "$BACKUP_DIR"
log "Backup target: $BACKUP_DIR"

has_slot() {  # does the probe say this partition is slotted? (fastboot has-slot:<p>)
  [[ "$(jq -r --arg k "has-slot:$1" '.raw[$k] // empty' <<<"$PROFILE_JSON")" == "yes" ]]
}

capture_fastboot() {  # capture_fastboot <part> <out>
  local part="$1" out="$2"
  "${BOUND[@]}" "${FASTBOOT[@]}" fetch "$part" "$out.part" 2>>"$LOG_FILE" || return 1
  [[ -s "$out.part" ]] || return 1
  mv -f "$out.part" "$out"
}

capture_adb() {  # capture_adb <part> <out>   — needs root on the device
  local part="$1" out="$2" block="" remote="/sdcard/rf_${1}.img" candidate
  for candidate in "$part${CURRENT_SLOT:+_$CURRENT_SLOT}" "$part"; do
    if "${BOUND[@]}" "${ADB[@]}" shell "su -c '[ -e /dev/block/by-name/$candidate ] && echo exists'" 2>/dev/null | grep -q exists; then
      block="/dev/block/by-name/$candidate"
      break
    fi
  done
  [[ -n "$block" ]] || return 1
  "${BOUND[@]}" "${ADB[@]}" shell "su -c 'dd if=$block of=$remote'" 2>>"$LOG_FILE" || { "${ADB[@]}" shell "rm -f $remote" 2>/dev/null || true; return 1; }
  local remote_sum=""
  remote_sum="$("${BOUND[@]}" "${ADB[@]}" shell "sha256sum $remote" 2>/dev/null | awk '{print $1}' | tr -d '\r' || true)"
  if ! "${BOUND[@]}" "${ADB[@]}" pull "$remote" "$out.part" 2>>"$LOG_FILE"; then
    "${ADB[@]}" shell "rm -f $remote" 2>/dev/null || true
    rm -f "$out.part"
    return 1
  fi
  "${ADB[@]}" shell "rm -f $remote" 2>>"$LOG_FILE" || true
  [[ -s "$out.part" ]] || { rm -f "$out.part"; return 1; }
  if [[ -n "$remote_sum" && "$remote_sum" =~ ^[0-9a-f]{64}$ ]]; then
    if [[ "$(rf_sha256_file "$out.part")" != "$remote_sum" ]]; then
      log "  transfer check FAILED for $part: pulled copy does not match the device's own checksum"
      rm -f "$out.part"
      return 1
    fi
  else
    log "  note: the device could not checksum $part, so the adb transfer itself is unverified"
  fi
  mv -f "$out.part" "$out"
}

# --- capture ----------------------------------------------------------------------
ENTRIES='[]'
CAPTURED=()
MISSING=()
: > "$BACKUP_DIR/SHA256SUMS"
{
  echo "$CODENAME backup $STAMP"
  echo "# columns: partition sha256 bytes"
} > "$BACKUP_DIR/manifest.txt"

for part in "${PARTITIONS[@]}"; do
  OUT="$BACKUP_DIR/${part}.img"
  METHOD=""
  if [[ "$MODE" == "fastboot" ]]; then
    capture_fastboot "$part" "$OUT" && METHOD="fastboot-fetch"
  else
    capture_adb "$part" "$OUT" && METHOD="adb-dd"
  fi
  if [[ -z "$METHOD" ]]; then
    rm -f "$OUT" "$OUT.part"
    log "could not capture $part (the device may not have it, or lacks fastboot fetch /"
    log "  root access for the adb+dd path)"
    MISSING+=("$part")
    continue
  fi
  DIGEST="$(rf_sha256_file "$OUT")"
  BYTES="$(stat -c %s "$OUT")"
  SLOT_VALUE=""
  if [[ "$MODE" == "adb" && -n "$CURRENT_SLOT" ]]; then SLOT_VALUE="$CURRENT_SLOT"
  elif has_slot "$part"; then SLOT_VALUE="$CURRENT_SLOT"; fi
  ENTRIES="$(jq -c --arg p "$part" --arg f "${part}.img" --arg h "$DIGEST" --argjson s "$BYTES" \
      --arg m "$METHOD" --arg slot "$SLOT_VALUE" \
      '. + [{partition:$p, file:$f, sha256:$h, size_bytes:$s, method:$m, slot:(if $slot == "" then null else $slot end)}]' \
      <<<"$ENTRIES")"
  CAPTURED+=("$part")
  echo "$part $DIGEST $BYTES" >> "$BACKUP_DIR/manifest.txt"
  echo "$DIGEST  ${part}.img" >> "$BACKUP_DIR/SHA256SUMS"
  log "$part: $(numfmt --to=iec --suffix=B "$BYTES" 2>/dev/null || echo "$BYTES bytes") sha256=${DIGEST:0:16}... via $METHOD"
done

if [[ ${#CAPTURED[@]} -eq 0 ]]; then
  rm -f "$BACKUP_DIR/SHA256SUMS" "$BACKUP_DIR/manifest.txt"
  rmdir "$BACKUP_DIR" 2>/dev/null || true
  log "NOTHING was captured (${MISSING[*]}). No backup was created."
  log "If this device lacks fastboot fetch support and is not rooted yet, take the images from a"
  log "booted recovery: dd if=/dev/block/by-name/<part> of=/sdcard/<part>.img"
  exit 1
fi

COMPLETE=true
[[ ${#MISSING[@]} -eq 0 ]] || COMPLETE=false
REQUESTED_JSON="$(printf '%s\n' "${PARTITIONS[@]}" | jq -R . | jq -sc .)"
MISSING_JSON="$(if [[ ${#MISSING[@]} -gt 0 ]]; then printf '%s\n' "${MISSING[@]}" | jq -R . | jq -sc .; else echo '[]'; fi)"
SERIAL_JSON="$(jq -n --arg s "$SERIAL" '$s')"
jq -n --argjson entries "$ENTRIES" --argjson device "$DEVICE_JSON" \
      --argjson requested "$REQUESTED_JSON" --argjson missing "$MISSING_JSON" \
      --argjson serial "$SERIAL_JSON" --argjson complete "$COMPLETE" \
      --arg codename "$CODENAME" --arg stamp "$STAMP" --arg created "$(date -u +%Y-%m-%dT%H:%M:%S+00:00)" \
      '{manifest_version: 1, trust: "captured", codename: $codename, timestamp: $stamp, serial: $serial,
        created_at: $created, complete: $complete, requested_partitions: $requested,
        missing_partitions: $missing, device: $device, entries: $entries}' \
  > "$BACKUP_DIR/manifest.json.tmp"
mv -f "$BACKUP_DIR/manifest.json.tmp" "$BACKUP_DIR/manifest.json"

log "Manifest written: $BACKUP_DIR/manifest.json"
log "Verify with: rootforge backup verify $CODENAME $STAMP"
log "Restore with: rootforge backup restore $CODENAME $STAMP"

EXIT_CODE=0
if [[ "$COMPLETE" != "true" ]]; then
  log "PARTIAL backup: captured ${CAPTURED[*]}; NOT captured ${MISSING[*]}."
  EXIT_CODE=4
else
  log "Backup complete: ${#CAPTURED[@]} partition(s)."
fi
echo "BACKUP_DIR=$BACKUP_DIR"
exit "$EXIT_CODE"

# Victorious Framework
