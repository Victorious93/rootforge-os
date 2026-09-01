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
# Seam, same as ROOTFORGE_GRUB_DEFAULTS / ROOTFORGE_SYSCTL_FILE: a destination
# outside $ROOTFORGE_HOME has to be redirectable, or testing the script means
# writing to /etc on the machine running the test.
CONF="${ROOTFORGE_WG_CONF:-/etc/wireguard/${IFACE}.conf}"

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
    # Validate the argument before inspecting any state, so a bad name is
    # reported as a bad name rather than as whatever unrelated precondition
    # happens to be checked first.
    #
    # The name becomes a directory under $WG_DIR/peers; keep it to something
    # that cannot climb out of it.
    [[ "$PEER_NAME" =~ ^[A-Za-z0-9._-]+$ && "$PEER_NAME" != "." && "$PEER_NAME" != ".." ]] \
      || { echo "Peer name must be [A-Za-z0-9._-] (got '$PEER_NAME')" >&2; exit 1; }
    [[ -f "$WG_DIR/privatekey" ]] || { echo "No local keypair yet — run 'init' first." >&2; exit 1; }
    command -v qrencode >/dev/null 2>&1 || sudo apt-get install -y qrencode | tee -a "$LOG_FILE"

    log "Generating a NEW keypair for peer '$PEER_NAME' (phones get their own key, not this box's)"
    PEER_KEY_DIR="$WG_DIR/peers/$PEER_NAME"
    mkdir -p "$PEER_KEY_DIR"
    umask 077
    wg genkey | tee "$PEER_KEY_DIR/privatekey" | wg pubkey > "$PEER_KEY_DIR/publickey"

    # Peer addresses used to be `10.66.66.$((RANDOM % 200 + 10))/32` — 200
    # slots picked at random with no check against the peers already issued.
    # That is a birthday-problem collision: ~20% chance of a duplicate by the
    # 10th peer and ~63% by the 20th. Two peers sharing an AllowedIPs address
    # doesn't fail loudly; it silently breaks routing for whichever one the
    # server saw last, which is a miserable thing to debug. Hand out the
    # lowest address not already taken instead.
    PEER_OCTET=""
    for candidate in $(seq 10 250); do
      if ! grep -rqs "^Address = 10\.66\.66\.${candidate}/32\b" "$WG_DIR/peers"; then
        PEER_OCTET="$candidate"
        break
      fi
    done
    [[ -n "$PEER_OCTET" ]] || { echo "No free address left in 10.66.66.10-250 — retire an old peer under $WG_DIR/peers." >&2; exit 1; }
    log "Assigned 10.66.66.${PEER_OCTET}/32 to '$PEER_NAME'"

    THIS_PUBKEY="$(cat "$WG_DIR/publickey" 2>/dev/null || echo "SET-THIS-BOXS-PUBKEY")"
    # `read -r -p` reads stdin, so with stdin closed — any unattended run —
    # it returned non-zero and `set -e` ended the script right here, after
    # the peer keypair had already been generated and an address assigned.
    # No message, and a half-created peer left behind. Say what is missing,
    # and take it from the environment when there is no terminal to ask.
    if [[ -n "${ROOTFORGE_WG_ENDPOINT:-}" ]]; then
      ENDPOINT="$ROOTFORGE_WG_ENDPOINT"
    elif [[ -t 0 ]]; then
      read -r -p "This box's WireGuard endpoint (host:port) as the peer should reach it: " ENDPOINT
    else
      echo "No terminal to prompt on for this box's WireGuard endpoint." >&2
      echo "Set ROOTFORGE_WG_ENDPOINT=host:port and re-run." >&2
      exit 1
    fi

    PEER_CONF=$(cat <<EOF
[Interface]
PrivateKey = $(cat "$PEER_KEY_DIR/privatekey")
Address = 10.66.66.${PEER_OCTET}/32
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
