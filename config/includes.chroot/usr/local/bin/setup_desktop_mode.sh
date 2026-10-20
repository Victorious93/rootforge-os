#!/usr/bin/env bash
# RootForge OS — Desktop mode environment (XFCE, X11/VNC, VS Code, RootForge desktop)
# Victorious Framework | Origin Source Labs
#
# Idempotent installer for the Linux desktop that VictorSuite hosts. Run it
# inside the Debian rootfs (PRoot or chroot). It never flashes a device and
# never talks to Android PackageManager.
#
# Usage:
#   setup_desktop_mode.sh            install missing pieces, print progress
#   setup_desktop_mode.sh --check    report what is present, change nothing
#   setup_desktop_mode.sh --help
#
# Optional:
#   ROOTFORGE_CODE_SERVER_URL and ROOTFORGE_CODE_SERVER_SHA256 together
#   install code-server from that URL after verifying the digest. Either
#   alone is ignored (unpinned downloads are refused).
#
# Seams (tests): ROOTFORGE_HOME, ROOTFORGE_APT_GET, ROOTFORGE_DESKTOP_MODE_STATE,
# ROOTFORGE_TERMUX_PREFIX, ROOTFORGE_DESKTOP_SESSION_BIN.

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
STATE_DIR="${ROOTFORGE_DESKTOP_MODE_STATE:-$ROOTFORGE_HOME/desktop-mode}"
APT_GET="${ROOTFORGE_APT_GET:-apt-get}"
TERMUX_PREFIX="${ROOTFORGE_TERMUX_PREFIX:-}"
SESSION_BIN="${ROOTFORGE_DESKTOP_SESSION_BIN:-xfce4-session}"
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
LAUNCHER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/rootforge_desktop_mode.sh"

XFCE_PACKAGES=(xfce4 xfce4-session xfce4-terminal dbus-x11 x11-xserver-utils)
VNC_PACKAGES=(tigervnc-standalone-server tigervnc-common)
BROWSER_PACKAGES=(firefox-esr)

usage() {
  sed -n '2,22p' "$SELF" | sed 's/^# \{0,1\}//'
}

log() { printf '[desktop-mode] %s\n' "$*"; }

have() { command -v "$1" >/dev/null 2>&1; }

stage_done() { [ -f "$STATE_DIR/$1.done" ]; }

mark_done() {
  mkdir -p "$STATE_DIR"
  printf '%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$STATE_DIR/$1.done"
}

apt_install() {
  local pkg
  log "apt-get update"
  "$APT_GET" update -y
  for pkg in "$@"; do
    log "apt-get install $pkg"
    "$APT_GET" install -y --no-install-recommends "$pkg"
  done
}

CHECK_ONLY=0
case "${1:-}" in
  --check) CHECK_ONLY=1 ;;
  -h|--help) usage; exit 0 ;;
  ""|--install) ;;
  *)
    log "ERROR: unknown argument: $1 (expected --check, --install)"
    usage >&2
    exit 1
    ;;
esac

report() {
  echo "Desktop mode readiness"
  echo "======================"
  printf '  %-22s %s\n' "state dir" "$STATE_DIR"
  if have "$SESSION_BIN"; then
    printf '  %-22s %s\n' "xfce4-session" "$(command -v "$SESSION_BIN")"
  else
    printf '  %-22s %s\n' "xfce4-session" "missing — run: $SELF"
  fi
  if have Xvnc || have vncserver; then
    printf '  %-22s %s\n' "VNC server" "$(command -v vncserver 2>/dev/null || command -v Xvnc)"
  else
    printf '  %-22s %s\n' "VNC server" "missing (fallback when Termux:X11 is down)"
  fi
  if have code-oss; then
    printf '  %-22s %s\n' "VS Code" "$(command -v code-oss)"
  elif have code; then
    printf '  %-22s %s\n' "VS Code" "$(command -v code)"
  elif have code-server; then
    printf '  %-22s %s\n' "VS Code" "$(command -v code-server) (browser)"
  else
    printf '  %-22s %s\n' "VS Code" "missing — apt code-oss, or set ROOTFORGE_CODE_SERVER_URL + SHA256"
  fi
  if have RootForgeOS; then
    printf '  %-22s %s\n' "RootForge desktop" "$(command -v RootForgeOS)"
  elif [ -x /opt/rootforge-os/bin/RootForgeOS ]; then
    printf '  %-22s %s\n' "RootForge desktop" "/opt/rootforge-os/bin/RootForgeOS"
  else
    printf '  %-22s %s\n' "RootForge desktop" "not on PATH — copy the :desktop package into /opt/rootforge-os"
  fi
  if [ -n "$TERMUX_PREFIX" ] && [ -x "$TERMUX_PREFIX/bin/rootforge-desktop-mode" ]; then
    printf '  %-22s %s\n' "Termux wrapper" "$TERMUX_PREFIX/bin/rootforge-desktop-mode"
  else
    printf '  %-22s %s\n' "Termux wrapper" "not installed (set ROOTFORGE_TERMUX_PREFIX to copy it)"
  fi
  if [ -x "$LAUNCHER" ]; then
    printf '  %-22s %s\n' "session launcher" "$LAUNCHER"
  else
    printf '  %-22s %s\n' "session launcher" "missing rootforge_desktop_mode.sh next to this script"
  fi
}

if [ "$CHECK_ONLY" -eq 1 ]; then
  report
  exit 0
fi

mkdir -p "$STATE_DIR" "$ROOTFORGE_HOME/logs" "$HOME/.config/autostart" "$HOME/.local/bin"

log "progress 1/5 — XFCE desktop"
if stage_done xfce || have "$SESSION_BIN"; then
  log "XFCE already present — skipping apt"
  stage_done xfce || mark_done xfce
else
  apt_install "${XFCE_PACKAGES[@]}"
  have "$SESSION_BIN" || { log "ERROR: $SESSION_BIN still missing after apt"; exit 1; }
  mark_done xfce
fi

log "progress 2/5 — VNC fallback (tigervnc)"
if stage_done vnc || have vncserver || have Xvnc; then
  log "VNC already present — skipping apt"
  stage_done vnc || mark_done vnc
else
  apt_install "${VNC_PACKAGES[@]}" || log "VNC packages unavailable in this repo — X11 remains the primary path"
  mark_done vnc
fi

log "progress 3/5 — VS Code / code-server"
if have code-oss || have code || have code-server; then
  log "A VS Code-class editor is already on PATH"
  stage_done vscode || mark_done vscode
else
  if "$APT_GET" install -y --no-install-recommends code-oss; then
    log "Installed code-oss from apt"
  else
    log "code-oss is not in this apt source (common on stock Debian)"
    if [ -n "${ROOTFORGE_CODE_SERVER_URL:-}" ] && [ -n "${ROOTFORGE_CODE_SERVER_SHA256:-}" ]; then
      dest="$ROOTFORGE_HOME/desktop-mode/code-server.tgz"
      mkdir -p "$(dirname "$dest")"
      rf_fetch_verified "$ROOTFORGE_CODE_SERVER_URL" "$dest" "$ROOTFORGE_CODE_SERVER_SHA256"
      mkdir -p "$ROOTFORGE_HOME/desktop-mode/code-server"
      tar -C "$ROOTFORGE_HOME/desktop-mode/code-server" --strip-components=1 -xzf "$dest"
      ln -sfn "$ROOTFORGE_HOME/desktop-mode/code-server/bin/code-server" "$HOME/.local/bin/code-server"
      log "Installed pinned code-server"
    else
      log "Skipping code-server download (need both ROOTFORGE_CODE_SERVER_URL and ROOTFORGE_CODE_SERVER_SHA256)"
    fi
  fi
  have firefox-esr || have firefox || apt_install "${BROWSER_PACKAGES[@]}" || log "No browser package — code-server will still listen on localhost"
  mark_done vscode
fi

log "progress 4/5 — session launchers and autostart"
cat > "$HOME/.local/bin/rootforge-vscode" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if command -v code-oss >/dev/null 2>&1; then exec code-oss "$@"; fi
if command -v code >/dev/null 2>&1; then exec code "$@"; fi
if command -v code-server >/dev/null 2>&1; then
  exec code-server --bind-addr 127.0.0.1:8080 --auth none "$@"
fi
echo "No VS Code-class editor is installed. Re-run setup_desktop_mode.sh." >&2
exit 1
EOF
chmod +x "$HOME/.local/bin/rootforge-vscode"

cat > "$HOME/.local/bin/rootforge-desktop-ui" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if command -v RootForgeOS >/dev/null 2>&1; then exec RootForgeOS "$@"; fi
if [ -x /opt/rootforge-os/bin/RootForgeOS ]; then exec /opt/rootforge-os/bin/RootForgeOS "$@"; fi
if [ -x "$HOME/rootforge/desktop/bin/RootForgeOS" ]; then exec "$HOME/rootforge/desktop/bin/RootForgeOS" "$@"; fi
echo "RootForge desktop UI is not installed in this rootfs." >&2
echo "Copy the :desktop distribution (RootForgeOS) to /opt/rootforge-os or \$HOME/rootforge/desktop." >&2
exit 1
EOF
chmod +x "$HOME/.local/bin/rootforge-desktop-ui"

write_desktop() {
  local path="$1" name="$2" exec_line="$3"
  cat > "$path" <<EOF
[Desktop Entry]
Type=Application
Name=$name
Exec=$exec_line
X-GNOME-Autostart-enabled=true
Terminal=false
EOF
}
write_desktop "$HOME/.config/autostart/rootforge-os.desktop" "RootForge OS" "$HOME/.local/bin/rootforge-desktop-ui"
write_desktop "$HOME/.config/autostart/rootforge-vscode.desktop" "VS Code" "$HOME/.local/bin/rootforge-vscode"
mark_done launchers

log "progress 5/5 — Termux wrapper"
WRAPPER_SRC="${ROOTFORGE_TERMUX_WRAPPER_SRC:-}"
if [ -z "$WRAPPER_SRC" ] && [ -f "$(dirname "$SELF")/desktop-mode-entry.sh" ]; then
  WRAPPER_SRC="$(dirname "$SELF")/desktop-mode-entry.sh"
fi
if [ -n "$TERMUX_PREFIX" ] && [ -d "$TERMUX_PREFIX/bin" ]; then
  if [ -n "$WRAPPER_SRC" ] && [ -f "$WRAPPER_SRC" ]; then
    cp "$WRAPPER_SRC" "$TERMUX_PREFIX/bin/rootforge-desktop-mode"
    chmod +x "$TERMUX_PREFIX/bin/rootforge-desktop-mode"
    log "Installed Termux wrapper at $TERMUX_PREFIX/bin/rootforge-desktop-mode"
  else
    # Minimal wrapper if the Termux tree is not next to this script.
    cat > "$TERMUX_PREFIX/bin/rootforge-desktop-mode" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
op="${1:-start}"
if command -v proot-distro >/dev/null 2>&1; then
  exec proot-distro login rootforge --shared-tmp -- /usr/local/bin/rootforge_desktop_mode.sh "$op"
fi
echo "proot-distro not found; run rootforge_desktop_mode.sh inside the Debian rootfs." >&2
exit 1
EOF
    chmod +x "$TERMUX_PREFIX/bin/rootforge-desktop-mode"
    log "Installed a built-in Termux wrapper at $TERMUX_PREFIX/bin/rootforge-desktop-mode"
  fi
else
  log "TERMUX_PREFIX unset or not a directory — skip Termux wrapper (in-rootfs launcher is enough)"
fi

log "Done. Start the session with: rootforge_desktop_mode.sh start"
report
