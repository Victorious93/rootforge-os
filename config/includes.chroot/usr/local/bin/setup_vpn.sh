#!/usr/bin/env bash
# RootForge OS — VPN quick-connect (WireGuard)
# Victorious Framework
#
# Generates a WireGuard keypair, brings up an interface, and can emit a QR
# code for a peer config (handy for adding a phone as a WireGuard peer
# directly from the terminal, no file transfer needed).
#
# Usage: ./setup_vpn.sh init | up | down | peer-qr <peer-name>

set -euo pipefail

CMD="${1:-init}"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
WG_DIR="$ROOTFORGE_HOME/keys/wireguard"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$WG_DIR" "$LOG_DIR"
chmod 700 "$WG_DIR"
LOG_FILE="$LOG_DIR/vpn_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[vpn] $*" | tee -a "$LOG_FILE"; }

IFACE="wg0"
CONF="/etc/wireguard/${IFACE}.conf"

case "$CMD" in
  init)
    command -v wg >/dev/null 2>&1 || sudo apt-get install -y wireguard wireguard-tools qrencode | tee -a "$LOG_FILE"

    if [[ -f "$WG_DIR/privatekey" ]]; then
      log "Keypair already exists at $WG_DIR — not regenerating (would break existing peers)."
    else
      log "Generating keypair"
      umask 077
      wg genkey | tee "$WG_DIR/privatekey" | wg pubkey > "$WG_DIR/publickey"
    fi
    PUBKEY="$(cat "$WG_DIR/publickey")"
    log "Public key: $PUBKEY"
    log "Add this box as a peer on your VPN server, then create $CONF with the"
    log "  server's endpoint/public key and run: $0 up"
    ;;

  up)
    [[ -f "$CONF" ]] || { echo "$CONF not found — create it (see 'init' output) before bringing the interface up." >&2; exit 1; }
    sudo wg-quick up "$IFACE" 2>&1 | tee -a "$LOG_FILE"
    log "Interface up. Check with: sudo wg show"
    ;;

  down)
    sudo wg-quick down "$IFACE" 2>&1 | tee -a "$LOG_FILE" || log "Interface wasn't up."
    ;;

  peer-qr)
    PEER_NAME="${2:?Usage: setup_vpn.sh peer-qr <peer-name>}"
    [[ -f "$WG_DIR/privatekey" ]] || { echo "No local keypair yet — run 'init' first." >&2; exit 1; }
    command -v qrencode >/dev/null 2>&1 || sudo apt-get install -y qrencode | tee -a "$LOG_FILE"

    log "Generating a NEW keypair for peer '$PEER_NAME' (phones get their own key, not this box's)"
    PEER_KEY_DIR="$WG_DIR/peers/$PEER_NAME"
    mkdir -p "$PEER_KEY_DIR"
    umask 077
    wg genkey | tee "$PEER_KEY_DIR/privatekey" | wg pubkey > "$PEER_KEY_DIR/publickey"

    THIS_PUBKEY="$(cat "$WG_DIR/publickey" 2>/dev/null || echo "SET-THIS-BOXS-PUBKEY")"
    read -r -p "This box's WireGuard endpoint (host:port) as the peer should reach it: " ENDPOINT

    PEER_CONF=$(cat <<EOF
[Interface]
PrivateKey = $(cat "$PEER_KEY_DIR/privatekey")
Address = 10.66.66.$((RANDOM % 200 + 10))/32
DNS = 1.1.1.1

[Peer]
PublicKey = $THIS_PUBKEY
Endpoint = $ENDPOINT
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
EOF
)
    echo "$PEER_CONF" > "$PEER_KEY_DIR/peer.conf"
    log "Peer config written to $PEER_KEY_DIR/peer.conf — add its public key"
    log "  ($(cat "$PEER_KEY_DIR/publickey")) to this box's server config as an"
    log "  allowed peer, then scan below with the WireGuard app:"
    echo ""
    qrencode -t ansiutf8 < "$PEER_KEY_DIR/peer.conf"
    ;;

  *)
    echo "Usage: $0 init | up | down | peer-qr <peer-name>" >&2
    exit 1
    ;;
esac

# Victorious Framework
