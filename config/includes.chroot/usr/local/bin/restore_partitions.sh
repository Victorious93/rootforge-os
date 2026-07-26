#!/usr/bin/env bash
# RootForge OS — partition restore
# Victorious Framework
#
# Restores a backup produced by backup_partitions.sh via fastboot flash.
# Lists available backups if no timestamp is given.
#
# Usage: ./restore_partitions.sh <device_codename> [backup_timestamp] [device-serial]

set -euo pipefail

CODENAME="${1:?Usage: restore_partitions.sh <device_codename> [backup_timestamp] [serial]}"
TIMESTAMP="${2:-}"
SERIAL="${3:-}"

FASTBOOT="fastboot"
[[ -n "$SERIAL" ]] && FASTBOOT="fastboot -s $SERIAL"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
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

LOG_FILE="$LOG_DIR/restore_${CODENAME}_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[restore] $*" | tee -a "$LOG_FILE"; }

log "Restoring from $BACKUP_DIR"
cat "$BACKUP_DIR/manifest.txt" 2>/dev/null | tee -a "$LOG_FILE" || true

echo ""
echo "This will flash every .img in this backup back to its named partition on"
echo "the connected device. Confirm the device in fastboot mode is actually $CODENAME"
echo "before proceeding — flashing another device's boot/vendor_boot images will"
echo "likely brick it."
read -r -p "Type RESTORE to proceed: " CONFIRM
[[ "$CONFIRM" == "RESTORE" ]] || { log "Confirmation not given — aborting."; exit 1; }

$FASTBOOT wait-for-device

for img in "$BACKUP_DIR"/*.img; do
  [[ -f "$img" ]] || continue
  part="$(basename "$img" .img)"
  log "Flashing $part <- $img"
  $FASTBOOT flash "$part" "$img" 2>>"$LOG_FILE" || log "FAILED to flash $part — check $LOG_FILE"
done

log "Restore flashing complete. Reboot with: fastboot reboot"

# Victorious Framework
