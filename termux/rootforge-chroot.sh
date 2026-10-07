#!/data/data/com.termux/files/usr/bin/bash
# RootForge OS — rooted chroot launcher for Android
# Victorious Framework | Origin Source Labs
#
# The rooted counterpart to the PRoot variant. Runs the RootForge rootfs in a
# REAL chroot via `su`, rather than PRoot's ptrace-based emulation.
#
# What this buys you over PRoot, and what it does not:
#
#   Genuinely better with root
#     - No ptrace syscall interception. PRoot intercepts every syscall to fake
#       uid 0 and rewrite paths; a real chroot does not, so builds and greps
#       over big trees run several times faster.
#     - Real device nodes. /dev/block/* and USB are visible, so adb/fastboot
#       over USB work without Termux:API's permission dance, and
#       inspect_partition_image.sh can actually loop-mount an image.
#     - /dev/net/tun, so a userspace VPN (wireguard-go, tailscaled) works.
#
#   NOT fixed by root, because this still runs on Android's own kernel
#     - harden_system.sh: AppArmor, auditd, nftables and USBGuard are not in
#       Android kernels (Android uses SELinux, and the rest simply are not
#       compiled in). Root gives you uid 0, not a different kernel.
#     - harden_kernel.sh: same, plus there is no GRUB on a phone to edit.
#     - setup_rooted_avd.sh: /dev/kvm needs the SoC booted at EL2 with a
#       KVM-enabled kernel. Root does not create that node.
#     - build_matrix.sh: Docker needs cgroup controllers and overlayfs that
#       most Android kernels do not ship. Root is necessary, not sufficient —
#       try it and see rather than assuming either way.
#
# Usage, from Termux (this script runs OUTSIDE the container):
#   ./rootforge-chroot.sh install <rootfs.tar.xz> --sha256 <hex>
#   ./rootforge-chroot.sh install <rootfs.tar.xz> --sha256-file <file.sha256>
#                                                   verify, then unpack a built rootfs
#   ./rootforge-chroot.sh login                     enter it
#   ./rootforge-chroot.sh umount                    tear the mounts down
#
# `install` unpacks as root, so it refuses an archive whose SHA-256 it was not
# given and cannot match. The digest comes from the release's SHA256SUMS (or
# the .sha256 beside a locally built tarball). The archive is checked for
# absolute or ".." member names and for members that pass through an earlier
# symlink, unpacked into a staging directory, and only moved into place — with
# a completion marker that `login` requires — once it has unpacked in full.
#
# Requires: a rooted device with a working `su`, and tar/xz in Termux.

set -euo pipefail

# Lives on shared storage rather than under Termux's private data dir: a
# chroot needs exec permission on the filesystem holding it, and Android
# mounts /sdcard noexec.
ROOTFS_DIR="${ROOTFORGE_CHROOT_DIR:-/data/local/rootforge}"

log()  { echo "[rootforge-chroot] $*"; }
die()  { echo "[rootforge-chroot] ERROR: $*" >&2; exit 1; }

# Android's `su -c` takes one string and hands it to a shell, so the command
# has to be built as text — which makes quoting the whole ballgame.
# `as_root "$*"` re-joins its arguments and re-parses them, so a rootfs path
# containing a space or a quote (ROOTFORGE_CHROOT_DIR is user-settable)
# silently becomes several arguments, or a syntax error, at every mount.
#
# rf_q quotes one value so it survives that round trip. Every call site below
# passes paths through it rather than hand-wrapping them in single quotes:
# hand-quoting works right up until someone edits a call site, which is
# exactly how this class of bug gets reintroduced.
rf_q() {
  # Wrap in single quotes, escaping embedded single quotes as '\''.
  # Deliberately parameter expansion, not a sed pipeline: the sed form needs
  # four backslashes to emit one, and a two-backslash version collapses to
  # ''' — which looks correct, passes a casual read, and silently breaks the
  # first path containing a quote. This is the same implementation as
  # rf_shell_quote in usr/local/lib/rootforge/sh/common.sh, which the test
  # suite covers; that file can't be sourced here because this script runs
  # on the Termux side, outside the container.
  local q="'\\''"
  printf "'%s'" "${1//\'/$q}"
}

as_root() {
  su -c "$*"
}

require_root() {
  command -v su >/dev/null 2>&1 || die "No 'su' on PATH — this launcher is for rooted devices. Use the PRoot variant instead: proot-distro login rootforge"
  if ! su -c 'id -u' 2>/dev/null | grep -q '^0$'; then
    die "'su' did not grant root (check your root manager's prompt, and that Termux is allowed).
       If this device is not rooted, use the PRoot variant instead:
         proot-distro login rootforge"
  fi
}

INSTALL_MARKER="etc/rootforge/install-complete"

# Prints the offending member names, one per line, nothing when the archive is
# safe to unpack as root. Two failure classes:
#   - an absolute name or a ".." component, which would land outside the
#     target directory;
#   - a member whose path passes *through* a symlink the archive itself
#     created earlier, which could redirect a later write anywhere.
# Member types and names are listed separately and paired by line; a name
# containing a newline would desynchronise them, so a mismatch is refused.
scan_archive() {
  local tarball="$1" types names
  types="$(mktemp)"; names="$(mktemp)"
  if ! tar -tvJf "$tarball" 2>/dev/null | cut -c1 > "$types" || ! tar -tJf "$tarball" > "$names" 2>/dev/null; then
    rm -f "$types" "$names"
    echo "(the archive could not be listed — is it a valid .tar.xz?)"
    return 0
  fi
  if [[ "$(wc -l < "$types")" != "$(wc -l < "$names")" ]]; then
    rm -f "$types" "$names"
    echo "(member names contain newlines; refusing to interpret the listing)"
    return 0
  fi
  paste -d '\t' "$types" "$names" | awk -F '\t' '
    {
      type = $1; name = $2
      sub(/\/$/, "", name)
      if (name ~ /^\// || name ~ /(^|\/)\.\.(\/|$)/) { print name; next }
      n = split(name, parts, "/"); path = ""
      for (i = 1; i < n; i++) {
        path = (i == 1) ? parts[i] : path "/" parts[i]
        if (path in links) { print name; next }
      }
      if (type == "l") links[name] = 1
    }'
  rm -f "$types" "$names"
}

cmd_install() {
  local tarball="" expected=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --sha256)
        [[ $# -ge 2 ]] || die "--sha256 needs a value"
        expected="$2"; shift 2 ;;
      --sha256-file)
        [[ $# -ge 2 ]] || die "--sha256-file needs a value"
        [[ -f "$2" ]] || die "Checksum file not found: $2"
        expected="$(awk 'NF { print $1; exit }' "$2")"; shift 2 ;;
      -*) die "Unknown option: $1" ;;
      *)
        [[ -z "$tarball" ]] || die "Unexpected argument: $1"
        tarball="$1"; shift ;;
    esac
  done
  [[ -n "$tarball" ]] || die "Usage: rootforge-chroot.sh install <rootfs.tar.xz> --sha256 <hex> | --sha256-file <file.sha256>"
  [[ -f "$tarball" ]] || die "Tarball not found: $tarball"
  [[ -n "$expected" ]] || die "No checksum given — refusing to unpack an unverified archive as root.
       Pass --sha256 <hex> or --sha256-file <file.sha256> (from the release's SHA256SUMS,
       or the .sha256 file beside a tarball you built yourself)."
  [[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] || die "'$expected' is not a SHA-256 hex digest (64 hex characters)."

  local actual
  actual="$(sha256sum "$tarball" | awk '{print $1}')"
  if [[ "${actual,,}" != "${expected,,}" ]]; then
    die "Checksum mismatch for $tarball
       expected: ${expected,,}
       actual:   ${actual,,}
       Nothing was unpacked. Re-download the archive."
  fi
  log "Checksum verified: $actual"

  local unsafe=()
  mapfile -t unsafe < <(scan_archive "$tarball")
  if [[ ${#unsafe[@]} -gt 0 ]]; then
    die "The archive contains members that are not safe to unpack as root:
$(printf '       %s\n' "${unsafe[@]}")
       Nothing was unpacked."
  fi

  require_root

  # A partly-unpacked rootfs from an interrupted run is worse than none: it
  # looks installed and fails deep inside. Unpack beside the target and move
  # it into place only when complete.
  if as_root "[ -e $(rf_q "$ROOTFS_DIR") ]"; then
    die "$ROOTFS_DIR already exists. Remove it first if you mean to reinstall:
       su -c 'rm -rf $ROOTFS_DIR'"
  fi
  local staging="${ROOTFS_DIR}.partial"
  # Only this launcher's own leftover staging directory is removed here.
  as_root "rm -rf $(rf_q "$staging")"
  as_root "mkdir -p $(rf_q "$staging")"

  log "Unpacking $tarball (this takes a while)"
  if ! as_root "tar -xJf $(rf_q "$tarball") -C $(rf_q "$staging")"; then
    as_root "rm -rf $(rf_q "$staging")"
    die "Unpacking failed. The partial copy was removed; nothing is installed."
  fi

  # Without a resolver the container has no DNS at all; Android's own
  # resolv.conf is not in a place the chroot can see.
  as_root "printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > $(rf_q "$staging/etc/resolv.conf")"
  as_root "printf 'rootforge-chroot\n' > $(rf_q "$staging/etc/hostname")"
  as_root "mkdir -p $(rf_q "$staging/etc/rootforge") && printf '%s\n' $(rf_q "$actual") > $(rf_q "$staging/$INSTALL_MARKER")"
  as_root "mv $(rf_q "$staging") $(rf_q "$ROOTFS_DIR")" || die "Could not move the unpacked rootfs into place; it remains at $staging"

  log "Installed. Enter it with: $0 login"
}

# Mounts the kernel filesystems the container needs. Ordering matters: /dev
# before /dev/pts, and everything before the chroot itself.
mount_all() {
  as_root "mkdir -p $(rf_q "$ROOTFS_DIR/proc") $(rf_q "$ROOTFS_DIR/sys") $(rf_q "$ROOTFS_DIR/dev") $(rf_q "$ROOTFS_DIR/dev/pts") $(rf_q "$ROOTFS_DIR/tmp")"

  as_root "mountpoint -q $(rf_q "$ROOTFS_DIR/proc") || mount -t proc proc $(rf_q "$ROOTFS_DIR/proc")"
  as_root "mountpoint -q $(rf_q "$ROOTFS_DIR/sys")  || mount -t sysfs sysfs $(rf_q "$ROOTFS_DIR/sys")"
  # A bind of the real /dev is what makes loop devices, block devices and
  # /dev/net/tun reachable — the whole point of the rooted variant.
  as_root "mountpoint -q $(rf_q "$ROOTFS_DIR/dev")  || mount -o bind /dev $(rf_q "$ROOTFS_DIR/dev")"
  as_root "mountpoint -q $(rf_q "$ROOTFS_DIR/dev/pts") || mount -t devpts devpts $(rf_q "$ROOTFS_DIR/dev/pts")"

  # Termux's tmp carries the Termux:X11 socket. Binding it here is what lets
  # rootforge_desktop.sh find an X server, exactly as --shared-tmp does for
  # the PRoot variant.
  local termux_tmp="/data/data/com.termux/files/usr/tmp"
  if [[ -d "$termux_tmp" ]]; then
    as_root "mountpoint -q $(rf_q "$ROOTFS_DIR/tmp") || mount -o bind $(rf_q "$termux_tmp") $(rf_q "$ROOTFS_DIR/tmp")"
  fi

  # Shared storage, so files can be moved in and out without root on the
  # Termux side. Absent on some devices/profiles, hence the guard.
  if [[ -d /sdcard ]]; then
    as_root "mkdir -p $(rf_q "$ROOTFS_DIR/sdcard")"
    as_root "mountpoint -q $(rf_q "$ROOTFS_DIR/sdcard") || mount -o bind /sdcard $(rf_q "$ROOTFS_DIR/sdcard")"
  fi
}

cmd_umount() {
  require_root
  log "Unmounting container filesystems"
  # Reverse order, and lazily: a shell still open inside the container would
  # otherwise hold /dev busy and leave the rest half-torn-down.
  for m in sdcard tmp dev/pts dev sys proc; do
    as_root "mountpoint -q $(rf_q "$ROOTFS_DIR/$m") && umount -l $(rf_q "$ROOTFS_DIR/$m")" || true
  done
  log "Done."
}

cmd_login() {
  require_root
  as_root "[ -d $(rf_q "$ROOTFS_DIR") ]" || die "No rootfs at $ROOTFS_DIR — run: $0 install <rootfs.tar.xz> --sha256 <hex>"
  as_root "[ -f $(rf_q "$ROOTFS_DIR/$INSTALL_MARKER") ]" || die "$ROOTFS_DIR has no completed-install marker, so it may be a partial or unverified unpack.
       Reinstall it with: $0 install <rootfs.tar.xz> --sha256 <hex>"

  mount_all

  log "Entering $ROOTFS_DIR (exit the shell to return to Termux)"
  log "Run '$0 umount' afterwards to release the bind mounts."

  # PATH is set explicitly: a chroot inherits Termux's PATH, which points at
  # /data/data/com.termux/... paths that do not exist inside the container.
  as_root "env -i \
    HOME=/root \
    TERM=$(rf_q "${TERM:-xterm-256color}") \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    ROOTFORGE_CONTAINER=chroot \
    chroot $(rf_q "$ROOTFS_DIR") /bin/bash --login"
}

CMD="${1:-}"
shift || true
case "$CMD" in
  install) cmd_install "$@" ;;
  login)   cmd_login "$@" ;;
  umount)  cmd_umount "$@" ;;
  -h|--help|"")
    sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
    [[ -z "$CMD" ]] && exit 1
    exit 0
    ;;
  *) die "Unknown command: $CMD (expected install, login, umount)" ;;
esac
