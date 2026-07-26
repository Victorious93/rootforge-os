#!/usr/bin/env bash
# RootForge OS — kernel hardening
# Victorious Framework
#
# Applies the standard hardened-sysctl baseline via a drop-in (never edits
# /etc/sysctl.conf directly — survives package updates cleanly). Deliberately
# does NOT enable kernel lockdown mode by default: lockdown=integrity blocks
# loading unsigned out-of-tree modules, which is a direct conflict with
# KernelSU development and some kernel-module workflows this distro exists
# to support. --lockdown is offered as an explicit opt-in for boxes that are
# NOT doing kernel module work, not a default.
#
# Usage: ./harden_kernel.sh [--lockdown] [--dry-run]

set -euo pipefail

LOCKDOWN=0
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --lockdown) LOCKDOWN=1 ;;
    --dry-run) DRY_RUN=1 ;;
  esac
done

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/harden_kernel_$(date +%Y%m%d_%H%M%S).log"
log() { echo "[harden-kernel] $*" | tee -a "$LOG_FILE"; }

SYSCTL_FILE="/etc/sysctl.d/90-rootforge-hardening.conf"

CONTENT=$(cat <<'EOF'
# RootForge OS — kernel hardening baseline
# Victorious Framework
#
# Deliberately does NOT set kernel.dmesg_restrict=1 as strictly as some
# hardening guides recommend — dmesg is used constantly for bootloader/USB
# debugging on this box, and restricting it to root-only is fine (default),
# but full suppression breaks the exact troubleshooting workflow this distro
# is for. Adjust below if your threat model disagrees.

kernel.kptr_restrict = 2
kernel.dmesg_restrict = 1
kernel.yama.ptrace_scope = 1
kernel.unprivileged_bpf_disabled = 1
net.core.bpf_jit_harden = 2
kernel.kexec_load_disabled = 1
kernel.sysrq = 4

net.ipv4.conf.all.rp_filter = 1
net.ipv4.conf.default.rp_filter = 1
net.ipv4.conf.all.accept_redirects = 0
net.ipv6.conf.all.accept_redirects = 0
net.ipv4.conf.all.send_redirects = 0
net.ipv4.conf.all.accept_source_route = 0
net.ipv4.tcp_syncookies = 1
net.ipv4.icmp_echo_ignore_broadcasts = 1

fs.protected_symlinks = 1
fs.protected_hardlinks = 1
fs.suid_dumpable = 0
EOF
)

if [[ $DRY_RUN -eq 1 ]]; then
  log "Dry run — would write to $SYSCTL_FILE:"
  echo "$CONTENT"
else
  echo "$CONTENT" | sudo tee "$SYSCTL_FILE" > /dev/null
  sudo sysctl --system 2>&1 | tee -a "$LOG_FILE"
  log "Applied. Verify: sysctl kernel.yama.ptrace_scope"
fi

log "kernel.yama.ptrace_scope=1 (not 2/3) deliberately — some Android"
log "  debugging/emulator tooling attaches via ptrace; 1 still blocks"
log "  cross-user ptrace without breaking that."

if [[ $LOCKDOWN -eq 1 ]]; then
  log "Enabling kernel lockdown=integrity via GRUB cmdline"
  log "[Certain] this WILL block loading unsigned out-of-tree kernel modules —"
  log "  do not enable this on a box actively doing KernelSU/kernel-module"
  log "  development. It's meant for a box in pure Android-testing mode only."
  if [[ $DRY_RUN -eq 0 ]]; then
    sudo sed -i 's/GRUB_CMDLINE_LINUX="\(.*\)"/GRUB_CMDLINE_LINUX="\1 lockdown=integrity"/' /etc/default/grub
    sudo update-grub 2>&1 | tee -a "$LOG_FILE"
    log "Takes effect on next reboot."
  fi
else
  log "Lockdown mode NOT enabled (default) — this box can still load unsigned"
  log "  kernel modules for KernelSU/kernel work. Pass --lockdown to opt in if"
  log "  this specific box isn't doing that work."
fi

# Victorious Framework
