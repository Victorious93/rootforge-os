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
# Usage: ./setup_intercept_proxy.sh install
#        ./setup_intercept_proxy.sh trust-cert [device-serial]
#        ./setup_intercept_proxy.sh start [listen-port]     (default 8080)

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
    # -noout matters: without it openssl prints the hash *and* the whole
    # certificate, and `head -1` then closes the pipe under it. That happens
    # to work only because the cert fits in the pipe buffer — a larger one
    # would SIGPIPE openssl and `pipefail` would turn that into a failed
    # assignment. Ask for just the hash.
    HASH="$(openssl x509 -inform PEM -subject_hash_old -noout -in "$CA_CERT")"
    [[ -n "$HASH" ]] || { echo "Could not compute a subject hash for $CA_CERT — is it a valid PEM certificate?" >&2; exit 1; }
    DEVICE_CERT_NAME="${HASH}.0"
    log "Cert will install as $DEVICE_CERT_NAME"

    # A fixed /tmp path named after a predictable hash is a collision (and,
    # on a shared box, a symlink) hazard.
    TMP_DIR="$(mktemp -d)"
    trap 'rm -rf "$TMP_DIR"' EXIT
    TMP_PEM="$TMP_DIR/$DEVICE_CERT_NAME"
    openssl x509 -inform PEM -in "$CA_CERT" -out "$TMP_PEM" -outform PEM

    log "Pushing to device and remounting /system writable"
    # `adb root` exits 0 even when it refuses ("adbd cannot run as root in
    # production builds"), so the failure only showed up as a confusing
    # permission error from the push several lines later. Check that root
    # actually took, and that the remount succeeded, before pushing.
    $ADB root 2>&1 | tee -a "$LOG_FILE" || true
    sleep 1
    CURRENT_USER="$($ADB shell id -u 2>/dev/null | tr -d '\r' || true)"
    if [[ "$CURRENT_USER" != "0" ]]; then
      echo "adbd is not running as root on this device (id -u reported '${CURRENT_USER:-unknown}')." >&2
      echo "Installing into the SYSTEM trust store needs root: use a rooted device, or a" >&2
      echo "rooted AVD from setup_rooted_avd.sh. A production build cannot do this." >&2
      exit 1
    fi
    if ! $ADB remount 2>&1 | tee -a "$LOG_FILE"; then
      echo "adb remount failed — /system is not writable on this device." >&2
      echo "On recent Android you may need 'adb disable-verity' and a reboot first." >&2
      exit 1
    fi
    $ADB push "$TMP_PEM" "/system/etc/security/cacerts/$DEVICE_CERT_NAME" 2>>"$LOG_FILE"
    $ADB shell chmod 644 "/system/etc/security/cacerts/$DEVICE_CERT_NAME"

    log "Installed to system trust store. Reboot the device for it to take effect:"
    log "  $ADB reboot"
    log "[Certain] this requires the device to have a writable /system (rooted AVD"
    log "  from setup_rooted_avd.sh, or a rooted physical device) — a stock"
    log "  unrooted device can only take the cert into the user trust store via"
    log "  Settings, which many modern apps ignore by design."
    ;;

  start)
    # This read the port from $3, matching the [device-serial] slot in the
    # old usage line rather than anything a caller would pass — so
    # `setup_intercept_proxy.sh start 9090` silently listened on 8080.
    # `start` has no use for a device serial; take the port as $2.
    PORT="${2:-8080}"
    [[ "$PORT" =~ ^[0-9]+$ && "$PORT" -ge 1 && "$PORT" -le 65535 ]] \
      || { echo "Invalid port '$PORT' (expected 1-65535)" >&2; exit 1; }
    log "Starting mitmproxy on port $PORT (interactive UI)"
    log "Point the device's Wi-Fi proxy settings at this machine's IP:$PORT,"
    log "  or route traffic through it with a transparent redirect if the device"
    log "  is tethered/on the same network segment."
    mitmproxy --listen-port "$PORT"
    ;;

  *)
    echo "Usage: $0 install | trust-cert [device-serial] | start [listen-port]" >&2
    exit 1
    ;;
esac

# Victorious Framework
