#!/usr/bin/env bash
# RootForge OS — Headscale mesh join
# Victorious Framework
#
# Joins this box to an EXISTING Headscale control server as a client node.
# Deliberately does not deploy a new Headscale server — if you're running
# this against a homelab that already has a Headscale control plane (e.g.
# on a Pi 5), standing up a second one would just create two competing
# meshes. This installs the Tailscale client (Headscale is API-compatible
# with it) and points it at your existing server.
#
# Usage: ./join_headscale.sh <headscale-login-server-url> [--advertise-exit-node] [--hostname NAME]

set -euo pipefail

SERVER_URL="${1:?Usage: join_headscale.sh <headscale-login-server-url> [--advertise-exit-node] [--hostname NAME]}"
shift || true

ADVERTISE_EXIT=0
HOSTNAME_OVERRIDE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --advertise-exit-node) ADVERTISE_EXIT=1 ;;
    --hostname) HOSTNAME_OVERRIDE="$2"; shift ;;
  esac
  shift
done

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/headscale_join_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[headscale] $*" | tee -a "$LOG_FILE"; }

log "Installing Tailscale client (used as the Headscale-compatible client)"
if ! command -v tailscale >/dev/null 2>&1; then
  curl -fsSL https://tailscale.com/install.sh | sh 2>&1 | tee -a "$LOG_FILE"
fi

UP_ARGS=(--login-server="$SERVER_URL")
[[ -n "$HOSTNAME_OVERRIDE" ]] && UP_ARGS+=(--hostname="$HOSTNAME_OVERRIDE")
[[ $ADVERTISE_EXIT -eq 1 ]] && UP_ARGS+=(--advertise-exit-node)

log "Joining mesh at $SERVER_URL"
sudo tailscale up "${UP_ARGS[@]}" 2>&1 | tee -a "$LOG_FILE"

log "This node likely needs approval on the Headscale server side before it's"
log "  fully routable — on the server: headscale nodes list, then"
log "  headscale nodes register/approve for this machine's key if it's"
log "  sitting in a pending state."

if [[ $ADVERTISE_EXIT -eq 1 ]]; then
  log "[Certain] --advertise-exit-node only REQUESTS exit-node status — the"
  log "  Headscale server operator still has to approve routes explicitly:"
  log "  headscale routes enable -r <route-id>. It won't work just from this side."
fi

log "Status: tailscale status"
log "Once connected, ADB/fastboot over the mesh works the same as any other"
log "  networked adb target (adb connect <mesh-ip>:5555) if you enable TCP/IP"
log "  debugging on a device you also physically have access to first."

# Victorious Framework
