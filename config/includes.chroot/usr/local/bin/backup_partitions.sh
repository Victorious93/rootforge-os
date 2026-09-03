#!/usr/bin/env bash
# RootForge OS — partition backup
# Victorious Framework
#
# Backs up boot/init_boot/vendor_boot/dtbo/vbmeta before you flash anything.
# Tries three paths in order of preference, since not every device supports
# the same read-back method:
#   1. `fastboot fetch` — works on devices with this support (Pixel-lineage,
#      Android 11+ on many AOSP-derived bootloaders)
#   2. adb root + dd from /dev/block/by-name/<partition> — works if the
#      device is already rooted or running a userdebug build
#   3. neither — prints manual instructions (dd from a booted TWRP/recovery)
#      rather than silently skipping the backup
#
# Usage: ./backup_partitions.sh <device_codename> [device-serial]

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

CODENAME="${1:?Usage: backup_partitions.sh <device_codename> [serial]}"
SERIAL="${2:-}"

# CODENAME and TIMESTAMP below are interpolated straight into a path under
# $ROOTFORGE_HOME/devices/. Nothing validated them, so a value containing
# ".." or "/" escaped that tree entirely:
#
#   backup_partitions.sh '../../escaped'
#     wrote the backup to $ROOTFORGE_HOME/../../escaped/backups/... — outside
#     devices/ altogether.
#   restore_partitions.sh testdev '../../../../evil'
#     read every .img from an arbitrary directory and FLASHED them to the
#     device. The SHA256SUMS gate does not catch it: an arbitrary directory
#     has no SHA256SUMS, so integrity checking degrades to a warning and the
#     flash proceeds.
#
# Both are realistically reached by mistake — a copy-pasted path, a value
# from a script — rather than by malice, and the consequence is writing
# unverified images to a device's boot partition.
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

FASTBOOT="fastboot"; ADB="adb"
[[ -n "$SERIAL" ]] && { FASTBOOT="fastboot -s $SERIAL"; ADB="adb -s $SERIAL"; }

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
STAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP_DIR="$ROOTFORGE_HOME/devices/$CODENAME/backups/$STAMP"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$BACKUP_DIR" "$LOG_DIR"
LOG_FILE="$LOG_DIR/backup_${CODENAME}_${STAMP}.log"
log() { echo "[backup] $*" | tee -a "$LOG_FILE"; }

PARTITIONS=(boot init_boot vendor_boot dtbo vbmeta vbmeta_system)

log "Backup target: $BACKUP_DIR"

try_fastboot_fetch() {
  local part="$1" out="$2"
  $FASTBOOT fetch "$part" "$out" 2>>"$LOG_FILE"
}

try_adb_dd() {
  local part="$1" out="$2"
  local block="/dev/block/by-name/$part"
  $ADB shell "su -c '[ -e $block ] && echo exists'" 2>/dev/null | grep -q exists || return 1
  $ADB shell "su -c 'dd if=$block of=/sdcard/${part}.img'" 2>>"$LOG_FILE"
  $ADB pull "/sdcard/${part}.img" "$out" 2>>"$LOG_FILE"
  $ADB shell "rm -f /sdcard/${part}.img" 2>>"$LOG_FILE"
}

# `adb devices` always prints a "List of devices attached" header followed by
# a blank line, so the previous `grep -qv "List of devices"` matched that
# blank line and reported MODE=adb with nothing plugged in — every partition
# then "failed to fetch" for a reason that had nothing to do with the device.
# rf_adb_serials/rf_fastboot_serials parse the state column instead.
MODE=""
if [[ -n "$SERIAL" ]]; then
  if rf_fastboot_serials | grep -qxF "$SERIAL"; then
    MODE="fastboot"
  elif rf_adb_serials | grep -qxF "$SERIAL"; then
    MODE="adb"
  fi
else
  if rf_have_fastboot_device; then
    MODE="fastboot"
  elif rf_have_adb_device; then
    MODE="adb"
  fi
fi

if [[ -z "$MODE" ]]; then
  if [[ -n "$SERIAL" ]]; then
    log "Device '$SERIAL' is not in fastboot mode and not reporting 'device' over adb."
    log "Connected adb devices:      $(rf_adb_serials | tr '\n' ' ')"
    log "Connected fastboot devices: $(rf_fastboot_serials | tr '\n' ' ')"
  else
    log "No device found in fastboot or adb mode. Connect the device and put it in"
    log "bootloader mode (adb reboot bootloader) or ensure adb sees it, then retry."
    log "A device shown as 'unauthorized' by 'adb devices' still needs the USB-debugging"
    log "prompt accepted on-screen — it does not count as connected here."
  fi
  exit 1
fi

log "Device reachable via: $MODE"

FAILED=()
for part in "${PARTITIONS[@]}"; do
  OUT="$BACKUP_DIR/${part}.img"
  SUCCESS=0

  if [[ "$MODE" == "fastboot" ]]; then
    if try_fastboot_fetch "$part" "$OUT" && [[ -s "$OUT" ]]; then
      log "fetched $part via fastboot fetch"
      SUCCESS=1
    fi
  fi

  if [[ $SUCCESS -eq 0 && "$MODE" == "adb" ]]; then
    if try_adb_dd "$part" "$OUT" && [[ -s "$OUT" ]]; then
      log "fetched $part via adb root + dd"
      SUCCESS=1
    fi
  fi

  if [[ $SUCCESS -eq 0 ]]; then
    rm -f "$OUT"
    log "could not fetch $part automatically (device may not have this partition, or"
    log "  lacks fastboot fetch support and isn't rooted yet for the adb+dd path)"
    FAILED+=("$part")
  fi
done

# The manifest used to carry a human-readable `du -h` size and nothing else,
# which gave restore_partitions.sh no way to tell a good image from a
# truncated or corrupted one before flashing it. Record a SHA-256 per image
# (and a sha256sum-compatible sidecar) so the restore path can verify.
{
  echo "$CODENAME backup $STAMP"
  echo "# columns: partition sha256 bytes"
} > "$BACKUP_DIR/manifest.txt"

: > "$BACKUP_DIR/SHA256SUMS"
for part in "${PARTITIONS[@]}"; do
  IMG_PATH="$BACKUP_DIR/${part}.img"
  [[ -f "$IMG_PATH" ]] || continue
  DIGEST="$(rf_sha256_file "$IMG_PATH")"
  BYTES="$(stat -c %s "$IMG_PATH")"
  echo "$part $DIGEST $BYTES" >> "$BACKUP_DIR/manifest.txt"
  # Relative name so `sha256sum -c SHA256SUMS` works from inside the backup
  # directory even after it has been moved or copied elsewhere.
  echo "$DIGEST  ${part}.img" >> "$BACKUP_DIR/SHA256SUMS"
  log "$part: $(numfmt --to=iec --suffix=B "$BYTES" 2>/dev/null || echo "$BYTES bytes") sha256=${DIGEST:0:16}..."
done

if [[ ${#FAILED[@]} -gt 0 ]]; then
  log "Partitions NOT backed up: ${FAILED[*]}"
  log "If this device is pre-root and lacks fastboot fetch support, back these up"
  log "manually from a booted TWRP: dd if=/dev/block/by-name/<part> of=/sdcard/<part>.img"
fi

log "Backup complete at $BACKUP_DIR"
log "Verify at any time with: (cd $BACKUP_DIR && sha256sum -c SHA256SUMS)"
log "Restore with: restore_partitions.sh $CODENAME $STAMP"

# Victorious Framework
