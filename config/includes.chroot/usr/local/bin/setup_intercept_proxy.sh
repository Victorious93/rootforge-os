#!/usr/bin/env bash
# RootForge OS — intercepting proxy toolkit
# Victorious Framework
#
# Installs mitmproxy and automates the part that's usually the manual,
# error-prone half of setting one up against Android: pushing the proxy's
# CA cert into the device's SYSTEM trust store (not just user trust store —
# most apps built against a modern targetSdk ignore user-added CAs since
# Android 7, so a rooted push straight into /system/etc/security/cacerts is
# what actually makes interception work against real apps).
#
# Usage: ./setup_intercept_proxy.sh [install|trust-cert|start] [device-serial]

set -euo pipefail

CMD="${1:-install}"
SERIAL="${2:-}"
ADB="adb"
[[ -n "$SERIAL" ]] && ADB="adb -s $SERIAL"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/intercept_proxy_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[intercept-proxy] $*" | tee -a "$LOG_FILE"; }

case "$CMD" in
  install)
    log "Installing mitmproxy"
    command -v pipx >/dev/null 2>&1 || sudo apt-get install -y pipx | tee -a "$LOG_FILE"
    pipx install mitmproxy 2>&1 | tee -a "$LOG_FILE" || pip install --user mitmproxy 2>&1 | tee -a "$LOG_FILE"
    log "Installed. Run 'mitmproxy' once (even briefly, ctrl-c after) to generate"
    log "  ~/.mitmproxy/mitmproxy-ca-cert.pem, then run: $0 trust-cert"
    ;;

  trust-cert)
    CA_CERT="$HOME/.mitmproxy/mitmproxy-ca-cert.pem"
    [[ -f "$CA_CERT" ]] || { echo "No CA cert at $CA_CERT — run mitmproxy once first to generate it." >&2; exit 1; }

    $ADB wait-for-device
    log "Computing Android-style cert hash (subject_hash_old) for the filename"
    HASH="$(openssl x509 -inform PEM -subject_hash_old -in "$CA_CERT" | head -1)"
    DEVICE_CERT_NAME="${HASH}.0"
    log "Cert will install as $DEVICE_CERT_NAME"

    TMP_PEM="/tmp/${DEVICE_CERT_NAME}"
    openssl x509 -inform PEM -in "$CA_CERT" -out "$TMP_PEM" -outform PEM

    log "Pushing to device and remounting /system writable"
    $ADB root
    sleep 1
    $ADB remount
    $ADB push "$TMP_PEM" "/system/etc/security/cacerts/$DEVICE_CERT_NAME" 2>>"$LOG_FILE"
    $ADB shell chmod 644 "/system/etc/security/cacerts/$DEVICE_CERT_NAME"
    rm -f "$TMP_PEM"

    log "Installed to system trust store. Reboot the device for it to take effect:"
    log "  $ADB reboot"
    log "[Certain] this requires the device to have a writable /system (rooted AVD"
    log "  from setup_rooted_avd.sh, or a rooted physical device) — a stock"
    log "  unrooted device can only take the cert into the user trust store via"
    log "  Settings, which many modern apps ignore by design."
    ;;

  start)
    PORT="${3:-8080}"
    log "Starting mitmproxy on port $PORT (interactive UI)"
    log "Point the device's Wi-Fi proxy settings at this machine's IP:$PORT,"
    log "  or route traffic through it with a transparent redirect if the device"
    log "  is tethered/on the same network segment."
    mitmproxy --listen-port "$PORT"
    ;;

  *)
    echo "Usage: $0 [install|trust-cert|start] [device-serial]" >&2
    exit 1
    ;;
esac

# Victorious Framework
