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

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

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

OP_NAME="${OPERATION%%:*}"

# "${OPERATION#*:}" returns the whole string when there is no colon, so
# `fleet_orchestrate.sh flash` used to pass the literal word "flash" as the
# image path (and "module-install" as the module id) to every device.
# Require the argument these two operations cannot work without.
OP_ARG=""
case "$OPERATION" in
  *:*) OP_ARG="${OPERATION#*:}" ;;
esac
case "$OP_NAME" in
  module-install)
    [[ -n "$OP_ARG" ]] || { echo "module-install needs a module id: module-install:<module_id>" >&2; exit 1; }
    ;;
  flash)
    [[ -n "$OP_ARG" ]] || { echo "flash needs an image path: flash:<image.img>" >&2; exit 1; }
    [[ -f "$OP_ARG" ]] || { echo "Image not found: $OP_ARG" >&2; exit 1; }
    ;;
esac

IS_DESTRUCTIVE=0
case "$OP_NAME" in
  unlock|flash) IS_DESTRUCTIVE=1 ;;
esac
if [[ $IS_DESTRUCTIVE -eq 1 && $ALLOW_DESTRUCTIVE -eq 0 ]]; then
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
mapfile -t ADB_SERIALS < <(rf_adb_serials)
mapfile -t FASTBOOT_SERIALS < <(rf_fastboot_serials)
ALL_SERIALS=()
[[ ${#ADB_SERIALS[@]} -gt 0 ]] && ALL_SERIALS+=("${ADB_SERIALS[@]}")
[[ ${#FASTBOOT_SERIALS[@]} -gt 0 ]] && ALL_SERIALS+=("${FASTBOOT_SERIALS[@]}")

if [[ ${#ALL_SERIALS[@]} -eq 0 ]]; then
  echo "No devices found in adb or fastboot mode." >&2
  exit 1
fi

log "Found ${#ALL_SERIALS[@]} device(s): ${ALL_SERIALS[*]}"

# The per-device scripts each gate destructive work behind a typed
# confirmation read from the terminal. This loop redirects their stdout into
# a per-device log, so before rf_confirm existed those prompts vanished into
# the log file and the run sat waiting on input the operator could not see.
# Collect one fleet-wide confirmation here instead, then let the children run
# unattended — a deliberate, logged handover, not a silent bypass.
if [[ $IS_DESTRUCTIVE -eq 1 ]]; then
  if ! rf_confirm "$(printf '%s' "$OP_NAME" | tr '[:lower:]' '[:upper:]')" \
      "" \
      "About to run the destructive operation '$OPERATION' against ALL ${#ALL_SERIALS[@]} connected device(s):" \
      "  ${ALL_SERIALS[*]}" \
      "Each device's own confirmation gate will be satisfied by this single answer." \
      "unlock wipes user data; flash overwrites the boot partition."; then
    echo "Fleet confirmation not given — aborting. Nothing was run." >&2
    exit 1
  fi
  # Consumed by the child scripts via common.sh's rf_confirm.
  export ROOTFORGE_ASSUME_YES=1
  log "Fleet-wide confirmation accepted — child scripts will not prompt individually."
fi

echo "# Fleet operation: $OPERATION — $STAMP" > "$SUMMARY"
echo "" >> "$SUMMARY"
echo "| Serial | Result | Log |" >> "$SUMMARY"
echo "|---|---|---|" >> "$SUMMARY"

FAILED_DEVICES=()
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
      # --serial matters here: without it adb refuses to act ("more than one
      # device/emulator") the moment a second device is plugged in, which is
      # precisely the situation this script exists for.
      if bash "$SCRIPT_DIR/build_magisk_module.sh" "$OP_ARG" --install --serial "$serial" "${EXTRA_ARGS[@]}" > "$DEV_LOG" 2>&1; then RESULT="OK"; fi
      ;;
    unlock)
      if bash "$SCRIPT_DIR/unlock_bootloader.sh" "$serial" "${EXTRA_ARGS[@]}" > "$DEV_LOG" 2>&1; then RESULT="OK"; fi
      ;;
    flash)
      if bash "$SCRIPT_DIR/flash_patched_boot.sh" "$OP_ARG" boot "$serial" "${EXTRA_ARGS[@]}" > "$DEV_LOG" 2>&1; then RESULT="OK"; fi
      ;;
    *)
      echo "Unknown operation: $OP_NAME" >&2
      exit 1
      ;;
  esac

  log "$serial: $RESULT (log: $DEV_LOG)"
  echo "| $serial | $RESULT | \`$DEV_LOG\` |" >> "$SUMMARY"
  [[ "$RESULT" == "FAIL" ]] && FAILED_DEVICES+=("$serial")
done

echo ""
log "Summary: $SUMMARY"
cat "$SUMMARY"

# A fleet run that failed on every device used to exit 0, so nothing wrapping
# this script could tell success from total failure.
if [[ ${#FAILED_DEVICES[@]} -gt 0 ]]; then
  log "${#FAILED_DEVICES[@]} of ${#ALL_SERIALS[@]} device(s) failed: ${FAILED_DEVICES[*]}"
  exit 1
fi

# Victorious Framework
