#!/usr/bin/env bash
# RootForge OS — AVD generation: rooted and unrooted emulator sessions
# Victorious Framework
#
# Creates AVDs from configurable API level / device profile / ABI / system
# image tag, auto-installing the system image via sdkmanager if it isn't
# present yet. Unrooted AVDs are just avdmanager wrapped with sane defaults.
# Rooted AVDs get patched with a real Magisk ramdisk patch (magiskinit
# swapped in as /init, magisk32/64 + magiskpolicy staged under overlay.d) —
# the same mechanism Magisk's own boot_patch.sh uses on a real device boot
# image, adapted for the goldfish/ranchu emulator's separate ramdisk.img
# rather than a packed boot.img.
#
# Each created AVD gets a profile file under $ROOTFORGE_HOME/avd-profiles/
# so `boot` can recall its mode (rooted/unrooted) and snapshot without you
# having to remember which AVDs were rooted.
#
# Usage:
#   setup_rooted_avd.sh create --name <avd> --mode rooted|unrooted [options]
#   setup_rooted_avd.sh boot   --name <avd> [--snapshot <name>]
#   setup_rooted_avd.sh list
#
# Options for create:
#   --api <level>      Android API level (default: 34)
#   --device <profile> avdmanager device profile (default: pixel_6)
#   --abi <abi>        x86_64 | x86 | arm64-v8a (default: x86_64)
#   --tag <tag>        google_apis | google_apis_playstore | default | google_tv
#                       (default: google_apis — rooted mode refuses
#                       google_apis_playstore, since Play images are signed
#                       and locked in ways that resist the writable-system
#                       trick and a ramdisk swap)
#   --force            recreate the AVD even if it already exists
#
# Requires: avdmanager, sdkmanager, emulator, adb on PATH (source
#   /etc/profile.d/rootforge.sh); magiskboot on PATH for rooted mode
#   (installed at build time — see 0060-magiskboot.hook.chroot).

set -euo pipefail

# shellcheck source=../lib/rootforge/sh/common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../lib/rootforge/sh/common.sh"

ROOTFORGE_HOME="${ROOTFORGE_HOME:-$HOME/rootforge}"
LOG_DIR="$ROOTFORGE_HOME/logs"
PROFILE_DIR="$ROOTFORGE_HOME/avd-profiles"
WORK_DIR="$ROOTFORGE_HOME/avd-work"
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
mkdir -p "$LOG_DIR" "$PROFILE_DIR" "$WORK_DIR"
STAMP="$(date +%Y%m%d_%H%M%S)"

log() { echo "[avd] $*"; }
die() { echo "[avd] ERROR: $*" >&2; exit 1; }

# A value-taking option whose value is missing reads an unset $2 and aborts
# with a raw "unbound variable" under `set -u`. Say what's wrong instead.
need_value() {
  [[ $2 -ge 2 ]] || die "$1 requires a value"
}

usage() {
  sed -n '2,33p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

# The AVD name becomes a profile filename, an AVD directory name and a work
# directory name. `create` validated it; `boot` did not, so
#
#   setup_rooted_avd.sh boot --name '../../escaped'
#
# read its mode from a .conf outside the profile directory and handed the
# name straight to `emulator`. One helper, both commands, so the two cannot
# drift apart again.
require_valid_avd_name() {
  [[ "$1" =~ ^[A-Za-z0-9._-]+$ ]] \
    || die "--name must be [A-Za-z0-9._-] (got '$1') — it becomes a profile filename and an AVD directory name"
}

# `x="$(grep -m1 KEY= file | cut ...)"` aborts the whole script under
# `set -o pipefail` when the key isn't present — so a profile that was
# hand-edited, truncated, or written by an older version of this script made
# `list` and `boot` exit silently with no output at all. Read the key
# without letting a miss become a fatal pipeline status.
profile_get() {
  local file="$1" key="$2" default="${3:-}"
  local value
  value="$(grep -m1 "^${key}=" "$file" 2>/dev/null | cut -d= -f2- || true)"
  printf '%s' "${value:-$default}"
}

cmd_list() {
  echo "avdmanager-known AVDs:"
  avdmanager list avd 2>/dev/null | grep -E "Name:|    Based on:|    Tag/ABI:" | sed 's/^/  /' \
    || echo "  (none)"
  echo
  echo "RootForge profiles ($PROFILE_DIR):"
  shopt -s nullglob
  local found=0
  for f in "$PROFILE_DIR"/*.conf; do
    found=1
    local name mode api device abi tag
    name="$(basename "$f" .conf)"
    mode="$(profile_get "$f" MODE '?')"
    api="$(profile_get "$f" API '?')"
    device="$(profile_get "$f" DEVICE '?')"
    abi="$(profile_get "$f" ABI '?')"
    tag="$(profile_get "$f" TAG '?')"
    echo "  $name  mode=$mode api=$api device=$device abi=$abi tag=$tag"
  done
  shopt -u nullglob

  # `[[ $found -eq 0 ]] && echo ...` as the final command made this function
  # — and therefore the whole script, since `list` is the last thing it runs
  # — return the *condition's* status. So `list` exited 1 whenever it
  # successfully found profiles, and 0 only when there were none: exactly
  # backwards, and invisible unless something checked the code.
  if [[ $found -eq 0 ]]; then
    echo "  (none)"
  fi
  return 0
}

cmd_boot() {
  local name="" snapshot=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --name)     need_value "$1" $#; name="$2";     shift 2 ;;
      --snapshot) need_value "$1" $#; snapshot="$2"; shift 2 ;;
      *) die "Unknown option: $1" ;;
    esac
  done
  [[ -z "$name" ]] && die "--name required"
  require_valid_avd_name "$name"

  local profile="$PROFILE_DIR/${name}.conf"
  local mode="unrooted"
  if [[ -f "$profile" ]]; then
    mode="$(profile_get "$profile" MODE unrooted)"
  else
    log "No saved profile for '$name' — booting with defaults."
  fi

  [[ -z "$snapshot" && "$mode" == "rooted" ]] && snapshot="rootforge-rooted"

  if [[ -n "$snapshot" ]]; then
    log "Booting '$name' (mode=$mode) from snapshot '$snapshot'"
    emulator -avd "$name" -snapshot "$snapshot"
  else
    log "Booting '$name' (mode=$mode)"
    emulator -avd "$name"
  fi
}

root_avd() {
  local name="$1" abi="$2" LOG_FILE="$3"
  # The emulator this function starts. Overridable for an unusual port
  # assignment; the default is what `emulator` uses for the first instance.
  local EMU_SERIAL="${ROOTFORGE_AVD_SERIAL:-emulator-5554}"

  local MAGISKBOOT
  MAGISKBOOT="$(command -v magiskboot 2>/dev/null || true)"
  [[ -n "$MAGISKBOOT" ]] || die "magiskboot not on PATH. It ships prebuilt on RootForge OS (0060-magiskboot.hook.chroot) — install it manually if missing."

  local avd_dir="$ANDROID_AVD_HOME/${name}.avd"
  [[ -d "$avd_dir" ]] || die "AVD directory not found: $avd_dir"
  local ramdisk="$avd_dir/ramdisk.img"
  [[ -f "$ramdisk" ]] || die "ramdisk.img not found in $avd_dir — unexpected AVD layout for this SDK version"

  if [[ ! -f "$avd_dir/ramdisk.img.stock" ]]; then
    cp "$ramdisk" "$avd_dir/ramdisk.img.stock"
    log "Backed up stock ramdisk to ramdisk.img.stock (revert by copying it back over ramdisk.img)"
  fi

  mkdir -p "$ROOTFORGE_HOME/bin"
  local MAGISK_APK="$ROOTFORGE_HOME/bin/magisk.apk"
  # `[[ ! -f ]]` treated any file at this path as a usable cache, and curl
  # wrote straight to it — so a download cut short left a partial APK that
  # every later run reused. `unzip -p` then extracted nothing and the script
  # died with
  #
  #   magiskinit not present in this Magisk APK for abi=x86_64 — pick an ABI
  #   Magisk actually ships lib/<abi>/ for
  #
  # which sends the user to change an ABI that was never wrong. Verified with
  # a 13-byte cached APK. A confidently wrong error is worse than none.
  #
  # rf_download_cached treats an implausibly small file as absent, so it also
  # recovers a cache the old code already poisoned. A Magisk APK is ~10 MB.
  if [[ ! -f "$MAGISK_APK" ]] || [[ "$(wc -c < "$MAGISK_APK")" -lt 1048576 ]]; then
    log "Fetching latest Magisk release APK (cached at $MAGISK_APK for future runs)"
    local url
    url=$(curl -fsSL "https://api.github.com/repos/topjohnwu/Magisk/releases/latest" \
      | python3 -c "import sys,json; a=[x['browser_download_url'] for x in json.load(sys.stdin)['assets'] if x['name'].endswith('.apk') and 'stub' not in x['name']]; print(a[0] if a else '')")
    [[ -z "$url" ]] && die "Could not resolve latest Magisk APK download URL"
    rf_download_cached "$url" "$MAGISK_APK" 1048576 \
      || die "Could not download a usable Magisk APK — nothing was patched."
  fi

  # Distinguish "this APK is not readable" from "this ABI is not shipped".
  unzip -l "$MAGISK_APK" >/dev/null 2>&1 \
    || die "$MAGISK_APK is not a readable zip — delete it and re-run to refetch."

  local rwork="$WORK_DIR/${name}-root-${STAMP}"
  mkdir -p "$rwork"

  log "Extracting Magisk $abi components from $MAGISK_APK"
  local component
  for component in libmagisk32.so libmagisk64.so libmagiskinit.so libmagiskpolicy.so; do
    unzip -p "$MAGISK_APK" "lib/${abi}/${component}" > "$rwork/$component" 2>/dev/null \
      && mv "$rwork/$component" "$rwork/${component#lib}" \
      || rm -f "$rwork/$component"
  done
  # libmagiskinit.so -> magiskinit.so at this point; drop the trailing .so
  local f
  for f in "$rwork"/*.so; do
    [[ -f "$f" ]] || continue
    mv "$f" "${f%.so}"
  done
  chmod 755 "$rwork"/magisk* 2>/dev/null || true

  # Reached only when the APK itself is readable, so this really is about the
  # ABI now rather than standing in for a broken download.
  [[ -f "$rwork/magiskinit" ]] || die "magiskinit not present in this Magisk APK for abi=$abi — pick an ABI Magisk actually ships lib/<abi>/ for (x86, x86_64, armeabi-v7a, arm64-v8a)"

  log "Patching ramdisk.img with magiskboot (init -> magiskinit, overlay.d payload)"
  cp "$ramdisk" "$rwork/ramdisk.img"
  (
    cd "$rwork"
    "$MAGISKBOOT" cpio ramdisk.img \
      "add 0750 init magiskinit" \
      "mkdir 0750 overlay.d" \
      "mkdir 0750 overlay.d/sbin" \
      "add 0755 overlay.d/sbin/magisk32 magisk32" \
      "add 0755 overlay.d/sbin/magisk64 magisk64" \
      "add 0755 overlay.d/sbin/magiskpolicy magiskpolicy" \
      "patch" \
      "backup ramdisk_orig.cpio" \
      "mkdir 000 .backup" \
      "add 000 .backup/.magisk config" \
      2>&1 | tee -a "$LOG_FILE"
  )
  log "[Likely] this cpio layout mirrors Magisk's own boot_patch.sh for the cached release;"
  log "if 'su -c id' fails to verify below, diff against scripts/boot_patch.sh in the Magisk"
  log "source tree for the version at $MAGISK_APK and adjust the cpio command list above."

  cp "$rwork/ramdisk.img" "$ramdisk"

  log "Booting '$name' writable with the patched ramdisk for first-boot root verification"
  emulator -avd "$name" -writable-system -no-snapshot-load -snapshot rootforge-rooted -no-window &
  local EMU_PID=$!

  # Both waits below were unbounded: `adb wait-for-device` blocks forever if
  # the emulator dies on startup (a bad AVD, no KVM, an image that won't
  # boot), and the on-device `while [ -z $(getprop sys.boot_completed) ]`
  # loop blocks forever if the system never finishes booting. Neither
  # printed anything while it hung, so the script looked wedged with no clue
  # why. Bound both, and notice when the emulator process is already gone.
  local boot_timeout="${ROOTFORGE_AVD_BOOT_TIMEOUT:-300}"
  local waited=0
  log "Waiting up to ${boot_timeout}s for the emulator to boot"
  while true; do
    if ! kill -0 "$EMU_PID" 2>/dev/null; then
      die "The emulator exited during boot — see $LOG_FILE and try 'emulator -avd $name' by hand to see its error."
    fi
    # `-s emulator-5554` is deliberate: adb without a serial fails outright
    # ("more than one device") the moment anything else is plugged in, and
    # the emulator is the only thing this step should be talking to.
    if [[ "$(adb -s "$EMU_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      log "Emulator finished booting after ${waited}s"
      break
    fi
    if [[ $waited -ge $boot_timeout ]]; then
      adb emu kill 2>/dev/null || true
      kill "$EMU_PID" 2>/dev/null || true
      die "Emulator did not report sys.boot_completed within ${boot_timeout}s. Raise ROOTFORGE_AVD_BOOT_TIMEOUT, or boot it with a window ('emulator -avd $name') to see where it stalls."
    fi
    sleep 2
    waited=$((waited + 2))
  done
  sleep 2

  log "Verifying root"
  if adb -s "$EMU_SERIAL" shell su -c id 2>/dev/null | grep -q "uid=0"; then
    log "Root verified: 'su -c id' reports uid=0"
  else
    log "WARNING: could not verify root via 'su -c id'. Install the Magisk manager APK"
    log "(adb -s $EMU_SERIAL install \"$MAGISK_APK\") and inspect on-device state with check_root_detection.sh."
  fi

  log "Shutting down to save the 'rootforge-rooted' snapshot"
  adb -s "$EMU_SERIAL" emu kill 2>/dev/null || true
  wait "$EMU_PID" 2>/dev/null || true

  log "Rooted AVD ready. Boot pre-rooted with:"
  log "  setup_rooted_avd.sh boot --name $name"
  log "  (equivalent to: emulator -avd $name -snapshot rootforge-rooted)"
}

cmd_create() {
  local name="" mode="" api="34" device="pixel_6" abi="x86_64" tag="google_apis" force=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --name)   need_value "$1" $#; name="$2";   shift 2 ;;
      --mode)   need_value "$1" $#; mode="$2";   shift 2 ;;
      --api)    need_value "$1" $#; api="$2";    shift 2 ;;
      --device) need_value "$1" $#; device="$2"; shift 2 ;;
      --abi)    need_value "$1" $#; abi="$2";    shift 2 ;;
      --tag)    need_value "$1" $#; tag="$2";    shift 2 ;;
      --force)  force=1;     shift ;;
      *) die "Unknown option: $1" ;;
    esac
  done

  [[ -z "$name" ]] && die "--name required"
  [[ "$mode" == "rooted" || "$mode" == "unrooted" ]] || die "--mode must be 'rooted' or 'unrooted'"
  # $api and $abi are interpolated into the sdkmanager package spec below,
  # and $name into a profile filename — keep them to what those accept so a
  # typo fails here with a clear message rather than deep inside sdkmanager.
  [[ "$api" =~ ^[0-9]{1,3}$ ]] || die "--api must be a number like 33 or 34 (got '$api')"
  require_valid_avd_name "$name"
  case "$abi" in
    x86_64|x86|arm64-v8a|armeabi-v7a) ;;
    *) die "--abi must be x86_64, x86, arm64-v8a or armeabi-v7a (got '$abi')" ;;
  esac
  case "$tag" in
    google_apis|google_apis_playstore|default|google_tv|android-wear|android-tv) ;;
    *) die "--tag '$tag' is not one this script knows; expected google_apis, google_apis_playstore, default or google_tv" ;;
  esac
  if [[ "$mode" == "rooted" && "$tag" == "google_apis_playstore" ]]; then
    die "Rooted mode requires a non-Play system image (--tag google_apis, default, or google_tv) — Play images are signed and locked in ways that resist both the writable-system trick and a ramdisk swap."
  fi

  command -v avdmanager >/dev/null 2>&1 || die "avdmanager not on PATH — source /etc/profile.d/rootforge.sh"
  command -v sdkmanager >/dev/null 2>&1 || die "sdkmanager not on PATH — source /etc/profile.d/rootforge.sh"
  command -v emulator   >/dev/null 2>&1 || die "emulator not on PATH"

  if [[ ! -e /dev/kvm ]]; then
    log "WARNING: /dev/kvm not present — the emulator will fall back to unaccelerated"
    log "software rendering (TCG). Expected under Termux/PRoot without root, or any host"
    log "without virtualization enabled. Usable for a basic smoke test, painfully slow"
    log "for real work — see 'kvm-ok' (cpu-checker) on a real Linux host to diagnose."
  fi

  local LOG_FILE="$LOG_DIR/avd_${name}_${STAMP}.log"
  local IMAGE="system-images;android-${api};${tag};${abi}"

  if ! sdkmanager --list_installed 2>/dev/null | grep -qF "$IMAGE"; then
    log "System image $IMAGE not installed — installing via sdkmanager"
    # yes(1) gets SIGPIPE once sdkmanager exits and closes stdin, which pipefail
    # would otherwise misreport as pipeline failure — check sdkmanager's own
    # PIPESTATUS slot instead of the pipeline's overall exit code.
    set +o pipefail
    yes | sdkmanager "$IMAGE" 2>&1 | tee -a "$LOG_FILE"
    local sdk_rc=${PIPESTATUS[1]}
    set -o pipefail
    [[ $sdk_rc -eq 0 ]] || die "sdkmanager install failed for $IMAGE — accept licenses first with 'sdkmanager --licenses'"
  fi

  local exists=0
  avdmanager list avd 2>/dev/null | grep -q "Name: $name$" && exists=1

  if [[ $exists -eq 1 && $force -eq 1 ]]; then
    log "Deleting existing AVD '$name' (--force)"
    avdmanager delete avd -n "$name" 2>>"$LOG_FILE" || true
    exists=0
  fi

  if [[ $exists -eq 1 ]]; then
    log "AVD '$name' already exists — reusing. Pass --force to recreate from scratch."
  else
    log "Creating AVD '$name' from $IMAGE (device=$device)"
    echo "no" | avdmanager create avd -n "$name" -k "$IMAGE" --device "$device" 2>>"$LOG_FILE"
  fi

  cat > "$PROFILE_DIR/${name}.conf" <<EOF
NAME=$name
MODE=$mode
API=$api
DEVICE=$device
ABI=$abi
TAG=$tag
IMAGE=$IMAGE
CREATED=$STAMP
EOF
  log "Saved profile: $PROFILE_DIR/${name}.conf"

  if [[ "$mode" == "unrooted" ]]; then
    log "Unrooted AVD ready:"
    log "  emulator -avd $name"
    exit 0
  fi

  root_avd "$name" "$abi" "$LOG_FILE"
}

CMD="${1:-}"
[[ -z "$CMD" ]] && usage 1
shift || true

case "$CMD" in
  create) cmd_create "$@" ;;
  boot)   cmd_boot "$@" ;;
  list)   cmd_list "$@" ;;
  -h|--help) usage 0 ;;
  *) die "Unknown command: $CMD (expected create|boot|list)" ;;
esac

# Victorious Framework
