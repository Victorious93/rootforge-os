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

CODENAME="${1:?Usage: backup_partitions.sh <device_codename> [serial]}"
SERIAL="${2:-}"

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

MODE=""
if $FASTBOOT devices 2>/dev/null | grep -q .; then
  MODE="fastboot"
elif $ADB devices 2>/dev/null | grep -qv "List of devices"; then
  MODE="adb"
fi

if [[ -z "$MODE" ]]; then
  log "No device found in fastboot or adb mode. Connect the device and put it in"
  log "bootloader mode (adb reboot bootloader) or ensure adb sees it, then retry."
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

echo "$CODENAME backup $STAMP" > "$BACKUP_DIR/manifest.txt"
for part in "${PARTITIONS[@]}"; do
  [[ -f "$BACKUP_DIR/${part}.img" ]] && echo "$part: OK ($(du -h "$BACKUP_DIR/${part}.img" | cut -f1))" >> "$BACKUP_DIR/manifest.txt"
done

if [[ ${#FAILED[@]} -gt 0 ]]; then
  log "Partitions NOT backed up: ${FAILED[*]}"
  log "If this device is pre-root and lacks fastboot fetch support, back these up"
  log "manually from a booted TWRP: dd if=/dev/block/by-name/<part> of=/sdcard/<part>.img"
fi

log "Backup complete at $BACKUP_DIR"
log "Restore with: restore_partitions.sh $CODENAME $STAMP"

# Victorious Framework
