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
    -h|--help)
      echo "Usage: harden_kernel.sh [--lockdown] [--dry-run]"
      exit 0
      ;;
    # An ignored typo matters here: `--lockdow` used to run the script
    # without lockdown and report success, so you'd believe a box was
    # locked down when it wasn't.
    *) echo "Unknown argument: $arg (expected --lockdown, --dry-run)" >&2; exit 1 ;;
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

  # `sysctl --system` exits non-zero if ANY key in ANY file under
  # /etc/sysctl.d cannot be set — a knob this kernel doesn't have, a
  # container, an unrelated third-party drop-in. Under `set -e` with
  # pipefail that aborted the whole script right here, so
  # `harden_kernel.sh --lockdown` applied nothing and never reached the
  # lockdown step the caller explicitly asked for. Record the failure,
  # finish the run, and report it at the end instead.
  SYSCTL_STATUS=0
  sudo sysctl --system 2>&1 | tee -a "$LOG_FILE" || SYSCTL_STATUS=$?
  if [[ $SYSCTL_STATUS -ne 0 ]]; then
    log "WARNING: sysctl --system exited $SYSCTL_STATUS — at least one key could not be set."
    log "  That is common on a kernel that lacks one of these knobs, or inside a container."
    log "  The drop-in at $SYSCTL_FILE is written either way; see $LOG_FILE for which key."
  fi
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
    # Overridable so the test suite can exercise this edit against a fixture
    # rather than a copy of the logic — a test that re-implements what it is
    # checking passes whether or not the real code is right.
    GRUB_DEFAULTS="${ROOTFORGE_GRUB_DEFAULTS:-/etc/default/grub}"
    [[ -f "$GRUB_DEFAULTS" ]] || { log "ERROR: $GRUB_DEFAULTS not found — this system may not use GRUB. Nothing changed."; exit 1; }

    if grep -q 'lockdown=integrity' "$GRUB_DEFAULTS"; then
      # The old `sed s/.../\1 lockdown=integrity/` appended unconditionally,
      # so every run added another copy: three runs left
      # GRUB_CMDLINE_LINUX=" lockdown=integrity lockdown=integrity
      # lockdown=integrity". Verified.
      log "lockdown=integrity is already present in $GRUB_DEFAULTS — leaving it alone."
    elif grep -qE '^[[:space:]]*GRUB_CMDLINE_LINUX=' "$GRUB_DEFAULTS"; then
      sudo sed -i 's/^\([[:space:]]*GRUB_CMDLINE_LINUX="\)\(.*\)"/\1\2 lockdown=integrity"/' "$GRUB_DEFAULTS"
      # The old sed silently matched nothing when the line was absent or
      # shaped differently, and the script still said "Takes effect on next
      # reboot." Confirm the edit actually landed.
      grep -q 'lockdown=integrity' "$GRUB_DEFAULTS" \
        || { log "ERROR: failed to add lockdown=integrity to $GRUB_DEFAULTS — check its GRUB_CMDLINE_LINUX line by hand."; exit 1; }
    else
      # Some installs only carry GRUB_CMDLINE_LINUX_DEFAULT. Append the
      # variable rather than editing a line that isn't there.
      log "No GRUB_CMDLINE_LINUX line in $GRUB_DEFAULTS — appending one."
      echo 'GRUB_CMDLINE_LINUX="lockdown=integrity"' | sudo tee -a "$GRUB_DEFAULTS" > /dev/null
    fi

    if [[ -n "${ROOTFORGE_GRUB_DEFAULTS:-}" ]]; then
      log "ROOTFORGE_GRUB_DEFAULTS is set — edited $GRUB_DEFAULTS and skipped update-grub."
    else
      sudo update-grub 2>&1 | tee -a "$LOG_FILE"
      log "Takes effect on next reboot."
    fi
  fi
else
  log "Lockdown mode NOT enabled (default) — this box can still load unsigned"
  log "  kernel modules for KernelSU/kernel work. Pass --lockdown to opt in if"
  log "  this specific box isn't doing that work."
fi

# Surface the deferred sysctl failure now that every requested step has run.
if [[ "${SYSCTL_STATUS:-0}" -ne 0 ]]; then
  log "Finished, but sysctl reported an error while applying the baseline — see above."
  exit 1
fi

# Victorious Framework
