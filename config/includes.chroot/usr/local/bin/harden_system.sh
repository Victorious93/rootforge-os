#!/usr/bin/env bash
# RootForge OS — system security hardening
# Victorious Framework
#
# AppArmor enforcement, USBGuard, auditd baseline, a default-deny nftables
# firewall, and fail2ban for SSH. USBGuard gets special attention here: this
# box's whole job is having unfamiliar Android devices plugged in over USB —
# that's exactly the threat model USBGuard exists for, more than on a typical
# desktop.
#
# Usage: ./harden_system.sh [--usbguard-learn]
#   --usbguard-learn   generate an allow-list from currently connected
#                       devices instead of starting from a blank default-block
#                       policy (do this once, with only trusted devices
#                       connected, before relying on USBGuard day to day)

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

# Only $1 was ever looked at, and an unrecognized argument was ignored, so
# `--usbguard-lern` ran the script without learning and reported success.
USBGUARD_LEARN=0
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --usbguard-learn) USBGUARD_LEARN=1 ;;
    --dry-run) DRY_RUN=1 ;;
    -h|--help)
      echo "Usage: harden_system.sh [--usbguard-learn] [--dry-run]"
      echo "  --usbguard-learn  build a USB allow-list from what is connected now"
      echo "  --dry-run         install nothing, enable no services, change no"
      echo "                    files; show what the USB policy would allow"
      exit 0
      ;;
    *) echo "Unknown argument: $arg (expected --usbguard-learn or --dry-run)" >&2; exit 1 ;;
  esac
done

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/harden_system_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[harden-system] $*" | tee -a "$LOG_FILE"; }

# --dry-run exists because this script's default USB posture is block-all: it
# can lock you out of the keyboard you would need to undo it. Being able to
# see the allow-list it would write, before it writes anything, is the
# difference between a reviewable change and a gamble. It is also what makes
# the empty-policy guard below testable without installing packages and
# enabling services on the machine running the test.
if [[ $DRY_RUN -eq 1 ]]; then
  log "DRY RUN — installing nothing, enabling no services, changing no files."
fi

run_priv() {
  if [[ $DRY_RUN -eq 1 ]]; then
    log "would run: $*"
    return 0
  fi
  sudo "$@"
}

log "Installing hardening packages"
run_priv apt-get update -y | tee -a "$LOG_FILE"
run_priv apt-get install -y --no-install-recommends \
  apparmor apparmor-utils apparmor-profiles \
  usbguard \
  auditd audispd-plugins \
  nftables \
  fail2ban \
  | tee -a "$LOG_FILE"

# ---- AppArmor -----------------------------------------------------------
log "Enabling AppArmor enforce mode for available profiles"
run_priv systemctl enable --now apparmor 2>&1 | tee -a "$LOG_FILE"
run_priv aa-enforce /etc/apparmor.d/* 2>>"$LOG_FILE" || log "Some profiles couldn't be set to enforce — check aa-status for details."

# ---- USBGuard -------------------------------------------------------------
log "Configuring USBGuard"
log "[Certain] this box's job involves plugging in unfamiliar Android devices"
log "  constantly — a default-block USB policy is the correct posture here,"
log "  the opposite of most desktops. Fastboot/ADB access is USB, so a"
log "  misconfigured policy will lock out the exact devices you're testing;"
log "  use --usbguard-learn once with only trusted gear connected first."

if [[ $USBGUARD_LEARN -eq 1 ]]; then
  log "Generating allow-list from currently connected devices"

  # This wrote generate-policy's output straight over rules.conf. USBGuard's
  # default ImplicitPolicyTarget is "block", so an empty or near-empty
  # rules.conf means *every* USB device is denied — including the keyboard
  # you would need to fix it with. `generate-policy` legitimately produces
  # nothing when nothing is plugged in, and that emptiness is not an error,
  # so nothing caught it. Build the policy in a temp file and check it
  # before it replaces anything.
  USBGUARD_TMP="$(mktemp)"
  # Same seam as ROOTFORGE_GRUB_DEFAULTS and ROOTFORGE_SYSCTL_FILE.
  USBGUARD_RULES="${ROOTFORGE_USBGUARD_RULES:-/etc/usbguard/rules.conf}"
  trap 'rm -f "$USBGUARD_TMP"' EXIT
  # Not run_priv: a dry run still needs the real policy so it can show what
  # would be allowed. generate-policy only reads the USB bus.
  sudo usbguard generate-policy > "$USBGUARD_TMP"

  RULE_COUNT="$(grep -cE '^[[:space:]]*allow' "$USBGUARD_TMP" || true)"
  if [[ "$RULE_COUNT" -eq 0 ]]; then
    log "ERROR: 'usbguard generate-policy' produced no allow rules."
    log "       Writing that as the policy would block every USB device on this box,"
    log "       keyboard included, with USBGuard's default block posture."
    log "       Plug in the devices you want allowed and re-run. Nothing was changed."
    exit 1
  fi

  log "Generated $RULE_COUNT allow rule(s) from the currently connected devices."
  log "Everything NOT connected right now will be blocked once USBGuard is enabled."
  if ! rf_confirm USBGUARD \
      "" \
      "About to replace $USBGUARD_RULES and enable USBGuard." \
      "Devices allowed by the new policy ($RULE_COUNT rule(s)):" \
      "$(grep -E '^[[:space:]]*allow' "$USBGUARD_TMP" | sed 's/^/  /' | head -20)" \
      "" \
      "A USB keyboard or mouse not in that list will stop working after this."; then
    log "Confirmation not given — aborting. USBGuard policy unchanged."
    exit 1
  fi

  run_priv cp "$USBGUARD_TMP" "$USBGUARD_RULES"
  run_priv chmod 600 "$USBGUARD_RULES"
  log "Policy written from current device snapshot. Review $USBGUARD_RULES"
  log "  before relying on it — this allow-lists whatever was plugged in just now."
  run_priv systemctl enable --now usbguard 2>&1 | tee -a "$LOG_FILE"
else
  log "Leaving existing/default USBGuard policy in place, and NOT enabling the"
  log "  service — enabling it against an empty policy would block every USB"
  log "  device on this box. Run with --usbguard-learn (with your trusted"
  log "  devices connected) to generate an allow-list and enable it."
fi

# ---- auditd ---------------------------------------------------------------
log "Configuring auditd baseline rules"
AUDIT_RULES="${ROOTFORGE_AUDIT_RULES:-/etc/audit/rules.d/rootforge.rules}"
run_priv tee "$AUDIT_RULES" > /dev/null <<'EOF'
# RootForge OS — audit baseline
# Victorious Framework
-w /etc/passwd -p wa -k rootforge-identity
-w /etc/sudoers -p wa -k rootforge-identity
-w /etc/ssh/sshd_config -p wa -k rootforge-ssh
-w /etc/usbguard/rules.conf -p wa -k rootforge-usbguard
-a always,exit -F arch=b64 -S execve -F path=/usr/bin/su -k rootforge-su-exec
EOF
run_priv augenrules --load 2>&1 | tee -a "$LOG_FILE" || log "augenrules load failed — check auditd is running."
run_priv systemctl enable --now auditd 2>&1 | tee -a "$LOG_FILE"

# ---- nftables firewall ------------------------------------------------------
log "Configuring default-deny inbound nftables firewall"
NFT_FILE="${ROOTFORGE_NFT_FILE:-/etc/nftables.conf}"
run_priv tee "$NFT_FILE" > /dev/null <<'EOF'
#!/usr/sbin/nft -f
# RootForge OS — default firewall
# Victorious Framework
flush ruleset

table inet filter {
  chain input {
    type filter hook input priority 0; policy drop;
    iif "lo" accept
    ct state established,related accept
    ip protocol icmp accept
    tcp dport 22 accept comment "SSH"
    # RootForge networking features add their own rules alongside this —
    # WireGuard (setup_vpn.sh) and Headscale/Tailscale (join_headscale.sh)
    # append rather than replace this file.
  }
  chain forward { type filter hook forward priority 0; policy drop; }
  chain output { type filter hook output priority 0; policy accept; }
}
EOF
run_priv systemctl enable --now nftables 2>&1 | tee -a "$LOG_FILE"
log "Default policy: deny inbound except SSH (22) and established connections."
log "  ADB/fastboot are USB, not network, so this doesn't affect device access."

# ---- fail2ban ---------------------------------------------------------------
log "Enabling fail2ban for SSH"
run_priv systemctl enable --now fail2ban 2>&1 | tee -a "$LOG_FILE"

log "System hardening complete. Check status with:"
log "  sudo aa-status && sudo usbguard list-devices && sudo auditctl -l && sudo nft list ruleset"

# Victorious Framework
