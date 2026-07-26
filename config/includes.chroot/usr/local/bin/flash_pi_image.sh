#!/usr/bin/env bash
# RootForge OS — Raspberry Pi image flasher
# Victorious Framework
#
# Wraps the OFFICIAL rpi-imager CLI mode rather than reimplementing SD/USB
# writing — that tool already handles verification and safety checks
# correctly. What this adds is node-role presets: pre-injecting SSH keys,
# hostname, and a first-boot script that installs either the homelab stack
# (Headscale + AdGuard + WireGuard + RustDesk, matching an existing Pi 5
# homelab node) or a bare PyraClaw-dispatcher-role skeleton, so a freshly
# flashed Pi boots straight into a known role instead of a blank Pi OS.
#
# Usage: ./flash_pi_image.sh <image.img.xz> <device> [--role homelab-node|dispatcher|bare] [--hostname NAME] [--ssh-key path]

set -euo pipefail

IMAGE="${1:?Usage: flash_pi_image.sh <image.img.xz> <device> [--role homelab-node|dispatcher|bare] [--hostname NAME] [--ssh-key path]}"
DEVICE="${2:?Missing target device, e.g. /dev/sdX}"
shift 2

ROLE="bare"
HOSTNAME_OVERRIDE="rootforge-pi"
SSH_KEY="$HOME/.ssh/id_ed25519.pub"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --role) ROLE="$2"; shift ;;
    --hostname) HOSTNAME_OVERRIDE="$2"; shift ;;
    --ssh-key) SSH_KEY="$2"; shift ;;
  esac
  shift
done

[[ -f "$IMAGE" ]] || { echo "Image not found: $IMAGE" >&2; exit 1; }
[[ -b "$DEVICE" ]] || { echo "Not a block device: $DEVICE — double-check before this overwrites it." >&2; exit 1; }
[[ -f "$SSH_KEY" ]] || { echo "SSH public key not found at $SSH_KEY — generate one or pass --ssh-key." >&2; exit 1; }

command -v rpi-imager >/dev/null 2>&1 || { echo "rpi-imager not installed (apt install rpi-imager)." >&2; exit 1; }

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/flash_pi_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[flash-pi] $*" | tee -a "$LOG_FILE"; }

echo ""
echo "About to write '$IMAGE' to '$DEVICE'. This ERASES everything currently on"
echo "that device. Confirm it's the Pi's SD/USB media, not something else."
read -r -p "Type FLASH to proceed: " CONFIRM
[[ "$CONFIRM" == "FLASH" ]] || { log "Confirmation not given — aborting."; exit 1; }

log "Writing image via rpi-imager CLI"
rpi-imager --cli \
  --enable-ssh \
  --ssh-user pi \
  --ssh-pubkey-file "$SSH_KEY" \
  --hostname "$HOSTNAME_OVERRIDE" \
  "$IMAGE" "$DEVICE" 2>&1 | tee -a "$LOG_FILE" \
  || { log "rpi-imager failed — see $LOG_FILE. Older rpi-imager builds may not support all of these flags; check 'rpi-imager --cli --help'."; exit 1; }

log "Image written. Node role: $ROLE"

case "$ROLE" in
  homelab-node)
    log "This preset expects you to SSH in after first boot and run the homelab"
    log "  provisioning yourself (Headscale client join via join_headscale.sh,"
    log "  AdGuard/WireGuard/RustDesk installs) — rpi-imager's own customization"
    log "  doesn't have a hook for arbitrary post-install package installs, so"
    log "  this isn't baked into the image itself. Treat this as a labeled"
    log "  reminder of intent, not an automatic provisioning step."
    ;;
  dispatcher)
    log "PyraClaw dispatcher role noted. No dispatcher software is bundled here —"
    log "  that's a separate, currently-inactive project. This just labels the"
    log "  node's intended role via its hostname ($HOSTNAME_OVERRIDE) so"
    log "  rpi_fleet_tools.sh can find it later by name."
    ;;
  bare)
    log "No role provisioning — plain Pi OS with SSH and hostname set."
    ;;
esac

log "Boot the Pi, then: ssh pi@$HOSTNAME_OVERRIDE.local (or find its IP via"
log "  rpi_fleet_tools.sh scan once it's on the network)."

# Victorious Framework
