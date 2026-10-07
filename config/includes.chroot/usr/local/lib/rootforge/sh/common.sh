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
#   rf_rootforge / rf_device_profile_json  a bridge into
#                  rootforge.core.device's vendor/slot/lock-state
#                  profiling, so flash_patched_boot.sh, backup_partitions.sh
#                  and unlock_bootloader.sh can share one tested detection
#                  path instead of each re-deriving it via ad hoc getvar/
#                  grep. Every caller falls back to its own original direct
#                  query when this comes back empty, so a missing/broken
#                  Python install degrades detection accuracy, not script
#                  availability. See docs/archive/IMPLEMENTATION_PLAN_P0-P3_2026-10-07.md P1 item 5.
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

# rf_fastboot_wait [serial] [timeout_seconds] — wait (bounded) for a fastboot
# device and print its serial.
#
# `fastboot wait-for-device` is not a fastboot command (upstream fastboot.cpp
# has no such verb), so scripts that called it were issuing an invalid
# command. This polls `fastboot devices` instead.
#
# With a serial: succeeds only when that exact serial is listed.
# Without one: succeeds only when exactly one device is listed; two or more
# is ambiguous and fails immediately rather than guessing. Exit codes:
#   0 found (serial on stdout)   1 timed out   2 ambiguous
# The default timeout is ROOTFORGE_FASTBOOT_WAIT or 30 seconds.
rf_fastboot_wait() {
  local want="${1:-}" timeout="${2:-${ROOTFORGE_FASTBOOT_WAIT:-30}}"
  local waited=0 serials=() s
  while :; do
    mapfile -t serials < <(rf_fastboot_serials)
    if [ -n "$want" ]; then
      for s in "${serials[@]}"; do
        if [ "$s" = "$want" ]; then
          printf '%s\n' "$want"
          return 0
        fi
      done
    else
      case "${#serials[@]}" in
        1) printf '%s\n' "${serials[0]}"; return 0 ;;
        0) ;;
        *)
          printf 'More than one device is in fastboot mode (%s) — pass a serial.\n' \
            "${serials[*]}" >&2
          return 2
          ;;
      esac
    fi
    [ "$waited" -ge "$timeout" ] && break
    sleep 1
    waited=$((waited + 1))
  done
  if [ -n "$want" ]; then
    printf 'Device %s did not appear in fastboot mode within %ss.\n' "$want" "$timeout" >&2
  else
    printf 'No device appeared in fastboot mode within %ss.\n' "$timeout" >&2
  fi
  return 1
}

# rf_adb_wait_boot <serial> [timeout_seconds] — wait (bounded) for one
# specific device to reconnect over adb AND report sys.boot_completed=1.
# An adb connection alone does not prove the system finished booting, and an
# unqualified `adb wait-for-device` can attach to a different phone and never
# times out. Exit codes:
#   0 boot completed   1 never reconnected   2 reconnected but boot not completed
# The default timeout is ROOTFORGE_BOOT_WAIT or 180 seconds.
rf_adb_wait_boot() {
  local serial="$1" timeout="${2:-${ROOTFORGE_BOOT_WAIT:-180}}"
  local waited=0 reconnected=0 state done_flag
  local bound=()
  command -v timeout >/dev/null 2>&1 && bound=(timeout 10)
  while :; do
    state="$("${bound[@]}" adb -s "$serial" get-state 2>/dev/null || true)"
    if [ "$state" = "device" ]; then
      reconnected=1
      done_flag="$("${bound[@]}" adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r[:space:]' || true)"
      if [ "$done_flag" = "1" ]; then
        return 0
      fi
    fi
    [ "$waited" -ge "$timeout" ] && break
    sleep 1
    waited=$((waited + 1))
  done
  [ "$reconnected" -eq 1 ] && return 2
  return 1
}

# --- rootforge CLI bridge -------------------------------------------------

# rf_rootforge <args...> — run the `rootforge` CLI from a shell script.
#
# Prefers the installed `rootforge` shim on PATH (the real, on-device
# layout). Falls back to invoking the Python package directly with
# PYTHONPATH pointed at this file's own location — the same checkout-
# relative trick runner.py's find_script() uses in the other direction —
# so this also works from a git checkout and from tests/run-tests.sh,
# where nothing is actually installed to /usr/local.
rf_rootforge() {
  if command -v rootforge >/dev/null 2>&1; then
    rootforge "$@"
    return $?
  fi
  local lib_dir
  lib_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
  PYTHONPATH="$lib_dir:${PYTHONPATH:-}" python3 -m rootforge.core.cli "$@"
}

# rf_device_profile_json [serial] — print the `rootforge device info --json`
# profile for one device on stdout, or nothing (and a non-zero exit) on any
# failure: jq missing, python3/the rootforge package unavailable, or
# `rootforge device info` itself refusing to pick a device (none attached,
# or more than one with no serial to disambiguate).
#
# `rootforge device info` also exits non-zero — while still printing JSON —
# for a device it *did* resolve but whose vendor is unsupported. Callers
# that need to tell "couldn't resolve a device" from "resolved one, but it's
# refused" apart must check whether stdout is non-empty, not the exit
# status alone; a bare `command || fallback` on this function conflates the
# two, so use it only where both outcomes should fall back the same way
# (e.g. re-deriving from a direct adb/fastboot query).
rf_device_profile_json() {
  rf_require_cmd jq "install jq (apt install jq)"
  rf_rootforge device info "$@" --json 2>/dev/null
}

# --- secrets -------------------------------------------------------------

# rf_shell_quote <string> — print the string single-quoted and safe to
# re-source from a shell script.
#
# setup_ai_tools.sh writes API keys into ~/.rootforge/ai-keys.env, which the
# shell rc files source on every startup. It used to emit them as
# `export VAR='$key'` with no escaping, so a key containing a single quote
# terminated the quoting early: at best the whole file became a syntax error
# and *no* keys loaded, at worst the remainder of the key ran as shell
# commands in every new shell. Keys get pasted from password managers and
# passed in by automation, so "the user typed it themselves" is not a
# safety argument.
#
# Emits POSIX-portable '...'\''...' rather than bash's printf %q, whose
# $'...' form the file's POSIX-sh readers would not understand.
rf_shell_quote() {
  local q="'\\''"
  printf "'%s'" "${1//\'/$q}"
}

# rf_write_private <path> — read stdin and write it to <path> with mode 0600
# from the moment it exists.
#
# The rewrite-through-a-temp-file pattern used to create that temp at the
# default umask (0644), fill it with every stored key, then `mv` it over the
# real file — which inherited 0644 — and only then chmod 0600. On a
# multi-user box that is a real window in which every key is world-readable.
rf_write_private() {
  local path="$1"
  local old_umask
  old_umask="$(umask)"
  umask 077
  cat > "$path"
  umask "$old_umask"
  # Belt and braces: an existing file keeps its own mode through a
  # redirect, so umask alone is not enough when the file already exists.
  chmod 600 "$path"
}

# --- logs ------------------------------------------------------------------

# rf_ensure_execution_id — set and export ROOTFORGE_EXECUTION_ID in THIS shell.
#
# `rootforge` sets it for the command it runs, so a CLI invocation, the script
# it wraps and any `rootforge` call that script makes all carry one ID. A
# script started directly generates its own. An inherited value is used only if
# it is a plain 4-32 character alphanumeric token: it is written into log
# files, so anything else is ignored rather than trusted.
#
# Call it directly, never inside $( ): a command substitution runs in a
# subshell, so the export would be lost and child processes would not inherit
# the ID.
rf_ensure_execution_id() {
  if ! [[ "${ROOTFORGE_EXECUTION_ID:-}" =~ ^[A-Za-z0-9]{4,32}$ ]]; then
    ROOTFORGE_EXECUTION_ID="$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')"
  fi
  export ROOTFORGE_EXECUTION_ID
}

# rf_private_file <path> — make sure <path> exists, mode 0600 from creation.
#
# Logs and reports carry device serials, partition names and local paths, so
# they are not for other users of the machine. The mode is set when the file
# is created (umask in a subshell) rather than chmod'ed afterwards, so there is
# no window at the default umask; an existing file keeps the mode it has. Under
# `sudo` a root-created 0600 file in the invoking user's home would be
# unreadable by that user, so a new file is handed to $SUDO_USER (best effort).
rf_private_file() {
  local path="$1"
  [[ -n "$path" ]] || return 1
  mkdir -p "$(dirname "$path")" || return 1
  [[ -e "$path" ]] && return 0
  ( umask 077; : > "$path" ) || return 1
  if [[ "$(id -u)" == "0" && -n "${SUDO_USER:-}" && "$SUDO_USER" != "root" ]]; then
    chown "$SUDO_USER" "$path" 2>/dev/null || true
  fi
  return 0
}

# rf_log_init <path> — rf_private_file plus a first line naming this run:
#   # rootforge execution <id>: <script> started <UTC time>
# so a script log can be matched to the CLI's JSON-lines log for the same run
# (rootforge-<command>-<id>.jsonl).
rf_log_init() {
  local path="$1"
  rf_private_file "$path" || return 1
  rf_ensure_execution_id
  printf '# rootforge execution %s: %s started %s\n' \
    "$ROOTFORGE_EXECUTION_ID" "$(basename "${0:-script}")" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$path"
}

# --- misc ----------------------------------------------------------------

# rf_require_cmd <cmd> <install hint> — exit 1 with a useful message rather
# than letting `set -e` kill the script on a bare "command not found".
rf_require_cmd() {
  command -v "$1" >/dev/null 2>&1 && return 0
  printf '%s not found — %s\n' "$1" "$2" >&2
  exit 1
}

# --- Android SDK provisioning -----------------------------------------------

# The one pinned Android cmdline-tools archive. The SHA-256 was computed from a
# download whose size and SHA-1 matched Google's own repository2-3.xml entry
# for this exact file (cmdline-tools;12.0, SHA-1 d313adb7...f8f8, 153607504
# bytes), because the manifest publishes only a SHA-1.
RF_CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
RF_CMDLINE_TOOLS_SHA256="2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258"

# rf_host_arch — arm64 | amd64 | unsupported:<machine>, from the running kernel.
rf_host_arch() {
  case "$(uname -m)" in
    aarch64|arm64) echo arm64 ;;
    x86_64|amd64)  echo amd64 ;;
    *)             echo "unsupported:$(uname -m)" ;;
  esac
}

# rf_fetch_verified <url> <dest> <sha256> — download to a temp file beside
# <dest>, rename into place only if the SHA-256 matches. A mismatch removes the
# download and fails: nothing unverified is ever left at <dest>.
rf_fetch_verified() {
  local url="$1" dest="$2" want="$3" tmp actual
  tmp="$(mktemp "$dest.part.XXXXXX")" || return 1
  if ! curl -fsSL -o "$tmp" "$url"; then
    rm -f "$tmp"
    printf 'Download failed: %s\n' "$url" >&2
    return 1
  fi
  actual="$(sha256sum "$tmp" | awk '{print $1}')"
  if [ "$actual" != "$want" ]; then
    rm -f "$tmp"
    printf 'SHA-256 mismatch for %s\n  expected: %s\n  actual:   %s\n' "$url" "$want" "$actual" >&2
    return 1
  fi
  mv -f "$tmp" "$dest"
}

# rf_runtime_capabilities_json — what this runtime can actually do, probed
# rather than assumed. Root does not imply any of these: a rooted Android
# chroot still runs on the host's Android kernel, PRoot is ptrace emulation,
# and a binary for another CPU does not run just because a tool exists.
# ROOTFORGE_DEV_ROOT (default /dev) is the seam tests use.
rf_runtime_capabilities_json() {
  local dev="${ROOTFORGE_DEV_ROOT:-/dev}" arch flavor="unknown" kvm=false loop=false tun=false usb=false native=false
  arch="$(rf_host_arch)"
  if [ -r /etc/rootforge/build-info ]; then
    flavor="$(sed -n 's/^flavor=//p' /etc/rootforge/build-info | head -n 1)"
    [ -n "$flavor" ] || flavor="unknown"
  fi
  [ -c "$dev/kvm" ] && [ -r "$dev/kvm" ] && [ -w "$dev/kvm" ] && kvm=true
  [ -e "$dev/loop-control" ] && loop=true
  [ -c "$dev/net/tun" ] && tun=true
  [ -d "$dev/bus/usb" ] && usb=true
  [ "$arch" = "amd64" ] && native=true
  printf '{"host_arch":"%s","container":"%s","kvm":%s,"loop_devices":%s,"tun":%s,"usb_bus":%s,"google_sdk_binaries_native":%s,"android_emulator_available":%s}\n' \
    "$arch" "$flavor" "$kvm" "$loop" "$tun" "$usb" "$native" "$native"
}

# --- downloads -----------------------------------------------------------

# rf_download_cached <url> <destination> [min_bytes] — fetch <url> to
# <destination>, reusing an existing file only if it is plausibly complete.
#
# The pattern this replaces was:
#
#   if [[ -f "$LOCAL" ]]; then log "using cached"; else curl -fsSL -o "$LOCAL" "$URL"; fi
#
# curl writes straight to the final path, so a download interrupted by
# Ctrl-C, a dropped connection or a full disk leaves a partial file *at the
# cache path*. Every later run then takes the `-f` branch, logs "using
# cached", and hands the truncated file to the device — verified: a 9-byte
# stub was pushed to /data/local/tmp and installed as a Magisk module.
# Nothing downstream notices, because a zip that will not open is a device-
# side failure, not a script-side one.
#
# Two changes fix it. Download to a sibling temp file and rename only after
# curl succeeds, so the cache path never holds a partial file; and treat a
# cached file below min_bytes as absent, which recovers a cache already
# poisoned by the old code.
rf_download_cached() {
  local url="$1" dest="$2" min_bytes="${3:-1024}"
  local size=0
  if [ -f "$dest" ]; then
    size="$(wc -c < "$dest" 2>/dev/null || echo 0)"
    if [ "$size" -ge "$min_bytes" ]; then
      return 0
    fi
    printf 'Cached file %s is only %s bytes — treating it as an incomplete download and refetching.\n' \
      "$dest" "$size" >&2
    rm -f "$dest"
  fi

  local tmp="$dest.part.$$"
  if ! curl -fsSL -o "$tmp" "$url"; then
    rm -f "$tmp"
    printf 'Download failed: %s\n' "$url" >&2
    return 1
  fi

  size="$(wc -c < "$tmp" 2>/dev/null || echo 0)"
  if [ "$size" -lt "$min_bytes" ]; then
    rm -f "$tmp"
    printf 'Downloaded %s bytes from %s — below the %s-byte minimum, refusing to cache it.\n' \
      "$size" "$url" "$min_bytes" >&2
    return 1
  fi

  mv -f "$tmp" "$dest"
}
