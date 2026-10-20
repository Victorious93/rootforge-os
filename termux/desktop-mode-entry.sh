#!/usr/bin/env bash
# RootForge OS — Termux-side Desktop mode entry
# Victorious Framework | Origin Source Labs
#
# Installed as $PREFIX/bin/rootforge-desktop-mode so VictorSuite's RUN_COMMAND
# can exec a single documented command. Logs into the Debian rootfs and runs
# rootforge_desktop_mode.sh. Never interpolates extra arguments from the
# caller beyond start|stop|setup|status.
#
# Seams: ROOTFORGE_PROOT_DISTRO, ROOTFORGE_CHROOT_DIR, ROOTFORGE_CHROOT_LOGIN.

set -euo pipefail

op="${1:-start}"
case "$op" in
  start|stop|setup|status) ;;
  -h|--help)
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    echo "rootforge-desktop-mode: unknown operation '$op' (expected start, stop, setup, status)" >&2
    exit 1
    ;;
esac

PROOT_DISTRO="${ROOTFORGE_PROOT_DISTRO:-proot-distro}"
CHROOT_DIR="${ROOTFORGE_CHROOT_DIR:-}"
CHROOT_LOGIN="${ROOTFORGE_CHROOT_LOGIN:-}"
INNER="/usr/local/bin/rootforge_desktop_mode.sh"

if command -v "$PROOT_DISTRO" >/dev/null 2>&1; then
  exec "$PROOT_DISTRO" login rootforge --shared-tmp -- "$INNER" "$op"
fi

if [ -n "$CHROOT_LOGIN" ]; then
  exec "$CHROOT_LOGIN" "$INNER" "$op"
fi

if [ -n "$CHROOT_DIR" ] && [ -x "$CHROOT_DIR$INNER" ]; then
  echo "rootforge-desktop-mode: found a chroot at $CHROOT_DIR but no login helper." >&2
  echo "Run $INNER $op from inside the chroot, or set ROOTFORGE_CHROOT_LOGIN." >&2
  exit 1
fi

echo "rootforge-desktop-mode: no RootForge Debian rootfs found." >&2
echo "Install it with proot-distro (plugin: rootforge) or the chroot launcher, then re-run." >&2
exit 1
