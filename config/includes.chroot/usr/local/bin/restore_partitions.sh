#!/usr/bin/env bash
# RootForge OS — partition restore
# Victorious Framework
#
# Restores a backup produced by backup_partitions.sh via fastboot flash.
# Lists available backups if no timestamp is given.
#
# Usage: ./restore_partitions.sh <device_codename> [backup_timestamp] [device-serial]

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

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
if [[ -f "$BACKUP_DIR/manifest.txt" ]]; then
  tee -a "$LOG_FILE" < "$BACKUP_DIR/manifest.txt"
else
  log "No manifest.txt in this backup."
fi

IMAGES=()
for img in "$BACKUP_DIR"/*.img; do
  [[ -f "$img" ]] && IMAGES+=("$img")
done
[[ ${#IMAGES[@]} -gt 0 ]] || { log "No .img files in $BACKUP_DIR — nothing to restore."; exit 1; }

# Integrity gate. Flashing a truncated or bit-rotted boot/vendor_boot image
# is the one failure mode here that leaves a device unbootable with no
# recourse, and it is entirely detectable beforehand — so verify first and
# refuse rather than discovering it after the write.
SUMS_FILE="$BACKUP_DIR/SHA256SUMS"
if [[ -f "$SUMS_FILE" ]]; then
  log "Verifying image checksums against SHA256SUMS"
  if ( cd "$BACKUP_DIR" && sha256sum -c --quiet SHA256SUMS ) 2>>"$LOG_FILE"; then
    log "All images match their recorded checksums."
  else
    log "CHECKSUM MISMATCH — one or more images in $BACKUP_DIR do not match SHA256SUMS."
    log "Refusing to flash. Re-run the verification by hand to see which:"
    log "  (cd $BACKUP_DIR && sha256sum -c SHA256SUMS)"
    exit 1
  fi
else
  # Backups taken before backup_partitions.sh recorded checksums have no
  # SHA256SUMS file. Say so plainly instead of implying the images were
  # checked.
  log "WARNING: no SHA256SUMS in this backup — image integrity CANNOT be verified."
  log "         (Backups taken by an older backup_partitions.sh predate checksums.)"
fi

if ! rf_confirm RESTORE \
    "" \
    "This will flash ${#IMAGES[@]} image(s) from this backup back to their named" \
    "partitions on the connected device. Confirm the device in fastboot mode is" \
    "actually $CODENAME before proceeding — flashing another device's" \
    "boot/vendor_boot images will likely brick it."; then
  log "Confirmation not given — aborting. Nothing was flashed."
  exit 1
fi

$FASTBOOT wait-for-device

RESTORE_FAILED=()
for img in "${IMAGES[@]}"; do
  part="$(basename "$img" .img)"
  log "Flashing $part <- $img"
  if ! $FASTBOOT flash "$part" "$img" 2>>"$LOG_FILE"; then
    log "FAILED to flash $part — check $LOG_FILE"
    RESTORE_FAILED+=("$part")
  fi
done

# Previously every flash failure was swallowed by `|| log ...`, so a restore
# in which nothing at all was written still printed "complete" and exited 0.
if [[ ${#RESTORE_FAILED[@]} -gt 0 ]]; then
  log "RESTORE INCOMPLETE — these partitions were not flashed: ${RESTORE_FAILED[*]}"
  log "Do NOT reboot into the system until this is resolved; see $LOG_FILE."
  exit 1
fi

log "Restore flashing complete. Reboot with: fastboot reboot"

# Victorious Framework
