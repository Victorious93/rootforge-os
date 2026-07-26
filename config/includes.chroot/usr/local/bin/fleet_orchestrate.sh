#!/usr/bin/env bash
# RootForge OS — device-fleet automation orchestrator
# Victorious Framework
#
# Every other RootForge script targets one device at a time via a serial
# arg — deliberately, since unlock/flash are destructive operations you
# want to reason about individually. This script is the exception: it runs
# a CHOSEN non-destructive-by-default operation across every currently
# connected device in sequence, with a per-device log and a summary table.
# Destructive operations (unlock, flash) are supported but require an
# extra explicit flag — this script doesn't lower the bar those scripts
# already set individually.
#
# Usage: ./fleet_orchestrate.sh <operation> [--allow-destructive] [-- <extra args passed to the underlying script>]
#   operation: lint | root-detect | backup | module-install:<module_id> | unlock | flash:<image>

set -euo pipefail

OPERATION="${1:?Usage: fleet_orchestrate.sh <operation> [--allow-destructive] [-- extra-args]}"
shift || true

ALLOW_DESTRUCTIVE=0
EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --allow-destructive) ALLOW_DESTRUCTIVE=1 ;;
    --) shift; EXTRA_ARGS=("$@"); break ;;
  esac
  shift
done

DESTRUCTIVE_OPS="unlock flash"
OP_NAME="${OPERATION%%:*}"
if echo "$DESTRUCTIVE_OPS" | grep -qw "$OP_NAME" && [[ $ALLOW_DESTRUCTIVE -eq 0 ]]; then
  echo "'$OP_NAME' is destructive across a whole fleet — pass --allow-destructive to confirm you mean it for every connected device, not just one." >&2
  exit 1
fi

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOTFORGE_HOME/logs"
STAMP="$(date +%Y%m%d_%H%M%S)"
mkdir -p "$LOG_DIR"
SUMMARY="$LOG_DIR/fleet_${OP_NAME}_${STAMP}_summary.md"
log() { echo "[fleet] $*"; }

log "Enumerating connected devices (adb + fastboot)"
mapfile -t ADB_SERIALS < <(adb devices 2>/dev/null | grep -v 'List of devices' | grep 'device$' | cut -f1)
mapfile -t FASTBOOT_SERIALS < <(fastboot devices 2>/dev/null | cut -f1)
ALL_SERIALS=("${ADB_SERIALS[@]}" "${FASTBOOT_SERIALS[@]}")

if [[ ${#ALL_SERIALS[@]} -eq 0 ]]; then
  echo "No devices found in adb or fastboot mode." >&2
  exit 1
fi

log "Found ${#ALL_SERIALS[@]} device(s): ${ALL_SERIALS[*]}"

echo "# Fleet operation: $OPERATION — $STAMP" > "$SUMMARY"
echo "" >> "$SUMMARY"
echo "| Serial | Result | Log |" >> "$SUMMARY"
echo "|---|---|---|" >> "$SUMMARY"

for serial in "${ALL_SERIALS[@]}"; do
  DEV_LOG="$LOG_DIR/fleet_${OP_NAME}_${STAMP}_${serial}.log"
  log "=== $serial ==="
  RESULT="FAIL"

  case "$OP_NAME" in
    lint)
      # lint_module.sh operates on local files, not a device — skip per-device,
      # note it as not applicable rather than silently pretending it ran.
      echo "N/A (lint_module.sh targets local files, not a device)" > "$DEV_LOG"
      RESULT="N/A"
      ;;
    root-detect)
      if bash "$SCRIPT_DIR/check_root_detection.sh" "$serial" "${EXTRA_ARGS[@]}" > "$DEV_LOG" 2>&1; then RESULT="OK"; fi
      ;;
    backup)
      if bash "$SCRIPT_DIR/backup_partitions.sh" "$serial" "$serial" "${EXTRA_ARGS[@]}" > "$DEV_LOG" 2>&1; then RESULT="OK"; fi
      ;;
    module-install)
      MODULE_ID="${OPERATION#*:}"
      if bash "$SCRIPT_DIR/build_magisk_module.sh" "$MODULE_ID" --install "${EXTRA_ARGS[@]}" > "$DEV_LOG" 2>&1; then RESULT="OK"; fi
      ;;
    unlock)
      if bash "$SCRIPT_DIR/unlock_bootloader.sh" "$serial" "${EXTRA_ARGS[@]}" > "$DEV_LOG" 2>&1; then RESULT="OK"; fi
      ;;
    flash)
      IMG="${OPERATION#*:}"
      if bash "$SCRIPT_DIR/flash_patched_boot.sh" "$IMG" boot "$serial" "${EXTRA_ARGS[@]}" > "$DEV_LOG" 2>&1; then RESULT="OK"; fi
      ;;
    *)
      echo "Unknown operation: $OP_NAME" >&2
      exit 1
      ;;
  esac

  log "$serial: $RESULT (log: $DEV_LOG)"
  echo "| $serial | $RESULT | \`$DEV_LOG\` |" >> "$SUMMARY"
done

echo ""
log "Summary: $SUMMARY"
cat "$SUMMARY"

# Victorious Framework
