#!/usr/bin/env bash
# RootForge OS — Desktop mode session (XFCE on X11, VNC fallback)
# Victorious Framework | Origin Source Labs
#
# Starts, stops or reports the graphical session that VictorSuite hosts.
# Called from the Termux wrapper `rootforge-desktop-mode` or directly inside
# the Debian rootfs.
#
# Usage:
#   rootforge_desktop_mode.sh start|stop|setup|status
#
# X11: Termux:X11 / VictorSuite internal X server, socket in
#   $ROOTFORGE_X11_SOCKET_DIR (default /tmp/.X11-unix), DISPLAY :0
# VNC: if that socket is missing, start tigervnc on :1 and tell the user.
#
# Seams: ROOTFORGE_X11_SOCKET_DIR, ROOTFORGE_X11_DISPLAY,
# ROOTFORGE_DESKTOP_SESSION_BIN, ROOTFORGE_VNC_DISPLAY, ROOTFORGE_HOME.

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

DISPLAY_NUM="${ROOTFORGE_X11_DISPLAY:-:0}"
X11_SOCKET_DIR="${ROOTFORGE_X11_SOCKET_DIR:-/tmp/.X11-unix}"
SESSION_BIN="${ROOTFORGE_DESKTOP_SESSION_BIN:-xfce4-session}"
VNC_DISPLAY="${ROOTFORGE_VNC_DISPLAY:-:1}"
ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
RUN_DIR="${ROOTFORGE_DESKTOP_MODE_STATE:-$ROOTFORGE_HOME/desktop-mode}"
PID_FILE="$RUN_DIR/session.pid"

log() { printf '[desktop-mode] %s\n' "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

op="${1:-}"
case "$op" in
  start|stop|setup|status) ;;
  -h|--help)
    sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    die "Unknown argument: ${op:-<(none)>} (expected start, stop, setup, status)"
    ;;
esac

have() { command -v "$1" >/dev/null 2>&1; }

x11_up() {
  [ -d "$X11_SOCKET_DIR" ] || return 1
  find "$X11_SOCKET_DIR" -maxdepth 1 -name 'X*' 2>/dev/null | grep -q .
}

setup_now() {
  local setup
  setup="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/setup_desktop_mode.sh"
  [ -x "$setup" ] || die "setup_desktop_mode.sh is not next to this launcher"
  exec "$setup"
}

status_now() {
  echo "Desktop mode session"
  echo "===================="
  printf '  %-22s %s\n' "DISPLAY" "$DISPLAY_NUM"
  printf '  %-22s %s\n' "socket dir" "$X11_SOCKET_DIR"
  if x11_up; then
    printf '  %-22s %s\n' "X11" "up"
  else
    printf '  %-22s %s\n' "X11" "down — VNC fallback if tigervnc is installed"
  fi
  if have "$SESSION_BIN"; then
    printf '  %-22s %s\n' "session" "$(command -v "$SESSION_BIN")"
  else
    printf '  %-22s %s\n' "session" "missing — run: rootforge_desktop_mode.sh setup"
  fi
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    printf '  %-22s %s\n' "running pid" "$(cat "$PID_FILE")"
  else
    printf '  %-22s %s\n' "running pid" "none"
  fi
  have rootforge-vscode && printf '  %-22s %s\n' "vscode wrapper" "$(command -v rootforge-vscode)" \
    || printf '  %-22s %s\n' "vscode wrapper" "missing"
  have rootforge-desktop-ui && printf '  %-22s %s\n' "RootForge UI wrapper" "$(command -v rootforge-desktop-ui)" \
    || printf '  %-22s %s\n' "RootForge UI wrapper" "missing"
}

stop_now() {
  if [ -f "$PID_FILE" ]; then
    local pid
    pid="$(cat "$PID_FILE")"
    if kill -0 "$pid" 2>/dev/null; then
      log "Stopping session pid $pid"
      kill "$pid" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
  if have vncserver; then
    vncserver -kill "$VNC_DISPLAY" >/dev/null 2>&1 || true
  fi
  log "Stopped"
}

start_now() {
  have "$SESSION_BIN" || die "No desktop installed. Run: $0 setup"
  mkdir -p "$RUN_DIR"
  if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    log "Already running (pid $(cat "$PID_FILE"))"
    exit 0
  fi

  if x11_up; then
    export DISPLAY="$DISPLAY_NUM"
    log "Using X11 display $DISPLAY (Termux:X11 / VictorSuite)"
  else
    have vncserver || have Xvnc || die "No X11 socket in $X11_SOCKET_DIR and no tigervnc. Start VictorSuite's X11 server, or run: $0 setup"
    export DISPLAY="$VNC_DISPLAY"
    log "X11 socket missing — starting tigervnc on $VNC_DISPLAY (localhost only)"
    if have vncserver; then
      vncserver "$VNC_DISPLAY" -localhost yes -geometry "${ROOTFORGE_DESKTOP_GEOMETRY:-1280x720}" || die "vncserver failed"
    else
      die "Xvnc is present but vncserver is not; install tigervnc-standalone-server"
    fi
    vnc_num="${VNC_DISPLAY#:}"
    case "$vnc_num" in
      ''|*[!0-9]*) vnc_port="unknown" ;;
      *) vnc_port=$((5900 + vnc_num)) ;;
    esac
    log "Connect a VNC client to 127.0.0.1:$vnc_port"
  fi

  export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-$(id -u)}"
  mkdir -p "$XDG_RUNTIME_DIR"
  chmod 700 "$XDG_RUNTIME_DIR"

  log "Starting $SESSION_BIN against $DISPLAY"
  if [ "${ROOTFORGE_SKIP_DBUS:-0}" != 1 ] && have dbus-launch; then
    dbus-launch --exit-with-session "$SESSION_BIN" &
  else
    [ "${ROOTFORGE_SKIP_DBUS:-0}" = 1 ] || log "dbus-launch missing — session daemons may misbehave"
    "$SESSION_BIN" &
  fi
  echo $! > "$PID_FILE"
  log "Session pid $(cat "$PID_FILE"). Open VictorSuite's X11 view (or the VNC client)."
}

case "$op" in
  setup) setup_now ;;
  status) status_now ;;
  stop) stop_now ;;
  start) start_now ;;
esac
