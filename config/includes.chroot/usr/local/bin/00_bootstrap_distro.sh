#!/usr/bin/env bash
# RootForge OS — bootstrap installer
# Victorious Framework
#
# Provisions a Debian 12 (Bookworm) amd64 base with the Android root-module
# development stack: SDK/NDK, KVM emulator acceleration, kernel
# cross-toolchains and the boot-image tools Magisk / KernelSU development needs.
#
# Usage: 00_bootstrap_distro.sh [--user NAME] [--only system|user] [--headless] [--check]
#   --user NAME   whose workspace to provision (see "Who it provisions for")
#   --only system root-level stages only: packages, udev rules, group membership
#   --only user   that user's workspace and Android SDK only (no root needed
#                 when run as that user)
#   --headless    skip the GNOME desktop check/install (CI/build-server profile)
#   --check       print the resolved user, paths and stage status, change nothing
#
# Who it provisions for. This used to default to $SUDO_USER or whoever ran it,
# which for rootforge-firstboot.service (root, no login user) meant root's
# /root/rootforge. It no longer guesses, and never falls back to root. In order:
#   1. --user NAME
#   2. $SUDO_USER, when it is not root
#   3. the account the installer recorded in /var/lib/rootforge/install-user
#      (written by Calamares' shellprocess module from the user page)
#   4. the invoking user, when this is not running as root
# If none applies it stops and says so. An explicit "--user root" is honored.
#
# Stages are resumable. Each writes a completion marker only after it succeeds,
# so an interrupted or offline run continues where it stopped, and the
# first-boot service's own "done" flag is only written when the whole script
# exits 0:
#   system (root):  packages  udev  groups        markers: /var/lib/rootforge/provision/
#   user:           workspace  sdk                markers: <workspace>/.provision/
# The user stages run as that user, so everything they create is theirs from
# the start, and they never touch another user's files or the shared
# /etc/profile.d/rootforge.sh, which already derives each user's paths.

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

STATE_DIR="${ROOTFORGE_STATE_DIR:-/var/lib/rootforge}"
INSTALL_USER_FILE="${ROOTFORGE_INSTALL_USER_FILE:-$STATE_DIR/install-user}"
UDEV_RULES="${ROOTFORGE_UDEV_RULES:-/etc/udev/rules.d/51-android.rules}"
APT_GET="${ROOTFORGE_APT_GET:-apt-get}"
SELF="$(readlink -f "${BASH_SOURCE[0]}")"

usage() {
  echo "Usage: $0 [--user NAME] [--only system|user] [--headless] [--check]" >&2
  echo "  --user NAME   whose workspace to provision (never guessed; never root unless named)" >&2
  echo "  --only STAGE  'system' (root stages) or 'user' (that user's workspace + SDK)" >&2
  echo "  --headless    skip the GNOME desktop check/install" >&2
  echo "  --check       print the resolved user, paths and stage status, changing nothing" >&2
}

HEADLESS=0
CHECK_ONLY=0
ONLY=""
EXPLICIT_USER=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --headless) HEADLESS=1; shift ;;
    --check)    CHECK_ONLY=1; shift ;;
    --user)
      [[ $# -ge 2 ]] || { echo "--user needs a value" >&2; usage; exit 1; }
      EXPLICIT_USER="$2"; shift 2 ;;
    --only)
      [[ $# -ge 2 ]] || { echo "--only needs system or user" >&2; usage; exit 1; }
      case "$2" in system|user) ONLY="$2" ;; *) echo "--only must be system or user (got '$2')" >&2; usage; exit 1 ;; esac
      shift 2 ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

is_root() { [[ "${ROOTFORGE_TEST_EUID:-$EUID}" -eq 0 ]]; }

valid_username() { [[ "$1" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]]; }

# --- who ---------------------------------------------------------------------
TARGET_USER=""
TARGET_SOURCE=""
if [[ -n "$EXPLICIT_USER" ]]; then
  TARGET_USER="$EXPLICIT_USER"; TARGET_SOURCE="--user"
elif [[ -n "${SUDO_USER:-}" && "${SUDO_USER:-}" != "root" ]]; then
  TARGET_USER="$SUDO_USER"; TARGET_SOURCE="\$SUDO_USER"
elif [[ -s "$INSTALL_USER_FILE" ]]; then
  TARGET_USER="$(head -n 1 "$INSTALL_USER_FILE" | tr -d '[:space:]')"; TARGET_SOURCE="$INSTALL_USER_FILE"
elif ! is_root; then
  TARGET_USER="$(id -un)"; TARGET_SOURCE="the invoking user"
fi

if [[ -z "$TARGET_USER" ]]; then
  echo "Cannot tell which user to provision for." >&2
  echo "  This is running as root with no --user, no \$SUDO_USER and no $INSTALL_USER_FILE" >&2
  echo "  (the installer writes that file). Re-run as: $0 --user <login name>" >&2
  exit 1
fi
valid_username "$TARGET_USER" || { echo "'$TARGET_USER' is not a valid login name (from $TARGET_SOURCE)." >&2; exit 1; }

TARGET_HOME="$(getent passwd "$TARGET_USER" 2>/dev/null | cut -d: -f6 || true)"
if [[ -z "$TARGET_HOME" ]]; then
  echo "Could not resolve a home directory for '$TARGET_USER' via getent." >&2
  echo "Set ROOTFORGE_HOME explicitly and re-run." >&2
  exit 1
fi
# System accounts have a home of /nonexistent or /. Installing a 15 GB SDK
# there is not what anyone meant, and the failure would surface much later.
if [[ -z "${ROOTFORGE_HOME:-}" && ! -d "$TARGET_HOME" ]]; then
  echo "'$TARGET_USER' has home '$TARGET_HOME', which does not exist." >&2
  echo "That is usually a system account. Run this for the account that will use" >&2
  echo "RootForge, or set ROOTFORGE_HOME explicitly." >&2
  exit 1
fi

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$TARGET_HOME/rootforge}"
SDK_ROOT="$ROOTFORGE_HOME/android-sdk"
LOG_DIR="$ROOTFORGE_HOME/logs"
USER_MARKERS="$ROOTFORGE_HOME/.provision"
SYSTEM_MARKERS="$STATE_DIR/provision"
STAMP="$(date +%Y%m%d_%H%M%S)"

SYSTEM_STAGES=(packages udev groups)
USER_STAGES=(workspace sdk)

stage_status() {  # stage_status <markers-dir> <stage>
  if [[ -f "$1/$2.done" ]]; then echo "done"; else echo "pending"; fi
}

if [[ $CHECK_ONLY -eq 1 ]]; then
  echo "target user:     $TARGET_USER  (from $TARGET_SOURCE)"
  echo "target home:     $TARGET_HOME"
  echo "ROOTFORGE_HOME:  $ROOTFORGE_HOME"
  echo "SDK_ROOT:        $SDK_ROOT"
  echo "desktop install: $([[ $HEADLESS -eq 1 ]] && echo skipped || echo GNOME)"
  for st in "${SYSTEM_STAGES[@]}"; do echo "stage system/$st: $(stage_status "$SYSTEM_MARKERS" "$st")"; done
  for st in "${USER_STAGES[@]}"; do echo "stage user/$st: $(stage_status "$USER_MARKERS" "$st")"; done
  exit 0
fi

log() { echo "[rootforge] $*" | tee -a "${LOG_FILE:-/dev/null}"; }

# run_stage <markers-dir> <stage> <function> — skip if done, run, then mark.
run_stage() {
  local markers="$1" stage="$2" fn="$3"
  if [[ -f "$markers/$stage.done" ]]; then
    log "stage $stage: already complete, skipping"
    return 0
  fi
  log "stage $stage: starting"
  "$fn"
  mkdir -p "$markers"
  date -u +%Y-%m-%dT%H:%M:%SZ > "$markers/$stage.done"
  log "stage $stage: complete"
}

# --- system stages (root) -------------------------------------------------------
stage_packages() {
  # No `apt-get upgrade`: SDK setup is not the moment to upgrade the whole
  # system, and the desktop is not reinstalled when it is already there.
  "$APT_GET" update -y
  "$APT_GET" install -y --no-install-recommends \
    build-essential git curl wget unzip zip rsync ccache \
    openjdk-17-jdk \
    python3 python3-pip python3-venv python3-yaml \
    clang lld llvm binutils-aarch64-linux-gnu gcc-aarch64-linux-gnu \
    bc bison flex libssl-dev libelf-dev dwarves cpio kmod \
    qemu-kvm libvirt-daemon-system virtinst bridge-utils cpu-checker \
    android-sdk-platform-tools-common adb fastboot \
    android-sdk-libsparse-utils abootimg e2fsprogs \
    jq docker.io \
    gnupg lsb-release
  if [[ $HEADLESS -eq 0 ]]; then
    if dpkg -s gnome-shell >/dev/null 2>&1; then
      log "GNOME already installed; not reinstalling it"
    else
      "$APT_GET" install -y --no-install-recommends \
        gnome-session gnome-shell gnome-terminal gnome-control-center gdm3 gnome-tweaks
    fi
  fi
}

stage_udev() {
  local rules
  rules='# RootForge OS — generic Android device access (adb + fastboot)
# Victorious Framework
SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666", GROUP="plugdev"
SUBSYSTEM=="usb", ATTR{idVendor}=="04e8", MODE="0666", GROUP="plugdev"
SUBSYSTEM=="usb", ATTR{idVendor}=="22b8", MODE="0666", GROUP="plugdev"
SUBSYSTEM=="usb", ATTR{idVendor}=="2717", MODE="0666", GROUP="plugdev"
SUBSYSTEM=="usb", ATTR{idVendor}=="12d1", MODE="0666", GROUP="plugdev"'
  mkdir -p "$(dirname "$UDEV_RULES")"
  if [[ "$(cat "$UDEV_RULES" 2>/dev/null || true)" != "$rules" ]]; then
    printf '%s\n' "$rules" > "$UDEV_RULES"
  fi
  udevadm control --reload-rules || true
  udevadm trigger || true
}

stage_groups() {
  # Only groups that exist: usermod fails outright on an unknown one, and the
  # old `|| true` hid that the others were skipped too.
  local g present=()
  for g in kvm plugdev docker; do
    if getent group "$g" >/dev/null 2>&1; then present+=("$g"); else log "group $g does not exist; skipping it"; fi
  done
  if [[ ${#present[@]} -gt 0 ]]; then
    usermod -aG "$(IFS=,; echo "${present[*]}")" "$TARGET_USER"
  fi
}

# --- user stages (run as the target user) ----------------------------------------
stage_workspace() {
  mkdir -p "$ROOTFORGE_HOME"/{devices,modules,kernels,keys,avd-profiles,logs}
  chmod 700 "$ROOTFORGE_HOME/keys"
}

stage_sdk() {
  [[ "$(rf_host_arch)" == "amd64" ]] || {
    echo "This installer provisions amd64 systems; Google's SDK binaries are x86-64 only." >&2
    echo "On other CPUs use bootstrap_proot.sh, which installs only what can run." >&2
    return 1
  }
  rf_require_cmd javac "install a JDK (apt install openjdk-17-jdk)"
  rf_require_cmd unzip "apt install unzip"
  rf_require_cmd curl "apt install curl"

  # Staged beside the destination and owned by this user (this stage runs as
  # them), so nothing root created is ever handed to an unprivileged process,
  # and a failed or repeated run never leaves a half-extracted tool tree.
  local stage rc=0
  stage="$(mktemp -d "$ROOTFORGE_HOME/.sdk-stage.XXXXXX")"
  sdk_install "$stage" || rc=$?
  rm -rf "$stage"
  return "$rc"
}

sdk_install() {
  local stage="$1" zip="$1/cmdline-tools.zip"
  log "Fetching Android cmdline-tools (pinned, SHA-256 verified)"
  rf_fetch_verified "$RF_CMDLINE_TOOLS_URL" "$zip" "$RF_CMDLINE_TOOLS_SHA256"
  unzip -q "$zip" -d "$stage/unpacked"
  [[ -d "$stage/unpacked/cmdline-tools" ]] || { echo "Unexpected archive layout: no cmdline-tools/ directory" >&2; return 1; }
  mkdir -p "$SDK_ROOT/cmdline-tools"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$stage/unpacked/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"

  local sdkmanager="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" java_home
  java_home="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  log "Accepting SDK licenses and installing platform-tools, build-tools, NDK, emulator"
  yes | JAVA_HOME="$java_home" "$sdkmanager" --sdk_root="$SDK_ROOT" --licenses > /dev/null || true
  JAVA_HOME="$java_home" "$sdkmanager" --sdk_root="$SDK_ROOT" \
    "platform-tools" "emulator" "build-tools;34.0.0" "platforms;android-34" \
    "ndk;26.1.10909125" "system-images;android-34;google_apis;x86_64"
}

# --- run ------------------------------------------------------------------------------
# Re-run this script's user stages as the target user (from root).
reexec_as_user() {
  local extra=()
  [[ $HEADLESS -eq 1 ]] && extra+=(--headless)
  runuser -u "$TARGET_USER" -- env HOME="$TARGET_HOME" ROOTFORGE_HOME="$ROOTFORGE_HOME" \
    bash "$SELF" --only user --user "$TARGET_USER" "${extra[@]}"
}

run_user_stages() {
  mkdir -p "$LOG_DIR" "$USER_MARKERS"
  LOG_FILE="$LOG_DIR/bootstrap_${STAMP}.log"
  rf_log_init "$LOG_FILE"
  log "Provisioning $TARGET_USER's workspace at $ROOTFORGE_HOME"
  run_stage "$USER_MARKERS" workspace stage_workspace
  run_stage "$USER_MARKERS" sdk stage_sdk
  if [[ "$ROOTFORGE_HOME" != "$TARGET_HOME/rootforge" ]]; then
    log "NOTE: the workspace is not at the default ~/rootforge; export ROOTFORGE_HOME=$ROOTFORGE_HOME in that user's shell."
  fi
  if kvm-ok >/dev/null 2>&1; then log "KVM acceleration: available"
  else log "WARNING: KVM not available — the emulator will fall back to software rendering."; fi
}

if [[ "$ONLY" == "user" ]]; then
  # Direct user-only run: as the target user, or as root on their behalf.
  if [[ "$(id -un)" != "$TARGET_USER" ]] && ! is_root; then
    echo "--only user for '$TARGET_USER' must run as that user or as root." >&2
    exit 1
  fi
  if [[ "$(id -un)" == "$TARGET_USER" ]]; then
    run_user_stages
  else
    reexec_as_user
  fi
  exit 0
fi

if ! is_root; then
  echo "The system stages need root (apt, udev, group membership). Re-run with sudo," >&2
  echo "or use --only user to provision just your own workspace." >&2
  exit 1
fi

mkdir -p "$STATE_DIR" "$SYSTEM_MARKERS"
LOG_FILE="$STATE_DIR/bootstrap_${STAMP}.log"
rf_log_init "$LOG_FILE"
log "System stages for $TARGET_USER (from $TARGET_SOURCE)"
run_stage "$SYSTEM_MARKERS" packages stage_packages
run_stage "$SYSTEM_MARKERS" udev stage_udev
run_stage "$SYSTEM_MARKERS" groups stage_groups

if [[ "$ONLY" == "system" ]]; then
  log "System stages complete."
  exit 0
fi

# The workspace tree is created by, and owned by, the target user.
if [[ "$(id -un)" == "$TARGET_USER" ]]; then
  run_user_stages
else
  reexec_as_user
fi

log "Bootstrap complete. Log $TARGET_USER out and in to pick up kvm/plugdev/docker group membership."
log "Next: clone Magisk source into \$ROOTFORGE_HOME/modules and run build_magisk_module.sh, or run setup_rooted_avd.sh."

# Victorious Framework
