#!/usr/bin/env bash
# RootForge OS — Termux:X11 desktop launcher
# Victorious Framework | Origin Source Labs
#
# Starts an XFCE session inside the RootForge container and points it at the
# Termux:X11 server running on the Android side.
#
# How the pieces fit together:
#
#   Android                          RootForge container (PRoot or chroot)
#   ------------------------------   ------------------------------------
#   Termux:X11 APK   <-- X proto --   xfce4-session, thunar, ...
#     (the X server)   over a unix     (the X clients)
#                      socket in
#                      $TMPDIR/.X11-unix
#
# The X server is the Android app; nothing inside the container provides one.
# That is also why this needs no root: an X client just talks to a socket.
#
# Usage:
#   rootforge_desktop.sh            start the desktop session
#   rootforge_desktop.sh --check    report what is present without starting
#   rootforge_desktop.sh --install  apt-install the desktop packages
#
# Requirements on the Termux side, done once, OUTSIDE this container:
#   pkg install x11-repo
#   pkg install termux-x11-nightly
#   # plus the Termux:X11 APK from the termux/termux-x11 GitHub releases
#   termux-x11 :0 &
# then open the Termux:X11 app, and log into the container with the shared
# tmp so the socket is visible:
#   proot-distro login rootforge --shared-tmp

set -euo pipefail

DISPLAY_NUM="${ROOTFORGE_X11_DISPLAY:-:0}"
# Termux's own tmp, bind-mounted into the container by `--shared-tmp` (PRoot)
# or by the chroot launcher's mount list. The socket lives here, not in the
# container's own /tmp, because the server is an Android app.
X11_SOCKET_DIR="${ROOTFORGE_X11_SOCKET_DIR:-/tmp/.X11-unix}"

log() { echo "[desktop] $*"; }
die() { echo "[desktop] ERROR: $*" >&2; exit 1; }

DESKTOP_PACKAGES="xfce4 xfce4-session xfce4-terminal dbus-x11 x11-xserver-utils"

have_desktop() {
  command -v xfce4-session >/dev/null 2>&1
}

report() {
  echo "Termux:X11 desktop readiness"
  echo "============================"
  printf '  %-22s %s\n' "DISPLAY" "$DISPLAY_NUM"
  printf '  %-22s %s\n' "socket dir" "$X11_SOCKET_DIR"

  if [[ -d "$X11_SOCKET_DIR" ]]; then
    local sockets
    sockets="$(find "$X11_SOCKET_DIR" -maxdepth 1 -name 'X*' 2>/dev/null | tr '\n' ' ')"
    if [[ -n "$sockets" ]]; then
      printf '  %-22s %s\n' "X server socket" "found:$sockets"
    else
      printf '  %-22s %s\n' "X server socket" "directory exists but is empty — is 'termux-x11 :0' running?"
    fi
  else
    printf '  %-22s %s\n' "X server socket" "$X11_SOCKET_DIR missing — log in with 'proot-distro login rootforge --shared-tmp'"
  fi

  if have_desktop; then
    printf '  %-22s %s\n' "xfce4-session" "$(command -v xfce4-session)"
  else
    printf '  %-22s %s\n' "xfce4-session" "not installed — run: $0 --install"
  fi
  command -v dbus-launch >/dev/null 2>&1 \
    && printf '  %-22s %s\n' "dbus-launch" "$(command -v dbus-launch)" \
    || printf '  %-22s %s\n' "dbus-launch" "not installed (part of dbus-x11)"
}

case "${1:---start}" in
  --check)
    report
    exit 0
    ;;
  --install)
    log "Installing the desktop layer ($DESKTOP_PACKAGES)"
    log "This is a few hundred MB — it is deliberately not in the base rootfs."
    sudo apt-get update -y
    # shellcheck disable=SC2086
    sudo apt-get install -y --no-install-recommends $DESKTOP_PACKAGES
    have_desktop || die "apt finished but xfce4-session is still not on PATH."
    log "Installed. Start it with: $0"
    exit 0
    ;;
  --start|"")
    ;;
  -h|--help)
    sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    die "Unknown argument: $1 (expected --start, --check, --install)"
    ;;
esac

# --- start ----------------------------------------------------------------

if ! have_desktop; then
  die "No desktop installed. Run '$0 --install' first, or rebuild the rootfs with --with-x11."
fi

if [[ ! -d "$X11_SOCKET_DIR" ]]; then
  die "$X11_SOCKET_DIR does not exist.
       The X server is the Termux:X11 Android app, and its socket reaches this
       container through Termux's tmp. Log in with:
         proot-distro login rootforge --shared-tmp
       (or use the chroot launcher, which mounts it for you)."
fi

if ! find "$X11_SOCKET_DIR" -maxdepth 1 -name 'X*' 2>/dev/null | grep -q .; then
  die "No X server socket in $X11_SOCKET_DIR.
       On the Termux side, outside this container, run:
         termux-x11 $DISPLAY_NUM &
       then open the Termux:X11 app so the server is actually listening."
fi

export DISPLAY="$DISPLAY_NUM"
# XFCE reaches for a session bus; without one, panel and settings daemons
# fail in ways that look like a broken desktop rather than a missing bus.
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-$(id -u)}"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"

log "Starting XFCE against $DISPLAY"
log "Switch to the Termux:X11 app to see it."

if command -v dbus-launch >/dev/null 2>&1; then
  exec dbus-launch --exit-with-session xfce4-session
else
  log "dbus-launch not found (install dbus-x11) — starting without a session bus;"
  log "  expect the panel and settings daemons to misbehave."
  exec xfce4-session
fi
