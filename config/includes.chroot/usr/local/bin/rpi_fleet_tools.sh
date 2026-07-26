#!/usr/bin/env bash
# RootForge OS — Raspberry Pi fleet tools
# Victorious Framework
#
# Local-network discovery (MAC OUI match on Raspberry Pi Foundation
# prefixes), SSH key distribution, and a batch command runner across
# however many Pis answer. Built for a small homelab fleet, not a
# datacenter — no inventory database, just an nmap/arp-scan pass each time.
#
# Usage: ./rpi_fleet_tools.sh scan | push-key <host> | run "<command>" [host...]

set -euo pipefail

CMD="${1:?Usage: rpi_fleet_tools.sh scan | push-key <host> | run \"<command>\" [host...]}"
shift || true

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
FLEET_FILE="$ROOTFORGE_HOME/devices/pi-fleet.txt"
mkdir -p "$LOG_DIR" "$(dirname "$FLEET_FILE")"
LOG_FILE="$LOG_DIR/pi_fleet_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[pi-fleet] $*" | tee -a "$LOG_FILE"; }

case "$CMD" in
  scan)
    command -v nmap >/dev/null 2>&1 || sudo apt-get install -y nmap | tee -a "$LOG_FILE"
    log "Scanning local subnet for Raspberry Pi Foundation MAC prefixes"
    log "(this only finds Pis on the same L2 segment — a mesh-connected Pi"
    log " elsewhere, e.g. over Headscale, needs its mesh IP addressed directly)"

    SUBNET="$(ip route | grep -oP '(?<=src )\S+' | head -1 | sed 's/\.[0-9]*$/.0\/24/' || echo "192.168.1.0/24")"
    log "Scanning $SUBNET"

    RESULTS="$(sudo nmap -sn "$SUBNET" 2>>"$LOG_FILE" | grep -B2 -iE 'Raspberry Pi|dc:a6:32|b8:27:eb|d8:3a:dd|e4:5f:01' || true)"
    if [[ -z "$RESULTS" ]]; then
      log "No Raspberry Pi devices found on $SUBNET. If a Pi is on a different"
      log "  segment or joined via Headscale, address it directly instead."
    else
      echo "$RESULTS" | tee -a "$LOG_FILE"
      echo "$RESULTS" | grep -oP '(?<=\()[\d.]+(?=\))' > "$FLEET_FILE" || true
      log "IPs saved to $FLEET_FILE for use with 'run'"
    fi
    ;;

  push-key)
    HOST="${1:?Usage: rpi_fleet_tools.sh push-key <host>}"
    KEY="$HOME/.ssh/id_ed25519.pub"
    [[ -f "$KEY" ]] || { echo "No SSH key at $KEY — generate one first (ssh-keygen -t ed25519)." >&2; exit 1; }
    log "Pushing SSH key to pi@$HOST"
    ssh-copy-id -i "$KEY" "pi@$HOST" 2>&1 | tee -a "$LOG_FILE"
    ;;

  run)
    COMMAND="${1:?Usage: rpi_fleet_tools.sh run \"<command>\" [host...]}"
    shift || true
    HOSTS=("$@")
    if [[ ${#HOSTS[@]} -eq 0 ]]; then
      [[ -f "$FLEET_FILE" ]] || { echo "No hosts given and no $FLEET_FILE from a prior scan." >&2; exit 1; }
      mapfile -t HOSTS < "$FLEET_FILE"
    fi

    log "Running on ${#HOSTS[@]} host(s): $COMMAND"
    echo "" > "$LOG_DIR/pi_fleet_run_$(date +%Y%m%d_%H%M%S)_results.txt"
    RESULTS_FILE="$LOG_DIR/pi_fleet_run_$(date +%Y%m%d_%H%M%S)_results.txt"

    for host in "${HOSTS[@]}"; do
      echo "=== $host ===" | tee -a "$RESULTS_FILE"
      if ssh -o ConnectTimeout=5 -o BatchMode=yes "pi@$host" "$COMMAND" 2>&1 | tee -a "$RESULTS_FILE"; then
        log "$host: OK"
      else
        log "$host: FAILED (see $RESULTS_FILE)"
      fi
    done
    log "Full output: $RESULTS_FILE"
    ;;

  *)
    echo "Usage: $0 scan | push-key <host> | run \"<command>\" [host...]" >&2
    exit 1
    ;;
esac

# Victorious Framework
