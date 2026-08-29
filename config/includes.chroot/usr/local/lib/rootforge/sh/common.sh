#!/usr/bin/env bash
# RootForge OS — shared shell helpers
# Victorious Framework | Origin Source Labs
#
# Sourced by the usr/local/bin/*.sh scripts. Deliberately small: it holds
# only the pieces that were being reimplemented (inconsistently) in more
# than one script, where the inconsistency was itself a bug —
#
#   rf_confirm     the typed-confirmation gate. Previously copy-pasted as a
#                  bare `read -r -p` in flash_patched_boot.sh,
#                  unlock_bootloader.sh and restore_partitions.sh. That
#                  form breaks under fleet_orchestrate.sh, which redirects
#                  each child script's stdout to a per-device log: the
#                  prompt goes into the log file where nobody sees it and
#                  the run looks hung forever. rf_confirm talks to /dev/tty
#                  so the prompt is always visible, and fails closed with a
#                  clear message when there is no terminal at all.
#   rf_sha256_*    backup/restore integrity. backup_partitions.sh wrote a
#                  manifest with `du -h` sizes only, so restore_partitions.sh
#                  had no way to notice a truncated or corrupted .img before
#                  flashing it to a device.
#   rf_device_serials  one implementation of "which devices are connected".
#                  The old inline `adb devices | grep -qv 'List of devices'`
#                  matched the trailing blank line and reported a device
#                  even when none was attached.
#
# Guard against double-sourcing: scripts may source this directly and also
# via another helper.
[ -n "${ROOTFORGE_COMMON_SH_LOADED:-}" ] && return 0
ROOTFORGE_COMMON_SH_LOADED=1

# --- confirmation --------------------------------------------------------

# rf_confirm <word> <line>...
#
# Prints the given lines, then requires the operator to type <word> exactly.
# Returns 0 on match, 1 otherwise — callers decide how to abort so their own
# logging stays intact.
#
# ROOTFORGE_ASSUME_YES=1 skips the prompt. That exists for one specific
# caller (fleet_orchestrate.sh, which collects a single fleet-wide typed
# confirmation up front and then drives N devices non-interactively) and is
# logged loudly wherever it takes effect. It is not a general "make the
# safety gate go away" switch.
rf_confirm() {
  local word="$1"; shift
  local line

  for line in "$@"; do
    printf '%s\n' "$line" >&2
  done

  if [ "${ROOTFORGE_ASSUME_YES:-0}" = "1" ]; then
    printf 'ROOTFORGE_ASSUME_YES=1 — proceeding without the typed "%s" gate.\n' "$word" >&2
    return 0
  fi

  # stdout may be redirected to a log file (fleet_orchestrate.sh does
  # exactly this), so prompt on the controlling terminal instead. No
  # terminal means no operator, and a destructive step must not proceed
  # unattended by default.
  if [ ! -r /dev/tty ]; then
    printf 'No terminal available to confirm on — refusing to continue.\n' >&2
    printf 'Run this interactively, or set ROOTFORGE_ASSUME_YES=1 if you really mean to automate it.\n' >&2
    return 1
  fi

  local reply=""
  printf 'Type %s to proceed: ' "$word" > /dev/tty
  IFS= read -r reply < /dev/tty || reply=""

  [ "$reply" = "$word" ]
}

# --- integrity -----------------------------------------------------------

# rf_sha256_file <path> — print the bare hex digest (no filename column).
rf_sha256_file() {
  sha256sum -- "$1" | awk '{print $1}'
}

# rf_sha256_verify <path> <expected-hex> — 0 if it matches, 1 if not.
rf_sha256_verify() {
  local actual
  actual="$(rf_sha256_file "$1")" || return 1
  [ "$actual" = "$2" ]
}

# --- device enumeration --------------------------------------------------

# rf_adb_serials [--] — print one serial per line for devices in the `device`
# state. Devices reporting `unauthorized`, `offline` or `recovery` are
# deliberately excluded: every caller here wants a device it can actually
# shell into.
#
# `adb devices` prints a "List of devices attached" header and a trailing
# blank line. Filtering with `grep -v` on the header alone matches that
# blank line and reports a phantom device, which is the bug this replaces.
rf_adb_serials() {
  adb devices 2>/dev/null | awk '$2 == "device" { print $1 }'
}

# rf_fastboot_serials — one serial per line for devices in fastboot mode.
rf_fastboot_serials() {
  fastboot devices 2>/dev/null | awk 'NF >= 1 && $1 != "" { print $1 }'
}

rf_have_adb_device() {
  [ -n "$(rf_adb_serials | head -n 1)" ]
}

rf_have_fastboot_device() {
  [ -n "$(rf_fastboot_serials | head -n 1)" ]
}

# --- misc ----------------------------------------------------------------

# rf_require_cmd <cmd> <install hint> — exit 1 with a useful message rather
# than letting `set -e` kill the script on a bare "command not found".
rf_require_cmd() {
  command -v "$1" >/dev/null 2>&1 && return 0
  printf '%s not found — %s\n' "$1" "$2" >&2
  exit 1
}
