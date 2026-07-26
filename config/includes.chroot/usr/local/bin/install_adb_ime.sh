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
    ADB="adb"
    [[ -n "$SERIAL" ]] && ADB="adb -s $SERIAL"

    APK_PATH="$CACHE_DIR/ADBKeyboard.apk"
    if [[ ! -f "$APK_PATH" ]]; then
      log "Fetching latest ADBKeyboard release"
      command -v jq >/dev/null 2>&1 || { echo "jq is required (apt install jq)" >&2; exit 1; }
      URL="$(curl -sSL https://api.github.com/repos/senzhk/ADBKeyBoard/releases/latest \
        | jq -r '.assets[] | select(.name | test("apk$")) | .browser_download_url' | head -1)"
      if [[ -z "$URL" || "$URL" == "null" ]]; then
        log "Could not resolve a release APK automatically — check"
        log "  https://github.com/senzhk/ADBKeyBoard/releases and place the APK at $APK_PATH manually."
        exit 1
      fi
      curl -sSL -o "$APK_PATH" "$URL"
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
    ADB="adb"
    [[ -n "$SERIAL" ]] && ADB="adb -s $SERIAL"

    CURRENT_IME="$($ADB shell settings get secure default_input_method 2>/dev/null | tr -d '\r')"
    if [[ "$CURRENT_IME" != "$IME_ID" ]]; then
      log "Scripted IME isn't currently active (active: $CURRENT_IME) — run 'install' first, or 'ime set $IME_ID' if it's already installed."
      exit 1
    fi

    log "Injecting text via broadcast"
    $ADB shell am broadcast -a ADB_INPUT_TEXT --es msg "$TEXT" 2>&1 | tee -a "$LOG_FILE"
    ;;

  *)
    echo "Usage: $0 install|type [args] [device-serial]" >&2
    exit 1
    ;;
esac

# Victorious Framework
