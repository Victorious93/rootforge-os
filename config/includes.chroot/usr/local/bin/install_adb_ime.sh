#!/usr/bin/env bash
# RootForge OS — scripted IME for reliable automated text input
# Victorious Framework
#
# `adb shell input text` chokes on non-ASCII characters, emoji, and some
# special characters, because it goes through the on-screen keyboard's
# input pipeline. The fix the Android testing community actually uses is a
# broadcast-receiver-based IME (the ADBKeyboard pattern): a minimal keyboard
# app that does nothing on-screen but listens for an ADB broadcast intent
# and injects the exact text given, bypassing IME text-processing entirely.
# This script fetches a prebuilt release, installs it, sets it as the
# active IME, and provides a shell helper for scripted use.
#
# Usage: ./install_adb_ime.sh install [device-serial]
#        ./install_adb_ime.sh type "some text" [device-serial]

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

CMD="${1:?Usage: install_adb_ime.sh install|type [args] [device-serial]}"
shift || true

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
CACHE_DIR="$ROOTFORGE_HOME/bin"
mkdir -p "$LOG_DIR" "$CACHE_DIR"
LOG_FILE="$LOG_DIR/adb_ime_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[adb-ime] $*" | tee -a "$LOG_FILE"; }

IME_PACKAGE="com.android.adbkeyboard"
IME_ID="${IME_PACKAGE}/.AdbIME"

case "$CMD" in
  install)
    SERIAL="${1:-}"
    # A flag here used to be taken as a device serial, producing
    # `adb -s --whatever` and a confusing adb error rather than a usage message.
    [[ "$SERIAL" == -* ]] && { echo "Unknown option: $SERIAL" >&2; echo "Usage: $0 install [device-serial]" >&2; exit 1; }
    [[ $# -le 1 ]] || { echo "Unexpected extra argument: ${2}" >&2; exit 1; }
    ADB="adb"
    [[ -n "$SERIAL" ]] && ADB="adb -s $SERIAL"

    APK_PATH="$CACHE_DIR/ADBKeyboard.apk"
    if [[ ! -f "$APK_PATH" ]]; then
      log "Fetching latest ADBKeyboard release"
      command -v jq >/dev/null 2>&1 || { echo "jq is required (apt install jq)" >&2; exit 1; }
      # `first(...)` inside jq rather than `| head -1`: head closing the pipe
      # hands jq a SIGPIPE, which pipefail turns into a failed assignment.
      URL="$(curl -fsSL https://api.github.com/repos/senzhk/ADBKeyBoard/releases/latest \
        | jq -r 'first(.assets[] | select(.name | test("apk$")) | .browser_download_url) // empty')"
      if [[ -z "$URL" || "$URL" == "null" ]]; then
        log "Could not resolve a release APK automatically — check"
        log "  https://github.com/senzhk/ADBKeyBoard/releases and place the APK at $APK_PATH manually."
        exit 1
      fi
    else
      URL=""
    fi
    # Downloads to a temp file and renames on success. Writing straight to
    # $APK_PATH meant an interrupted download stayed there and every later run
    # logged "Using cached APK" and pushed the truncated file to `adb install`.
    if [[ -n "$URL" || ! -f "$APK_PATH" ]]; then
      rf_download_cached "$URL" "$APK_PATH" 32768 || {
        log "Could not obtain a usable ADBKeyboard.apk — nothing was installed."
        exit 1
      }
    else
      log "Using cached APK at $APK_PATH"
    fi

    $ADB wait-for-device
    log "Installing"
    $ADB install -r "$APK_PATH" 2>&1 | tee -a "$LOG_FILE"

    log "Enabling and switching to the scripted IME"
    $ADB shell ime enable "$IME_ID" 2>&1 | tee -a "$LOG_FILE"
    $ADB shell ime set "$IME_ID" 2>&1 | tee -a "$LOG_FILE"

    log "Installed and active. Type with:"
    log "  $0 type \"your text here\" $SERIAL"
    log "Switch back to the normal keyboard when done — this IME has no visible"
    log "  UI, so leaving it active makes manual typing on the device look broken"
    log "  (it isn't — there's just nothing to tap)."
    ;;

  type)
    TEXT="${1:?Usage: install_adb_ime.sh type \"text\" [device-serial]}"
    SERIAL="${2:-}"
    [[ "$SERIAL" == -* ]] && { echo "Unknown option: $SERIAL" >&2; echo "Usage: $0 type \"text\" [device-serial]" >&2; exit 1; }
    ADB="adb"
    [[ -n "$SERIAL" ]] && ADB="adb -s $SERIAL"

    CURRENT_IME="$($ADB shell settings get secure default_input_method 2>/dev/null | tr -d '\r')"
    if [[ "$CURRENT_IME" != "$IME_ID" ]]; then
      log "Scripted IME isn't currently active (active: $CURRENT_IME) — run 'install' first, or 'ime set $IME_ID' if it's already installed."
      exit 1
    fi

    # `adb shell a b c` does not pass an argument vector to the device. It
    # joins the arguments with spaces and hands the result to the device's
    # /system/bin/sh, which then parses it. So the text being typed was parsed
    # as shell source on the phone. Reproduced against a model of that join:
    #
    #   type "it's a test"              -> device sh got an unterminated quote;
    #                                      the broadcast never fired
    #   type "hello; touch /tmp/PWNED"  -> am received only "hello", and touch
    #                                      RAN on the device
    #
    # Even a plain space truncated the message at the first word. That is the
    # exact class of input this script exists to handle, so this was breaking
    # its own stated purpose, not just a hardening gap.
    #
    # ADBKeyboard's ADB_INPUT_B64 action takes the text base64-encoded, which
    # is the documented path for anything non-trivial: the payload is
    # [A-Za-z0-9+/=] and survives the join intact. rf_shell_quote on top costs
    # nothing and keeps the command line well-formed regardless.
    rf_require_cmd base64 "install coreutils"
    TEXT_B64="$(printf '%s' "$TEXT" | base64 -w0)"
    log "Injecting ${#TEXT} character(s) via broadcast (base64-encoded)"
    $ADB shell am broadcast -a ADB_INPUT_B64 --es msg "$(rf_shell_quote "$TEXT_B64")" 2>&1 | tee -a "$LOG_FILE"
    ;;

  *)
    echo "Usage: $0 install|type [args] [device-serial]" >&2
    exit 1
    ;;
esac

# Victorious Framework
