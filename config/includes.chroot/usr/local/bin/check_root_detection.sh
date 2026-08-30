#!/usr/bin/env bash
# RootForge OS — root detection / stealth verification
# Victorious Framework
#
# Runs the checks a root-detection library or Play Integrity attestation
# would run, against a connected device or emulator, so you know whether
# your DenyList/Shamiko/Zygisk hiding config actually holds before you
# ship a module. This does NOT call the real Play Integrity API — that
# requires a signed app talking to Google's servers and can't be
# meaningfully scripted here. What it does is the same static/dynamic
# surface real detectors check, so you catch the obvious leaks yourself.
#
# Usage: ./check_root_detection.sh [device-serial] [--checker-apk /path/to/checker.apk]

set -euo pipefail

SERIAL=""
CHECKER_APK=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --checker-apk) CHECKER_APK="$2"; shift ;;
    *) SERIAL="$1" ;;
  esac
  shift
done

ADB="adb"
[[ -n "$SERIAL" ]] && ADB="adb -s $SERIAL"

LOG_DIR="${ROOTFORGE_HOME:-$HOME/rootforge}/logs"
mkdir -p "$LOG_DIR"
REPORT="$LOG_DIR/root_detection_$(date +%Y%m%d_%H%M%S).md"

$ADB wait-for-device

# Every probe below reads a command's output and concludes "clean" when that
# output is empty. An unreachable device produces empty output too, so a
# failed probe used to be reported as a PASS — the worst direction for a tool
# whose whole job is telling you whether your hiding config holds. Confirm
# the device answers at all before drawing any conclusion from silence.
if ! $ADB shell echo __rootforge_probe__ 2>/dev/null | grep -q __rootforge_probe__; then
  echo "Cannot reach the device over adb (${SERIAL:-no serial given})." >&2
  echo "Refusing to run: every check here reads silence as 'clean', so a report" >&2
  echo "produced without a reachable device would be misleading rather than empty." >&2
  exit 1
fi

# adb_probe <remote-command> — run it on the device and print its output.
# Returns 1 if the device could not be reached, so a caller can tell an empty
# result from a failed one. The sentinel is what distinguishes them: adb
# itself exits non-zero both when the connection fails and when the remote
# command merely returns non-zero.
PROBE_OUT=""
adb_probe() {
  local raw
  raw="$($ADB shell "echo __rootforge_probe__; $1" 2>/dev/null || true)"
  case "$raw" in
    *__rootforge_probe__*) ;;
    *) return 1 ;;
  esac
  PROBE_OUT="$(printf '%s' "$raw" | sed 's/__rootforge_probe__//' | tr -d '\r')"
  return 0
}

pass=0; fail=0; warn=0
echo "# Root detection report — $(date)" > "$REPORT"
echo "" >> "$REPORT"

check() {
  local desc="$1" result="$2" detail="${3:-}"
  case "$result" in
    PASS) pass=$((pass+1)); echo "- **PASS** — $desc${detail:+ ($detail)}" >> "$REPORT" ;;
    FAIL) fail=$((fail+1)); echo "- **FAIL** — $desc${detail:+ ($detail)}" >> "$REPORT" ;;
    WARN) warn=$((warn+1)); echo "- **WARN** — $desc${detail:+ ($detail)}" >> "$REPORT" ;;
  esac
}

echo "== RootForge root-detection sweep =="

# 1. Build tags — release-keys is what a locked stock device reports
TAGS="$($ADB shell getprop ro.build.tags 2>/dev/null | tr -d '\r')"
[[ "$TAGS" == *"release-keys"* ]] && check "ro.build.tags" PASS "$TAGS" || check "ro.build.tags" FAIL "$TAGS — test-keys/dev-keys is an easy static flag"

# 2. Verified boot state
VBSTATE="$($ADB shell getprop ro.boot.verifiedbootstate 2>/dev/null | tr -d '\r')"
[[ "$VBSTATE" == "green" ]] && check "verified boot state" PASS "$VBSTATE" || check "verified boot state" FAIL "$VBSTATE — expected 'green' for a stock-signature boot chain"

# 3. ro.debuggable / ro.secure
DEBUGGABLE="$($ADB shell getprop ro.debuggable 2>/dev/null | tr -d '\r')"
SECURE="$($ADB shell getprop ro.secure 2>/dev/null | tr -d '\r')"
[[ "$DEBUGGABLE" == "0" && "$SECURE" == "1" ]] && check "ro.debuggable / ro.secure" PASS "0 / 1" || check "ro.debuggable / ro.secure" FAIL "$DEBUGGABLE / $SECURE"

# 4. Common su binary paths
SU_PATHS=(/system/bin/su /system/xbin/su /sbin/su /system/sd/xbin/su \
          /system/bin/failsafe/su /data/local/xbin/su /data/local/bin/su \
          /data/local/su /su/bin/su /system/bin/.ext/.su)
FOUND_SU=""
SU_PROBE_OK=1
for p in "${SU_PATHS[@]}"; do
  if adb_probe "[ -e $p ] && echo found"; then
    case "$PROBE_OUT" in *found*) FOUND_SU="$FOUND_SU $p" ;; esac
  else
    SU_PROBE_OK=0
    break
  fi
done
if [[ $SU_PROBE_OK -eq 0 ]]; then
  check "su binary in common paths" WARN "device stopped answering mid-probe — result unknown, not clean"
elif [[ -z "$FOUND_SU" ]]; then
  check "su binary in common paths" PASS
else
  check "su binary in common paths" FAIL "found at:$FOUND_SU"
fi

# 5. Known root/manager package names (default, non-hidden identifiers)
PKG_CANDIDATES=(com.topjohnwu.magisk eu.chainfire.supersu com.noshufou.android.su \
                 com.koushikdutta.superuser com.thirdparty.superuser me.weishu.kernelsu \
                 me.weishu.exposed org.lsposed.manager com.kingroot.kinguser)
if ! adb_probe "pm list packages"; then
  check "known root manager package names" WARN "could not list packages — result unknown, not clean"
elif [[ -z "${PROBE_OUT//[[:space:]]/}" ]]; then
  # `pm list packages` returning nothing at all is not a device with no
  # packages; it is a query that did not work.
  check "known root manager package names" WARN "'pm list packages' returned nothing — result unknown, not clean"
else
  INSTALLED_PKGS="$PROBE_OUT"
  FOUND_PKG=""
  for p in "${PKG_CANDIDATES[@]}"; do
    # -F: these names are full of dots, which are regex wildcards otherwise.
    echo "$INSTALLED_PKGS" | grep -qF "$p" && FOUND_PKG="$FOUND_PKG $p"
  done
  [[ -z "$FOUND_PKG" ]] && check "known root manager package names" PASS "no default (non-randomized) package names visible" \
    || check "known root manager package names" FAIL "found:$FOUND_PKG — enable Magisk's randomized package name + hide app option"
fi

# 6. Magisk DenyList / Zygisk status (best-effort; requires su)
if $ADB shell "su -c 'magisk -v'" >/dev/null 2>&1; then
  DENYLIST_STATUS="$($ADB shell "su -c 'magisk --denylist status'" 2>/dev/null | tr -d '\r' || true)"
  if [[ "$DENYLIST_STATUS" == *"enforcing"* ]]; then
    check "Magisk DenyList enforcement" PASS "$DENYLIST_STATUS"
  else
    check "Magisk DenyList enforcement" WARN "status='$DENYLIST_STATUS' — enable DenyList and add the target app(s) if this is a hide-sensitive build"
  fi
else
  check "Magisk DenyList enforcement" WARN "could not reach su -c 'magisk -v' — device may use KernelSU (check separately) or su isn't reachable this session"
fi

# 7. Mount namespace leak — /proc/mounts referencing Magisk paths
if ! adb_probe "cat /proc/self/mountinfo"; then
  check "mount namespace leak (/proc/self/mountinfo)" WARN "could not read mountinfo — result unknown, not clean"
elif [[ -z "${PROBE_OUT//[[:space:]]/}" ]]; then
  check "mount namespace leak (/proc/self/mountinfo)" WARN "mountinfo came back empty — result unknown, not clean"
else
  MOUNT_LEAK="$(echo "$PROBE_OUT" | grep -ci 'magisk\|worker' || true)"
  [[ "$MOUNT_LEAK" -eq 0 ]] && check "mount namespace leak (/proc/self/mountinfo)" PASS \
    || check "mount namespace leak (/proc/self/mountinfo)" FAIL "$MOUNT_LEAK matching lines — SUS mount hiding (susfs) may be needed for this kernel"
fi

echo "" >> "$REPORT"
echo "**Summary:** $pass pass / $warn warn / $fail fail" >> "$REPORT"
cat "$REPORT"

# 8. Optional: sideload a real checker APK (e.g. an open-source Play
# Integrity/SafetyNet verdict viewer you supply) and surface its logcat output.
if [[ -n "$CHECKER_APK" ]]; then
  echo ""
  echo "== Sideloading checker APK for a live attestation read =="
  echo "[Certain] This still calls Google's real servers from the device — it is the"
  echo "closest thing to ground truth, but requires the device to have working Play"
  echo "services and network access, and results depend on Google's server-side risk"
  echo "signals, not just what's on-device."
  $ADB install -r "$CHECKER_APK"
  echo "APK installed. Launch it manually and read the verdict — automating the UI"
  echo "read reliably across app versions isn't something to build into a static script."
fi

echo ""
echo "Full report: $REPORT"

# Victorious Framework
