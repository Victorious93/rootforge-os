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

USBGUARD_LEARN=0
[[ "${1:-}" == "--usbguard-learn" ]] && USBGUARD_LEARN=1

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/harden_system_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[harden-system] $*" | tee -a "$LOG_FILE"; }

log "Installing hardening packages"
sudo apt-get update -y | tee -a "$LOG_FILE"
sudo apt-get install -y --no-install-recommends \
  apparmor apparmor-utils apparmor-profiles \
  usbguard \
  auditd audispd-plugins \
  nftables \
  fail2ban \
  | tee -a "$LOG_FILE"

# ---- AppArmor -----------------------------------------------------------
log "Enabling AppArmor enforce mode for available profiles"
sudo systemctl enable --now apparmor 2>&1 | tee -a "$LOG_FILE"
sudo aa-enforce /etc/apparmor.d/* 2>>"$LOG_FILE" || log "Some profiles couldn't be set to enforce — check aa-status for details."

# ---- USBGuard -------------------------------------------------------------
log "Configuring USBGuard"
log "[Certain] this box's job involves plugging in unfamiliar Android devices"
log "  constantly — a default-block USB policy is the correct posture here,"
log "  the opposite of most desktops. Fastboot/ADB access is USB, so a"
log "  misconfigured policy will lock out the exact devices you're testing;"
log "  use --usbguard-learn once with only trusted gear connected first."

if [[ $USBGUARD_LEARN -eq 1 ]]; then
  log "Generating allow-list from currently connected devices"
  sudo usbguard generate-policy | sudo tee /etc/usbguard/rules.conf > /dev/null
  log "Policy written from current device snapshot. Review /etc/usbguard/rules.conf"
  log "  before relying on it — this allow-lists whatever was plugged in just now."
else
  log "Leaving existing/default USBGuard policy in place. Run with"
  log "  --usbguard-learn to generate a fresh allow-list from connected devices."
fi
sudo systemctl enable --now usbguard 2>&1 | tee -a "$LOG_FILE"

# ---- auditd ---------------------------------------------------------------
log "Configuring auditd baseline rules"
AUDIT_RULES="/etc/audit/rules.d/rootforge.rules"
sudo tee "$AUDIT_RULES" > /dev/null <<'EOF'
# RootForge OS — audit baseline
# Victorious Framework
-w /etc/passwd -p wa -k rootforge-identity
-w /etc/sudoers -p wa -k rootforge-identity
-w /etc/ssh/sshd_config -p wa -k rootforge-ssh
-w /etc/usbguard/rules.conf -p wa -k rootforge-usbguard
-a always,exit -F arch=b64 -S execve -F path=/usr/bin/su -k rootforge-su-exec
EOF
sudo augenrules --load 2>&1 | tee -a "$LOG_FILE" || log "augenrules load failed — check auditd is running."
sudo systemctl enable --now auditd 2>&1 | tee -a "$LOG_FILE"

# ---- nftables firewall ------------------------------------------------------
log "Configuring default-deny inbound nftables firewall"
NFT_FILE="/etc/nftables.conf"
sudo tee "$NFT_FILE" > /dev/null <<'EOF'
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
sudo systemctl enable --now nftables 2>&1 | tee -a "$LOG_FILE"
log "Default policy: deny inbound except SSH (22) and established connections."
log "  ADB/fastboot are USB, not network, so this doesn't affect device access."

# ---- fail2ban ---------------------------------------------------------------
log "Enabling fail2ban for SSH"
sudo systemctl enable --now fail2ban 2>&1 | tee -a "$LOG_FILE"

log "System hardening complete. Check status with:"
log "  sudo aa-status && sudo usbguard list-devices && sudo auditctl -l && sudo nft list ruleset"

# Victorious Framework
