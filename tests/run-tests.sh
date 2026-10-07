#!/usr/bin/env bash
# RootForge OS — test runner
# Victorious Framework | Origin Source Labs
#
# See tests/README.md. Runs without a device, Docker, or network access.
#
# Usage: tests/run-tests.sh [shell|python]

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN_DIR="$REPO_ROOT/config/includes.chroot/usr/local/bin"
LIB_DIR="$REPO_ROOT/config/includes.chroot/usr/local/lib"
STUB_DIR="$REPO_ROOT/tests/stubs"

WHICH="${1:-all}"

PASS=0
FAIL=0
CURRENT=""

pass() { PASS=$((PASS+1)); printf '  ok   %s\n' "$1"; }
fail() {
  FAIL=$((FAIL+1))
  printf '  FAIL %s\n' "$1"
  [ -n "${2:-}" ] && printf '       %s\n' "$2"
  return 0
}

# assert_contains <label> <haystack> <needle>
assert_contains() {
  case "$2" in
    *"$3"*) pass "$1" ;;
    *) fail "$1" "expected to find: $3" ;;
  esac
}

assert_not_contains() {
  case "$2" in
    *"$3"*) fail "$1" "did not expect to find: $3" ;;
    *) pass "$1" ;;
  esac
}

assert_eq() {
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected '$3', got '$2'"; fi
}

# Fresh sandbox per test: HOME is redirected so scripts write their logs and
# backups into scratch space, and the stubs shadow the real adb/fastboot.
new_sandbox() {
  SANDBOX="$(mktemp -d)"
  export HOME="$SANDBOX/home"
  export ROOTFORGE_HOME="$SANDBOX/home/rootforge"
  mkdir -p "$HOME"
  export RF_STUB_LOG="$SANDBOX/stub.log"
  : > "$RF_STUB_LOG"
  export PATH="$STUB_DIR:$ORIGINAL_PATH"
  # Bound every wait to a single check so a "no device" case is instant.
  export ROOTFORGE_FASTBOOT_WAIT=0 ROOTFORGE_BOOT_WAIT=0
  # harden_kernel.sh writes a sysctl drop-in and runs `sysctl --system`.
  # Without this the suite modifies the machine it runs on — verified: the
  # drop-in was present on the host, timestamped by the last run. Set here
  # rather than per-section so a future test cannot forget it.
  export ROOTFORGE_SYSCTL_FILE="$SANDBOX/sysctl-dropin.conf"
  # Everything a previous section may have exported. A value leaking into the
  # next sandbox makes a test pass or fail for a reason that is nowhere in
  # its own body — already hit once with PATH, and again with
  # ROOTFORGE_WG_ENDPOINT.
  unset RF_STUB_ADB_DEVICES RF_STUB_FASTBOOT_DEVICES RF_STUB_SLOT \
        RF_STUB_FLASH_RC RF_STUB_GETVAR_ALL RF_STUB_ADB_SHELL_OUT \
        RF_STUB_FLASH_FAIL_ON_CALL RF_STUB_ADB_SHELL_RC \
        RF_STUB_DL_BYTES RF_STUB_DL_RC RF_STUB_RELEASE_JSON \
        RF_STUB_DUMPER_WRITES RF_STUB_PASSWD RF_STUB_USB_POLICY \
        RF_STUB_CURRENT_IME \
        RF_STUB_ADB_STATE RF_STUB_BOOT_COMPLETED RF_STUB_REBOOT_RC RF_STUB_UNLOCKED \
        RF_STUB_PRODUCT RF_STUB_FASTBOOT_RC RF_STUB_FLASH_SLEEP RF_STUB_GETVAR_ALL_2 \
        RF_STUB_TAMPER_FILE RF_STUB_FETCH_FAIL RF_STUB_ADB_PULL_RC RF_STUB_ADB_REMOTE_SUM \
        ROOTFORGE_ASSUME_YES ROOTFORGE_GRUB_DEFAULTS ROOTFORGE_NMAP_OUTPUT \
        ROOTFORGE_USBGUARD_RULES ROOTFORGE_AUDIT_RULES ROOTFORGE_NFT_FILE \
        ROOTFORGE_PROFILE_D ROOTFORGE_WG_CONF ROOTFORGE_WG_ENDPOINT \
        ROOTFORGE_X11_SOCKET_DIR ROOTFORGE_X11_DISPLAY \
        2>/dev/null || true
}

drop_sandbox() { [ -n "${SANDBOX:-}" ] && rm -rf "$SANDBOX"; }

# run_script <script> [args...] — capture combined output, expose rc in RC.
# stdin is closed so any script that tries to read a confirmation from stdin
# fails fast rather than hanging the suite.
run_script() {
  OUT="$(cd "$SANDBOX" && "$@" 2>&1 </dev/null)"
  RC=$?
  return 0
}

section() { printf '\n== %s ==\n' "$1"; }

# A boot image flash_patched_boot.sh accepts: the ANDROID! magic plus padding.
make_boot_img() { { printf 'ANDROID!'; head -c 1016 /dev/zero; } > "$1"; }

# An unlocked A/B device in fastboot mode. fb_device [serial] [slot] [product]
# The stub reports these variables on stderr, as real fastboot does.
fb_device() {
  export RF_STUB_FASTBOOT_DEVICES="${1:-FB1}\tfastboot\n"
  export RF_STUB_GETVAR_ALL="(bootloader) product: ${3:-cheetah}\n(bootloader) current-slot: ${2:-a}\n(bootloader) slot-count: 2\n(bootloader) unlocked: yes\n(bootloader) has-slot:boot: yes\n(bootloader) partition-size:boot_a: 0x4000000\n(bootloader) partition-size:boot_b: 0x4000000\n(bootloader) partition-size:init_boot_a: 0x800000\n(bootloader) partition-size:init_boot_b: 0x800000\n(bootloader) partition-size:vbmeta_a: 0x10000\n(bootloader) partition-size:vbmeta_b: 0x10000\n(bootloader) partition-size:dtbo_a: 0x1000000\n(bootloader) partition-size:dtbo_b: 0x1000000\n"
}

# make_backup <codename> <timestamp> <product|-> <slot|-> <partition>=<content>...
# Builds a captured-trust backup (images + manifest.json) without going
# through backup_partitions.sh, so restore tests do not depend on it.
make_backup() {
  local code="$1" ts="$2" product="$3" slot="$4"; shift 4
  local dir="$ROOTFORGE_HOME/devices/$code/backups/$ts" entries='[]' spec part content sha
  mkdir -p "$dir"
  for spec in "$@"; do
    part="${spec%%=*}"; content="${spec#*=}"
    printf '%s' "$content" > "$dir/$part.img"
    sha="$(sha256sum "$dir/$part.img" | cut -d' ' -f1)"
    entries="$(jq -c --arg p "$part" --arg h "$sha" --argjson s "${#content}" --arg slot "$slot" \
      '. + [{partition:$p, file:($p + ".img"), sha256:$h, size_bytes:$s, method:"fastboot-fetch",
             slot:(if $slot == "-" then null else $slot end)}]' <<<"$entries")"
  done
  jq -n --argjson e "$entries" --arg c "$code" --arg t "$ts" --arg prod "$product" --arg slot "$slot" \
    '{manifest_version: 1, trust: "captured", codename: $c, timestamp: $t, serial: "S", complete: true,
      device: {product: (if $prod == "-" then null else $prod end),
               current_slot: (if $slot == "-" then null else $slot end)},
      entries: $e}' > "$dir/manifest.json"
}

ORIGINAL_PATH="$PATH"

# ---------------------------------------------------------------------------
# Shell tests
# ---------------------------------------------------------------------------

test_shell() {

section "common.sh — device enumeration"

new_sandbox
# shellcheck source=../config/includes.chroot/usr/local/lib/rootforge/sh/common.sh
. "$LIB_DIR/rootforge/sh/common.sh"

# The bug this replaces: `adb devices | grep -qv 'List of devices'` matched
# the trailing blank line and reported a device with nothing attached.
export RF_STUB_ADB_DEVICES=""
assert_eq "no adb devices -> empty" "$(rf_adb_serials)" ""
if rf_have_adb_device; then
  fail "no adb devices -> rf_have_adb_device false" "reported a phantom device"
else
  pass "no adb devices -> rf_have_adb_device false"
fi

export RF_STUB_ADB_DEVICES='SERIAL123\tdevice\n'
assert_eq "one adb device -> its serial" "$(rf_adb_serials)" "SERIAL123"

# An unauthorized device is attached but unusable; treating it as connected
# sends every downstream command into a confusing failure.
export RF_STUB_ADB_DEVICES='SERIAL123\tunauthorized\n'
assert_eq "unauthorized device is not usable" "$(rf_adb_serials)" ""

export RF_STUB_ADB_DEVICES='AAA\tdevice\nBBB\toffline\nCCC\tdevice\n'
assert_eq "mixed states -> only 'device' rows" "$(rf_adb_serials | tr '\n' ',')" "AAA,CCC,"

export RF_STUB_FASTBOOT_DEVICES='FBSERIAL\tfastboot\n'
assert_eq "fastboot serial parsed" "$(rf_fastboot_serials)" "FBSERIAL"
drop_sandbox

section "common.sh — confirmation gate"

new_sandbox
. "$LIB_DIR/rootforge/sh/common.sh"
# With no terminal a destructive gate must refuse, never assume yes.
if ( exec </dev/null; rf_confirm FLASH "test" >/dev/null 2>&1 ); then
  fail "rf_confirm without a tty refuses" "it returned success"
else
  pass "rf_confirm without a tty refuses"
fi
if ( export ROOTFORGE_ASSUME_YES=1; rf_confirm FLASH "test" >/dev/null 2>&1 ); then
  pass "rf_confirm honors ROOTFORGE_ASSUME_YES"
else
  fail "rf_confirm honors ROOTFORGE_ASSUME_YES" "it refused"
fi
drop_sandbox

section "common.sh — rootforge CLI bridge"

new_sandbox
# rf_device_profile_json calls rf_require_cmd, which calls the `exit`
# builtin (not a normal command failure) when jq is missing. `exit` inside
# a function called *within* a $(...) command substitution terminates that
# subshell immediately, before control ever returns to any `|| true`
# written *inside* the same parentheses — only a `|| true` placed *after*
# the closing "$(...)" can catch it. Every one of the three retrofitted
# scripts depends on getting this right, so it's covered directly here
# rather than only implicitly through them.
mkdir -p "$SANDBOX/shadow-bin"
ln -sf "$(command -v bash)" "$SANDBOX/shadow-bin/bash"
CORRECT="$(PATH="$SANDBOX/shadow-bin" bash -c '
  set -euo pipefail
  . "'"$LIB_DIR"'/rootforge/sh/common.sh"
  OUT="$(rf_device_profile_json 2>/dev/null)" || true
  printf "reached-end:[%s]\n" "$OUT"
' 2>&1)"
assert_eq "|| true outside the substitution survives jq being missing" "$CORRECT" "reached-end:[]"

BROKEN="$(PATH="$SANDBOX/shadow-bin" bash -c '
  set -euo pipefail
  . "'"$LIB_DIR"'/rootforge/sh/common.sh"
  OUT="$(rf_device_profile_json 2>/dev/null || true)"
  printf "reached-end:[%s]\n" "$OUT"
' 2>&1)"
assert_eq "(regression pin) || true inside the substitution does NOT survive" "$BROKEN" ""
drop_sandbox

section "flash_patched_boot.sh — argument parsing"

new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1

# Regression: `shift 2 || true` left the image path in "$@", so a
# single-argument run set SERIAL to the image and ran `fastboot -s boot.img`.
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img"
assert_eq "a single argument flashes successfully" "$RC" "0"
assert_not_contains "single arg does not become a serial" "$(cat "$RF_STUB_LOG")" "-s $SANDBOX/boot.img"
assert_contains "the serial is the device's, resolved from fastboot" "$(cat "$RF_STUB_LOG")" "fastboot -s FB1 --slot a flash boot"

# Regression: PARTITION="${2:-boot}" swallowed the flag, so this flashed a
# partition literally named "--both-slots".
new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img" --both-slots --slots-same-build
assert_not_contains "a flag is not read as a partition" "$(cat "$RF_STUB_LOG")" "flash --both-slots"
assert_contains "--both-slots writes the active slot explicitly" "$(cat "$RF_STUB_LOG")" "--slot a flash boot"
assert_contains "--both-slots writes the other slot explicitly" "$(cat "$RF_STUB_LOG")" "--slot b flash boot"

# Writing both slots must never touch which slot is active: the old
# set-active dance left the device on the wrong slot if anything failed
# between the two writes.
assert_not_contains "the active slot is never switched" "$(cat "$RF_STUB_LOG")" "set-active"
assert_not_contains "no set_active command either" "$(cat "$RF_STUB_LOG")" "set_active"

new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img" --both-slots
assert_eq "--both-slots alone is refused" "$RC" "1"
assert_contains "the refusal says the slots' builds cannot be verified" "$OUT" "--slots-same-build"
assert_eq "the refusal happens before any device access" "$(wc -l < "$RF_STUB_LOG")" "0"

new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB9 a
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img" init_boot FB9
assert_eq "an explicit partition and serial succeed" "$RC" "0"
assert_contains "explicit partition and serial are honored" "$(cat "$RF_STUB_LOG")" "fastboot -s FB9 --slot a flash init_boot"

new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img" system
assert_eq "unsupported partition rejected" "$RC" "1"
assert_not_contains "unsupported partition never flashes" "$(cat "$RF_STUB_LOG")" "flash system"

new_sandbox
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/missing.img"
assert_eq "missing image rejected" "$RC" "1"

new_sandbox
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1
head -c 1024 /dev/zero > "$SANDBOX/notboot.img"
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/notboot.img"
assert_eq "an image without the Android header is rejected" "$RC" "1"
assert_contains "the header rejection says why" "$OUT" "ANDROID! header"
assert_not_contains "a non-boot image is never written" "$(cat "$RF_STUB_LOG")" " flash "

# The typed gate: with no terminal and no explicit opt-in nothing is written,
# even for a device that passed every check.
new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img"
assert_eq "unconfirmed flash aborts" "$RC" "1"
assert_not_contains "unconfirmed flash writes nothing" "$(cat "$RF_STUB_LOG")" " flash "
assert_contains "the plan names the device and image" "$OUT" "SHA-256:"
drop_sandbox

section "flash_patched_boot.sh — blocked writes make zero write calls"

# Every case runs with ROOTFORGE_ASSUME_YES=1: an inherited assume-yes flag
# skips the typed prompt only, never validation.
blocked_case() {  # blocked_case <label> <expected-text> [script args...]
  local label="$1" expect="$2"; shift 2
  run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img" "$@"
  assert_eq "$label: exit 3" "$RC" "3"
  assert_contains "$label: says why" "$OUT" "$expect"
  assert_not_contains "$label: zero write calls" "$(cat "$RF_STUB_LOG")" " flash "
}

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
fb_device FB1 a
export RF_STUB_GETVAR_ALL="${RF_STUB_GETVAR_ALL//unlocked: yes/unlocked: no}"
blocked_case "a locked bootloader" "locked"

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
export RF_STUB_FASTBOOT_DEVICES='FB1\tfastboot\n'
export RF_STUB_GETVAR_ALL='(bootloader) product: x\n(bootloader) current-slot: a\n(bootloader) secure: yes\n'
blocked_case "secure: yes is not an unlocked bootloader" "lock state"

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
export RF_STUB_FASTBOOT_DEVICES='FB1\tfastboot\n'
export RF_STUB_GETVAR_ALL='(bootloader) product: x\n(bootloader) current-slot: a\n'
blocked_case "an unreported lock state" "lock state"

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
blocked_case "no device attached" "no device found in fastboot mode"

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
export RF_STUB_ADB_DEVICES='AD1\tdevice\n'
blocked_case "a device only in adb mode" "no device found in fastboot mode"

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
fb_device FB1 a
export RF_STUB_FASTBOOT_DEVICES='FB1\tfastboot\nFB2\tfastboot\n'
blocked_case "two devices and no serial" "more than one device is in fastboot mode"

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
fb_device FB1 a
blocked_case "a serial that is not attached" "no device found in fastboot mode" boot NOSUCH

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
export RF_STUB_FASTBOOT_DEVICES='FB1\tfastboot\n'
export RF_STUB_GETVAR_ALL=''
blocked_case "an empty probe" "probe failed"

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
export RF_STUB_FASTBOOT_DEVICES='FB1\tfastboot\n'
export RF_STUB_GETVAR_ALL='(bootloader) product: gts4lvwifi\n(bootloader) current-slot: a\n(bootloader) unlocked: yes\n(bootloader) samsung device\n'
blocked_case "a Samsung device" "samsung"

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
fb_device FB1 a
export RF_STUB_GETVAR_ALL="${RF_STUB_GETVAR_ALL//partition-size:init_boot_a/partition-size:other_a}"
blocked_case "a partition the device does not list" "init_boot_a" init_boot

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
fb_device FB1 a
export RF_STUB_GETVAR_ALL="${RF_STUB_GETVAR_ALL//partition-size:boot_a: 0x4000000/partition-size:boot_a: 0x10}"
blocked_case "an image larger than its partition" "only 16 bytes"

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
export RF_STUB_FASTBOOT_DEVICES='FB1\tfastboot\n'
export RF_STUB_GETVAR_ALL='(bootloader) product: oldphone\n(bootloader) slot-count: 1\n(bootloader) unlocked: yes\n'
blocked_case "--both-slots on a single-slot device" "not a confirmed A/B" --both-slots --slots-same-build

# Fail closed when the validation path itself is broken: a flash must never
# proceed on the strength of "I could not check".
new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
fb_device FB1 a
mkdir -p "$SANDBOX/fakebin"
printf '#!/bin/sh\nexit 127\n' > "$SANDBOX/fakebin/rootforge"
chmod +x "$SANDBOX/fakebin/rootforge"
export PATH="$SANDBOX/fakebin:$PATH"
blocked_case "a broken rootforge CLI" "could not be validated"

# The device changing between the first check and the write.
new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
fb_device FB1 a
export RF_STUB_GETVAR_ALL_2="${RF_STUB_GETVAR_ALL//product: cheetah/product: oriole}"
blocked_case "a different device behind the same serial" "different device"

new_sandbox; make_boot_img "$SANDBOX/boot.img"; export ROOTFORGE_ASSUME_YES=1
fb_device FB1 a
export RF_STUB_GETVAR_ALL_2="${RF_STUB_GETVAR_ALL//unlocked: yes/unlocked: no}"
blocked_case "a bootloader that locked after confirmation" "changed state after confirmation"
drop_sandbox

section "flash_patched_boot.sh — the write path"

new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img"
assert_eq "a healthy flash exits 0" "$RC" "0"
assert_contains "the reboot targets the same device" "$(cat "$RF_STUB_LOG")" "fastboot -s FB1 reboot"
assert_contains "boot is checked on the same serial" "$(cat "$RF_STUB_LOG")" "adb -s FB1 get-state"
assert_not_contains "no unqualified adb wait" "$(cat "$RF_STUB_LOG")" "adb wait-for-device"
assert_not_contains "no invented fastboot wait command" "$(cat "$RF_STUB_LOG")" "wait-for-device"
assert_contains "boot completion is reported as verified" "$OUT" "Boot verified"
assert_contains "unattended use is audited" "$OUT" "UNATTENDED"

# A failed write must not reboot into a half-written partition.
new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1 RF_STUB_FLASH_RC=1
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img"
assert_eq "a failed write exits non-zero" "$RC" "1"
assert_not_contains "a failed write does not reboot" "$(cat "$RF_STUB_LOG")" "reboot"
assert_contains "a failed write says the partition may be partly written" "$OUT" "partly written"

# Writing the second slot fails: the first is intact, the active slot was
# never moved, and nothing is rebooted.
new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1 RF_STUB_FLASH_FAIL_ON_CALL=2
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img" --both-slots --slots-same-build
assert_eq "a failed second-slot write exits non-zero" "$RC" "1"
assert_contains "the failing slot is named" "$OUT" "boot_b"
assert_not_contains "no active-slot change after a failed mirror" "$(cat "$RF_STUB_LOG")" "active"
assert_not_contains "no reboot after a failed mirror" "$(cat "$RF_STUB_LOG")" "reboot"

new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1 RF_STUB_REBOOT_RC=1
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img"
assert_eq "a written image with a failed reboot exits 5" "$RC" "5"
assert_contains "the failed reboot is explained" "$OUT" "reboot"

# Write success, reboot request and verified boot are different facts.
new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1 RF_STUB_BOOT_COMPLETED=0
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img"
assert_eq "adb up but boot not completed exits 4" "$RC" "4"
assert_contains "that case is called out" "$OUT" "never reported sys.boot_completed=1"

new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1 RF_STUB_ADB_STATE=offline
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img"
assert_eq "a device that never reconnects exits 4" "$RC" "4"
assert_contains "that case is called out too" "$OUT" "did not reconnect"

new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img" --no-boot-check
assert_eq "--no-boot-check exits 4 (boot not verified)" "$RC" "4"
assert_not_contains "--no-boot-check does not poll adb" "$(cat "$RF_STUB_LOG")" "get-state"

# Interrupting during a write must say the partition is in an unknown state.
new_sandbox
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1 RF_STUB_FLASH_SLEEP=2
( cd "$SANDBOX" && exec bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img" > "$SANDBOX/out.txt" 2>&1 </dev/null ) &
FLASH_PID=$!
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  grep -q " flash " "$RF_STUB_LOG" 2>/dev/null && break
  sleep 0.25
done
kill -TERM "$FLASH_PID" 2>/dev/null
wait "$FLASH_PID"; RC=$?
OUT="$(cat "$SANDBOX/out.txt")"
assert_eq "an interrupted flash exits 130" "$RC" "130"
assert_contains "an interrupted flash reports an unknown partition state" "$OUT" "state is UNKNOWN"
assert_not_contains "an interrupted flash does not reboot" "$(cat "$RF_STUB_LOG")" "reboot"
drop_sandbox

section "extract_ota.sh — argument parsing"

new_sandbox
head -c 64 /dev/zero > "$SANDBOX/payload.bin"
# A dumper that records its arguments, so the parsed values can be asserted
# on rather than inferred from log text.
mkdir -p "$ROOTFORGE_HOME/bin"
cat > "$ROOTFORGE_HOME/bin/payload-dumper-go" <<'STUB'
#!/usr/bin/env bash
printf 'payload-dumper-go %s
' "$*" >> "$RF_STUB_LOG"
# Produces a file, because extract_ota.sh now treats an empty output
# directory as a failure. This stub used to write nothing, so the
# "extraction succeeds" assertion below was pinning the very bug that
# check fixes: exit 0 and "Extraction complete" over zero files.
OUT=""; prev=""
for a in "$@"; do [ "$prev" = "-o" ] && OUT="$a"; prev="$a"; done
[ -n "$OUT" ] && head -c 1024 /dev/zero > "$OUT/boot.img"
STUB
chmod +x "$ROOTFORGE_HOME/bin/payload-dumper-go"

# Regression: `--partitions` landed in $2 and became the output directory.
run_script bash "$BIN_DIR/extract_ota.sh" "$SANDBOX/payload.bin" --partitions boot,dtbo
if [ -d "$SANDBOX/--partitions" ]; then
  fail "flag is not used as an output directory" "created a dir named --partitions"
else
  pass "flag is not used as an output directory"
fi
assert_contains "requested partition list reaches the dumper" "$(cat "$RF_STUB_LOG")" "-p boot,dtbo"
assert_eq "extraction succeeds" "$RC" "0"

new_sandbox
head -c 64 /dev/zero > "$SANDBOX/payload.bin"
run_script bash "$BIN_DIR/extract_ota.sh" "$SANDBOX/payload.bin" --partitions
assert_eq "--partitions without a value is rejected" "$RC" "1"

new_sandbox
run_script bash "$BIN_DIR/extract_ota.sh" "$SANDBOX/nope.bin"
assert_eq "missing input rejected" "$RC" "1"
drop_sandbox

section "backup_partitions.sh"

new_sandbox
# Regression: with nothing attached this used to select MODE=adb and then
# report every partition as unfetchable.
run_script bash "$BIN_DIR/backup_partitions.sh" testdev
assert_eq "no device -> backup fails" "$RC" "1"
assert_contains "no device -> explains why" "$OUT" "No device found"

new_sandbox
fb_device FB1 a testdev
export RF_STUB_FASTBOOT_DEVICES='FB1\tfastboot\nFB2\tfastboot\n'
run_script bash "$BIN_DIR/backup_partitions.sh" testdev
assert_eq "two devices and no serial -> backup fails" "$RC" "1"
assert_contains "two devices -> asks for a serial" "$OUT" "More than one device"
assert_not_contains "two devices -> nothing fetched" "$(cat "$RF_STUB_LOG")" "fetch"

new_sandbox
fb_device FB1 a testdev
run_script bash "$BIN_DIR/backup_partitions.sh" testdev NOSUCH
assert_eq "an unattached serial -> backup fails" "$RC" "1"

# A complete backup: manifest.json is the contract, SHA256SUMS is derived.
new_sandbox
fb_device FB1 a testdev
run_script bash "$BIN_DIR/backup_partitions.sh" testdev --partitions boot,vbmeta
assert_eq "a complete backup exits 0" "$RC" "0"
BDIR="$(printf '%s\n' "$OUT" | sed -n 's/^BACKUP_DIR=//p' | tail -n 1)"
assert_eq "the exact output directory is the last line" "$(printf '%s\n' "$OUT" | tail -n 1)" "BACKUP_DIR=$BDIR"
assert_eq "the manifest exists" "$([ -f "$BDIR/manifest.json" ] && echo yes)" "yes"
assert_eq "the manifest is valid JSON" "$(jq -e . "$BDIR/manifest.json" >/dev/null 2>&1 && echo yes)" "yes"
assert_eq "the manifest records the device product" "$(jq -r '.device.product' "$BDIR/manifest.json")" "testdev"
assert_eq "the manifest marks the backup complete" "$(jq -r '.complete' "$BDIR/manifest.json")" "true"
assert_eq "the manifest records the capture method" "$(jq -r '.entries[0].method' "$BDIR/manifest.json")" "fastboot-fetch"
assert_eq "a slotted partition records its slot" "$(jq -r '.entries[] | select(.partition=="boot") | .slot' "$BDIR/manifest.json")" "a"
assert_eq "a partition not reported as slotted records no slot" "$(jq -r '.entries[] | select(.partition=="vbmeta") | .slot' "$BDIR/manifest.json")" "null"
assert_eq "the derived SHA256SUMS still checks out" "$(cd "$BDIR" && sha256sum -c --quiet SHA256SUMS >/dev/null 2>&1 && echo yes)" "yes"
STAMP_DIR="$(basename "$BDIR")"
OUT="$(cd "$SANDBOX" && PYTHONPATH="$LIB_DIR" python3 -m rootforge.core.cli backup verify testdev "$STAMP_DIR" 2>&1)"; RC=$?
assert_eq "the CLI verifies what the script captured" "$RC" "0"

# Partial: some captured, some not. Honest exit code, honest manifest.
new_sandbox
fb_device FB1 a testdev
export RF_STUB_FETCH_FAIL="vbmeta"
run_script bash "$BIN_DIR/backup_partitions.sh" testdev --partitions boot,vbmeta
assert_eq "a partial backup exits 4" "$RC" "4"
BDIR="$(printf '%s\n' "$OUT" | sed -n 's/^BACKUP_DIR=//p' | tail -n 1)"
assert_eq "a partial manifest says incomplete" "$(jq -r '.complete' "$BDIR/manifest.json")" "false"
assert_eq "a partial manifest names what is missing" "$(jq -r '.missing_partitions | join(",")' "$BDIR/manifest.json")" "vbmeta"
assert_eq "the failed partition leaves no image behind" "$(ls "$BDIR" | grep -c '^vbmeta' || true)" "0"
assert_contains "a partial backup is called partial" "$OUT" "PARTIAL"

# Nothing captured: not a success, and no empty "backup" is left behind.
new_sandbox
fb_device FB1 a testdev
export RF_STUB_FETCH_FAIL="boot vbmeta"
run_script bash "$BIN_DIR/backup_partitions.sh" testdev --partitions boot,vbmeta
assert_eq "a backup that captured nothing exits 1" "$RC" "1"
assert_contains "it says nothing was captured" "$OUT" "NOTHING was captured"
assert_eq "no empty backup directory is left" "$(ls "$ROOTFORGE_HOME/devices/testdev/backups" 2>/dev/null | wc -l | tr -d ' ')" "0"

# Partition selection: --partitions > config > built-in default.
new_sandbox
fb_device FB1 a testdev
mkdir -p "$ROOTFORGE_HOME/devices/testdev"
printf 'backup:\n  partitions: [boot]\n' > "$ROOTFORGE_HOME/devices/testdev/rootforge.yaml"
run_script bash "$BIN_DIR/backup_partitions.sh" testdev
assert_eq "config-selected partitions give a complete backup" "$RC" "0"
assert_contains "the configured partition is fetched" "$(cat "$RF_STUB_LOG")" "fetch boot"
assert_not_contains "partitions outside the config are not fetched" "$(cat "$RF_STUB_LOG")" "fetch init_boot"

new_sandbox
fb_device FB1 a testdev
mkdir -p "$ROOTFORGE_HOME/devices/testdev"
printf 'backup:\n  partitions: [boot]\n' > "$ROOTFORGE_HOME/devices/testdev/rootforge.yaml"
run_script bash "$BIN_DIR/backup_partitions.sh" testdev --partitions dtbo
assert_contains "--partitions overrides the config" "$(cat "$RF_STUB_LOG")" "fetch dtbo"
assert_not_contains "the overridden config partition is not fetched" "$(cat "$RF_STUB_LOG")" "fetch boot"

new_sandbox
fb_device FB1 a testdev
mkdir -p "$ROOTFORGE_HOME/devices/testdev"
printf 'backup:\n  partitions: [../evil]\n' > "$ROOTFORGE_HOME/devices/testdev/rootforge.yaml"
run_script bash "$BIN_DIR/backup_partitions.sh" testdev
assert_eq "an invalid config refuses to back up" "$RC" "1"
assert_contains "the config error is shown" "$OUT" "Config error"
assert_not_contains "an invalid config fetches nothing" "$(cat "$RF_STUB_LOG")" "fetch"

new_sandbox
fb_device FB1 a testdev
run_script bash "$BIN_DIR/backup_partitions.sh" testdev --partitions '../x'
assert_eq "an invalid --partitions name is rejected" "$RC" "1"
assert_not_contains "an invalid --partitions name fetches nothing" "$(cat "$RF_STUB_LOG")" "fetch"

# The adb + dd path, including its transfer check.
new_sandbox
export RF_STUB_ADB_DEVICES='AD1\tdevice\n'
export RF_STUB_ADB_SHELL_OUT=exists
run_script bash "$BIN_DIR/backup_partitions.sh" testdev --partitions boot
assert_eq "an adb capture succeeds" "$RC" "0"
BDIR="$(printf '%s\n' "$OUT" | sed -n 's/^BACKUP_DIR=//p' | tail -n 1)"
assert_eq "an adb capture records its method" "$(jq -r '.entries[0].method' "$BDIR/manifest.json")" "adb-dd"
assert_contains "an unverifiable adb transfer is flagged" "$OUT" "transfer itself is unverified"

new_sandbox
export RF_STUB_ADB_DEVICES='AD1\tdevice\n'
export RF_STUB_ADB_SHELL_OUT=exists
export RF_STUB_ADB_REMOTE_SUM="$(printf '0%.0s' $(seq 1 64))"
run_script bash "$BIN_DIR/backup_partitions.sh" testdev --partitions boot
assert_eq "a pulled copy that disagrees with the device's checksum is not kept" "$RC" "1"
assert_contains "the transfer mismatch is reported" "$OUT" "transfer check FAILED"
drop_sandbox

# Regression: the codename and timestamp are interpolated straight into a
# path under $ROOTFORGE_HOME/devices/, and nothing validated them. Before the
# guard, `backup_partitions.sh '../../escaped'` wrote to $HOME/escaped.
new_sandbox
export RF_STUB_ADB_DEVICES="X1\tdevice"
run_script bash "$BIN_DIR/backup_partitions.sh" '../../escaped'
assert_eq "a traversing codename aborts the backup" "$RC" "1"
assert_contains "the codename abort explains itself" "$OUT" "Invalid device codename"
if [ -e "$SANDBOX/home/escaped" ]; then
  fail "a traversing codename writes nothing outside devices/" "$SANDBOX/home/escaped exists"
else
  pass "a traversing codename writes nothing outside devices/"
fi
drop_sandbox

section "restore_partitions.sh — only verified manifest entries are flashed"

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot vbmeta=realvbmeta
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "a verified backup restores" "$RC" "0"
assert_contains "boot is written to the manifest's slot" "$(cat "$RF_STUB_LOG")" "fastboot -s FB1 --slot a flash boot "
assert_contains "vbmeta is written too" "$(cat "$RF_STUB_LOG")" "flash vbmeta "
assert_not_contains "a restore never reboots" "$(cat "$RF_STUB_LOG")" "reboot"
assert_contains "unattended use is audited" "$OUT" "UNATTENDED"

# The reported flaw: an *.img the manifest does not list was flashed unchecked.
new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
printf 'EXTRA-UNCHECKED' > "$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000/vendor_boot.img"
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "an unlisted extra image refuses the restore" "$RC" "3"
assert_contains "the extra image is named" "$OUT" "vendor_boot.img: UNLISTED"
assert_not_contains "nothing is flashed when there is an extra image" "$(cat "$RF_STUB_LOG")" " flash "

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
printf 'CORRUPTED' > "$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000/boot.img"
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "a changed image refuses the restore" "$RC" "3"
assert_contains "a changed image is reported" "$OUT" "boot.img:"
assert_not_contains "a changed image flashes nothing" "$(cat "$RF_STUB_LOG")" " flash "

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot vbmeta=realvbmeta
rm "$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000/vbmeta.img"
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "a missing image refuses the whole restore" "$RC" "3"
assert_not_contains "a missing image flashes nothing at all" "$(cat "$RF_STUB_LOG")" " flash "

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
: > "$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000/boot.img"
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "an emptied image refuses the restore" "$RC" "3"

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
BD="$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000"
printf 'realboot' > "$SANDBOX/elsewhere.img"
rm "$BD/boot.img"; ln -s "$SANDBOX/elsewhere.img" "$BD/boot.img"
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "a symlinked image refuses the restore, even with matching content" "$RC" "3"
assert_contains "the symlink is named" "$OUT" "SYMLINK"
assert_not_contains "a symlinked image flashes nothing" "$(cat "$RF_STUB_LOG")" " flash "

# A manifest that names a path is not trusted to.
new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
BD="$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000"
jq '.entries[0].file = "../escape.img"' "$BD/manifest.json" > "$BD/m.tmp" && mv "$BD/m.tmp" "$BD/manifest.json"
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "a manifest naming a path outside the backup is refused" "$RC" "3"
assert_not_contains "a path-escaping manifest flashes nothing" "$(cat "$RF_STUB_LOG")" " flash "

# Legacy backups: verifiable, never silently restorable.
new_sandbox
BD="$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000"
mkdir -p "$BD"
printf 'realboot' > "$BD/boot.img"
( cd "$BD" && sha256sum boot.img > SHA256SUMS )
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "a legacy backup is refused" "$RC" "3"
assert_contains "the refusal points at the import step" "$OUT" "import-legacy"
assert_not_contains "a legacy backup flashes nothing" "$(cat "$RF_STUB_LOG")" " flash "
OUT="$(cd "$SANDBOX" && PYTHONPATH="$LIB_DIR" python3 -m rootforge.core.cli backup import-legacy testdev 20240101_000000 2>&1)"; RC=$?
assert_eq "the legacy import succeeds" "$RC" "0"
assert_eq "the import is labelled legacy-imported" "$(jq -r '.trust' "$BD/manifest.json")" "legacy-imported"
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "a legacy-imported backup still needs an explicit opt-in" "$RC" "3"
assert_contains "the opt-in flag is named" "$OUT" "--accept-legacy-import"
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000 --accept-legacy-import
assert_eq "with the opt-in a legacy-imported backup restores" "$RC" "0"
drop_sandbox

section "restore_partitions.sh — the target device must match the backup"

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
fb_device FB1 a cheetah
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "an image for another device is refused" "$RC" "3"
assert_contains "the mismatch names both devices" "$OUT" "the backup is for 'testdev' but the connected device is 'cheetah'"
assert_not_contains "a wrong-device restore flashes nothing" "$(cat "$RF_STUB_LOG")" " flash "

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
fb_device FB1 b testdev
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "an image from the other slot is refused" "$RC" "3"
assert_contains "the slot mismatch is explained" "$OUT" "wrong slot"
assert_not_contains "a wrong-slot restore flashes nothing" "$(cat "$RF_STUB_LOG")" " flash "

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
fb_device FB1 a testdev
export RF_STUB_GETVAR_ALL="${RF_STUB_GETVAR_ALL//unlocked: yes/unlocked: no}"
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "a locked bootloader refuses the restore" "$RC" "3"
assert_not_contains "a locked bootloader flashes nothing" "$(cat "$RF_STUB_LOG")" " flash "

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "no device refuses the restore" "$RC" "3"

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
fb_device FB1 a testdev
export RF_STUB_FASTBOOT_DEVICES='FB1\tfastboot\nFB2\tfastboot\n'
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "two devices and no serial refuses the restore" "$RC" "3"
assert_not_contains "an ambiguous restore flashes nothing" "$(cat "$RF_STUB_LOG")" " flash "

# The device changes between the first check and the write.
new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
fb_device FB1 a testdev
export RF_STUB_GETVAR_ALL_2="${RF_STUB_GETVAR_ALL//product: testdev/product: cheetah}"
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "a device that changes after confirmation is refused" "$RC" "3"
assert_not_contains "a changed device is never written" "$(cat "$RF_STUB_LOG")" " flash "

# An image altered between verification and the write is caught by the
# per-image re-hash immediately before each flash.
new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
fb_device FB1 a testdev
export RF_STUB_TAMPER_FILE="$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "an image changed after verification is not written" "$RC" "1"
assert_contains "the late change is reported" "$OUT" "changed after verification"
assert_not_contains "a late-changed image is never flashed" "$(cat "$RF_STUB_LOG")" " flash "

# A backup whose device facts were not recorded still restores, with a warning.
new_sandbox
make_backup testdev 20240101_000000 - - boot=realboot
fb_device FB1 a cheetah
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "a manifest without device facts still restores" "$RC" "0"
assert_contains "the missing facts are called out" "$OUT" "records no device product"

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot vbmeta=realvbmeta
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000 --partitions boot
assert_eq "--partitions restores a subset" "$RC" "0"
assert_contains "the selected partition is flashed" "$(cat "$RF_STUB_LOG")" "flash boot "
assert_not_contains "an unselected partition is not flashed" "$(cat "$RF_STUB_LOG")" "flash vbmeta"

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000 --partitions dtbo
assert_eq "selecting a partition the backup lacks is refused" "$RC" "3"

# The typed gate still stands without an explicit opt-in.
new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
fb_device FB1 a testdev
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "an unconfirmed restore aborts" "$RC" "1"
assert_not_contains "an unconfirmed restore flashes nothing" "$(cat "$RF_STUB_LOG")" " flash "

# A write failure stops the restore: no later partition is attempted.
new_sandbox
make_backup testdev 20240101_000000 testdev a boot=b1 dtbo=d1 vbmeta=v1
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1 RF_STUB_FLASH_FAIL_ON_CALL=2
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "a failed write exits non-zero" "$RC" "1"
assert_contains "the report lists what was written" "$OUT" "written:       boot"
assert_contains "the report lists what was not attempted" "$OUT" "not attempted: vbmeta"
assert_eq "the third partition was never attempted" "$(grep -c ' flash ' "$RF_STUB_LOG")" "2"
assert_contains "the operator is told not to reboot" "$OUT" "Do NOT reboot"

new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1 RF_STUB_FLASH_RC=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "a restore where nothing was written is a failure" "$RC" "1"

# An incomplete backup restores what it holds and says so.
new_sandbox
make_backup testdev 20240101_000000 testdev a boot=realboot
BD="$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000"
jq '.complete = false' "$BD/manifest.json" > "$BD/m.tmp" && mv "$BD/m.tmp" "$BD/manifest.json"
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "an incomplete backup restores what it holds" "$RC" "0"
assert_contains "the incompleteness is called out" "$OUT" "INCOMPLETE"

# Regression: the codename and timestamp are interpolated straight into a
# path under $ROOTFORGE_HOME/devices/. `restore_partitions.sh testdev
# '../../../../evil'` used to read every .img from an arbitrary directory and
# flash it.
new_sandbox
mkdir -p "$ROOTFORGE_HOME/devices/testdev/backups" "$HOME/evil"
printf 'attacker' > "$HOME/evil/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev "../../../../evil"
assert_eq "a traversing timestamp aborts the restore" "$RC" "1"
assert_contains "the timestamp abort explains itself" "$OUT" "Invalid backup timestamp"
assert_not_contains "a traversing timestamp flashes nothing" "$(cat "$RF_STUB_LOG")" "flash"

new_sandbox
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" 'testdev/../../elsewhere'
assert_eq "a codename containing a separator is rejected" "$RC" "1"

# The guard must not reject the codenames people actually use.
new_sandbox
make_backup oriole_5g-2 20240101_000000 oriole_5g-2 a boot=realboot
fb_device FB1 a oriole_5g-2
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" oriole_5g-2 20240101_000000
assert_eq "an ordinary codename still restores" "$RC" "0"
assert_contains "an ordinary codename still flashes" "$(cat "$RF_STUB_LOG")" "flash boot"
drop_sandbox

section "unlock_bootloader.sh"

new_sandbox
# Vendor refusal, resolved via rootforge device info's own vendor list
# rather than this script's own grep heuristics.
export RF_STUB_FASTBOOT_DEVICES='FBSERIAL\tfastboot\n'
export RF_STUB_GETVAR_ALL='(bootloader) product: gts4lvwifi\n(bootloader) unlocked: no\n(bootloader) samsung device\n'
run_script bash "$BIN_DIR/unlock_bootloader.sh"
assert_eq "Samsung refusal exits 2" "$RC" "2"
assert_contains "Samsung refusal cites Knox" "$OUT" "Knox"
assert_contains "device info path is used when it resolves a device" "$OUT" "Device profile via rootforge device info"
assert_not_contains "a refused vendor is never unlocked" "$(cat "$RF_STUB_LOG")" "unlock"

new_sandbox
export RF_STUB_FASTBOOT_DEVICES='FBSERIAL\tfastboot\n'
export RF_STUB_GETVAR_ALL='(bootloader) product: whatever\n(bootloader) unlocked: no\nxiaomi bootloader\n'
run_script bash "$BIN_DIR/unlock_bootloader.sh"
assert_eq "Xiaomi refusal exits 2" "$RC" "2"
assert_contains "Xiaomi refusal cites Mi Unlock" "$OUT" "Mi Unlock"

new_sandbox
export RF_STUB_FASTBOOT_DEVICES='FBSERIAL\tfastboot\n'
export RF_STUB_GETVAR_ALL='(bootloader) product: cheetah\n(bootloader) unlocked: yes\n'
run_script bash "$BIN_DIR/unlock_bootloader.sh"
assert_eq "an already-unlocked device is a no-op" "$RC" "0"
assert_contains "already-unlocked says so" "$OUT" "already reports unlocked"

new_sandbox
export RF_STUB_FASTBOOT_DEVICES='FBSERIAL\tfastboot\n'
export RF_STUB_GETVAR_ALL='(bootloader) product: cheetah\n(bootloader) unlocked: no\n'
run_script bash "$BIN_DIR/unlock_bootloader.sh"
assert_eq "an unconfirmed unlock aborts" "$RC" "1"
assert_not_contains "an unconfirmed unlock issues no unlock command" "$(cat "$RF_STUB_LOG")" "unlock"

new_sandbox
export RF_STUB_FASTBOOT_DEVICES='FBSERIAL\tfastboot\n'
export RF_STUB_GETVAR_ALL='(bootloader) product: cheetah\n(bootloader) unlocked: no\n'
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/unlock_bootloader.sh"
assert_eq "a confirmed unlock succeeds" "$RC" "0"
assert_contains "the unlock targets the resolved serial" "$(cat "$RF_STUB_LOG")" "fastboot -s FBSERIAL flashing unlock"
assert_not_contains "no invented fastboot wait command" "$(cat "$RF_STUB_LOG")" "wait-for-device"

new_sandbox
export RF_STUB_FASTBOOT_DEVICES='FBSERIAL\tfastboot\n'
export RF_STUB_GETVAR_ALL='(bootloader) product: cheetah\n(bootloader) unlocked: no\n'
export ROOTFORGE_ASSUME_YES=1 RF_STUB_FASTBOOT_RC=1
run_script bash "$BIN_DIR/unlock_bootloader.sh"
assert_eq "an unlock the device rejects exits non-zero" "$RC" "1"

# The shared profile is optional: with the rootforge CLI broken the script
# falls back to its own direct query, and still refuses to guess.
new_sandbox
export RF_STUB_FASTBOOT_DEVICES='FBSERIAL\tfastboot\n'
export RF_STUB_GETVAR_ALL='(bootloader) product: cheetah\n(bootloader) unlocked: no\n'
mkdir -p "$SANDBOX/fakebin"
printf '#!/bin/sh\nexit 127\n' > "$SANDBOX/fakebin/rootforge"
chmod +x "$SANDBOX/fakebin/rootforge"
export PATH="$SANDBOX/fakebin:$PATH"
run_script bash "$BIN_DIR/unlock_bootloader.sh"
assert_contains "falls back to a direct query when device info is unavailable" "$OUT" "querying fastboot directly"
assert_eq "the fallback path still requires confirmation" "$RC" "1"

# Nothing is known about the device: an unlock (which wipes data) must not
# proceed to the prompt.
new_sandbox
export RF_STUB_FASTBOOT_DEVICES='FBSERIAL\tfastboot\n'
export RF_STUB_GETVAR_ALL=''
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/unlock_bootloader.sh"
assert_eq "an unidentifiable device is blocked" "$RC" "3"
assert_not_contains "an unidentifiable device is never unlocked" "$(cat "$RF_STUB_LOG")" "unlock"

new_sandbox
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/unlock_bootloader.sh"
assert_eq "no device is blocked" "$RC" "3"

new_sandbox
export RF_STUB_FASTBOOT_DEVICES='FB1\tfastboot\nFB2\tfastboot\n'
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/unlock_bootloader.sh"
assert_eq "two devices and no serial is blocked" "$RC" "3"
assert_not_contains "an ambiguous unlock issues no command" "$(cat "$RF_STUB_LOG")" "unlock"
drop_sandbox

section "kernelsu_patch_boot.sh --flash"

new_sandbox
mkdir -p "$ROOTFORGE_HOME/kernelsu-work"
# Regression: `ls -t ... | head -1` under pipefail aborted before die() could
# say anything, leaving a bare non-zero exit with no message.
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --flash
assert_eq "no patched image -> exit 1" "$RC" "1"
assert_contains "no patched image -> explains why" "$OUT" "No patched boot image found"

new_sandbox
mkdir -p "$ROOTFORGE_HOME/kernelsu-work"
make_boot_img "$ROOTFORGE_HOME/kernelsu-work/boot-ksu-patched-old-20240101_000000.img"
sleep 0.01
make_boot_img "$ROOTFORGE_HOME/kernelsu-work/boot-ksu-patched-new-20240102_000000.img"
fb_device FB1 a
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --flash
assert_eq "unconfirmed --flash aborts" "$RC" "1"
assert_not_contains "unconfirmed --flash writes nothing" "$(cat "$RF_STUB_LOG")" " flash "

new_sandbox
mkdir -p "$ROOTFORGE_HOME/kernelsu-work"
make_boot_img "$ROOTFORGE_HOME/kernelsu-work/boot-ksu-patched-old-20240101_000000.img"
sleep 0.01
make_boot_img "$ROOTFORGE_HOME/kernelsu-work/boot-ksu-patched-new-20240102_000000.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --flash
assert_eq "confirmed --flash succeeds through the checked flasher" "$RC" "0"
assert_contains "confirmed --flash picks the newest image" "$(cat "$RF_STUB_LOG")" "boot-ksu-patched-new"
assert_contains "--flash writes with an explicit serial and slot" "$(cat "$RF_STUB_LOG")" "fastboot -s FB1 --slot a flash boot"

# --flash must not bypass the device checks the flasher enforces.
new_sandbox
mkdir -p "$ROOTFORGE_HOME/kernelsu-work"
make_boot_img "$ROOTFORGE_HOME/kernelsu-work/boot-ksu-patched-x-20240101_000000.img"
fb_device FB1 a
export RF_STUB_GETVAR_ALL="${RF_STUB_GETVAR_ALL//unlocked: yes/unlocked: no}"
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --flash
assert_eq "--flash on a locked bootloader is blocked" "$RC" "3"
assert_not_contains "--flash on a locked bootloader writes nothing" "$(cat "$RF_STUB_LOG")" " flash "

new_sandbox
mkdir -p "$ROOTFORGE_HOME/kernelsu-work"
make_boot_img "$ROOTFORGE_HOME/kernelsu-work/boot-ksu-patched-x-20240101_000000.img"
fb_device FB1 a
export RF_STUB_FASTBOOT_DEVICES='FB1\tfastboot\nFB2\tfastboot\n'
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --flash
assert_eq "--flash with two devices and no serial is blocked" "$RC" "3"
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --flash --serial FB1
assert_eq "--flash --serial picks the named device" "$RC" "0"
assert_contains "--flash --serial writes to that device" "$(cat "$RF_STUB_LOG")" "fastboot -s FB1 --slot a flash boot"

new_sandbox
mkdir -p "$ROOTFORGE_HOME/kernelsu-work"
make_boot_img "$ROOTFORGE_HOME/kernelsu-work/boot-ksu-patched-x-20240101_000000.img"
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --flash --serial 'x; rm -rf /'
assert_eq "a malformed --serial is rejected" "$RC" "1"
assert_contains "the --serial rejection explains itself" "$OUT" "does not look like a device serial"

new_sandbox
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --stock-boot
assert_eq "missing option value rejected" "$RC" "1"
assert_contains "missing option value explained" "$OUT" "requires a value"
drop_sandbox

section "fleet_orchestrate.sh"

new_sandbox
# Regression: "${OPERATION#*:}" returns the whole string with no colon, so
# this used to pass the literal word "flash" as the image path.
export RF_STUB_ADB_DEVICES='AAA\tdevice\n'
run_script bash "$BIN_DIR/fleet_orchestrate.sh" flash --allow-destructive
assert_eq "flash without an image is rejected" "$RC" "1"
assert_contains "flash without an image explains itself" "$OUT" "flash needs an image path"

new_sandbox
export RF_STUB_ADB_DEVICES='AAA\tdevice\n'
run_script bash "$BIN_DIR/fleet_orchestrate.sh" module-install --allow-destructive
assert_eq "module-install without an id is rejected" "$RC" "1"

new_sandbox
export RF_STUB_ADB_DEVICES='AAA\tdevice\n'
run_script bash "$BIN_DIR/fleet_orchestrate.sh" unlock
assert_eq "destructive op needs --allow-destructive" "$RC" "1"

new_sandbox
export RF_STUB_ADB_DEVICES=""
export RF_STUB_FASTBOOT_DEVICES=""
run_script bash "$BIN_DIR/fleet_orchestrate.sh" root-detect
assert_eq "no devices -> exit 1" "$RC" "1"
assert_contains "no devices -> says so" "$OUT" "No devices found"
drop_sandbox

section "build_matrix.sh — matrix file handling"

new_sandbox
printf '# comment\n\n25.2.9519653\t31\n' > "$SANDBOX/matrix.tsv"
mkdir -p "$SANDBOX/project"
# docker is absent in the sandbox PATH, so this stops at the docker check —
# which is enough to prove the file was read and parsed rather than ignored.
run_script bash "$BIN_DIR/build_matrix.sh" --project-dir "$SANDBOX/project" --build-cmd "true" --matrix-file "$SANDBOX/nonexistent.tsv"
assert_eq "missing matrix file is an error" "$RC" "1"
assert_contains "missing matrix file explains itself" "$OUT" "Matrix file not found"

new_sandbox
mkdir -p "$SANDBOX/project"
run_script bash "$BIN_DIR/build_matrix.sh" --project-dir "$SANDBOX/project" --build-cmd
assert_eq "--build-cmd without a value is rejected" "$RC" "1"

new_sandbox
mkdir -p "$SANDBOX/project"
run_script bash "$BIN_DIR/build_matrix.sh" --project-dir "$SANDBOX/project" --build-cmd true --bogus
assert_eq "unknown flag is rejected" "$RC" "1"
drop_sandbox

section "build_magisk_module.sh"

new_sandbox
mkdir -p "$ROOTFORGE_HOME/modules/testmod"
printf 'id=testmod\nname=T\nversion=1\nversionCode=1\nauthor=a\ndescription=d\n' \
  > "$ROOTFORGE_HOME/modules/testmod/module.prop"
run_script bash "$BIN_DIR/build_magisk_module.sh" testmod --framework bogus
assert_eq "unknown framework rejected up front" "$RC" "1"

new_sandbox
mkdir -p "$ROOTFORGE_HOME/modules/testmod"
printf 'id=testmod\n' > "$ROOTFORGE_HOME/modules/testmod/module.prop"
run_script bash "$BIN_DIR/build_magisk_module.sh" testmod --install --serial SERIAL7
assert_contains "--serial reaches adb" "$(cat "$RF_STUB_LOG")" "-s SERIAL7"

new_sandbox
mkdir -p "$ROOTFORGE_HOME/modules/testmod"
printf 'id=testmod\n' > "$ROOTFORGE_HOME/modules/testmod/module.prop"
export RF_STUB_ADB_SHELL_RC=1
run_script bash "$BIN_DIR/build_magisk_module.sh" testmod --install
assert_eq "failed module install exits non-zero" "$RC" "1"
drop_sandbox

section "common.sh — secret handling"

new_sandbox
. "$LIB_DIR/rootforge/sh/common.sh"

# A key containing a single quote used to terminate the '...' wrapper early:
# the whole sourced file became a syntax error and NO keys loaded. A crafted
# key ran as shell commands in every new shell.
SQ="'"   # a literal single quote, without the unreadable '"'"' dance
for hostile in "simple" "abc${SQ}def" "sp ace" 'has$dollar' 'back\slash' '"dq"'; do
  printf 'export K=%s\n' "$(rf_shell_quote "$hostile")" > "$SANDBOX/k.env"
  got="$(bash -c ". '$SANDBOX/k.env'; printf %s \"\$K\"")"
  assert_eq "rf_shell_quote round-trips [$hostile]" "$got" "$hostile"
done

# The specific injection: a key that closes the quote and appends a command.
rm -f "$SANDBOX/PWNED"
INJECT="x${SQ};touch $SANDBOX/PWNED;${SQ}"
printf 'export K=%s\n' "$(rf_shell_quote "$INJECT")" > "$SANDBOX/k.env"
bash -c ". '$SANDBOX/k.env'" 2>/dev/null || true
if [ -f "$SANDBOX/PWNED" ]; then
  fail "rf_shell_quote blocks command injection" "the payload executed"
else
  pass "rf_shell_quote blocks command injection"
fi

# rf_write_private must never leave the file world-readable, including when
# it is replacing an existing 0644 file.
printf 'secret\n' | rf_write_private "$SANDBOX/priv.env"
assert_eq "rf_write_private creates 0600" "$(stat -c %a "$SANDBOX/priv.env")" "600"
: > "$SANDBOX/pre.env"; chmod 644 "$SANDBOX/pre.env"
printf 'secret\n' | rf_write_private "$SANDBOX/pre.env"
assert_eq "rf_write_private tightens an existing 0644 file" "$(stat -c %a "$SANDBOX/pre.env")" "600"
drop_sandbox

section "setup_ai_tools.sh — a key must not reach the log"

new_sandbox
# Regression: gemini authenticated with ?key= in the URL. curl's own error
# text quotes the URL it was trying to reach, that text is appended to
# $LOG_FILE, and the key landed there in plaintext — in a mode-644 file,
# while this same script chmod 600s the key file and chmod 700s its
# directory. A curl that fails the way a real one does makes it visible.
mkdir -p "$SANDBOX/curlbin"
cat > "$SANDBOX/curlbin/curl" <<'EOS'
#!/usr/bin/env bash
CFG="$(cat)"
URL="$(printf '%s' "$CFG" | sed -n 's/^url = "\(.*\)"$/\1/p')"
echo "curl: (6) Could not resolve host for $URL" >&2
echo "000"
EOS
chmod +x "$SANDBOX/curlbin/curl"
export PATH="$SANDBOX/curlbin:$STUB_DIR:$ORIGINAL_PATH"

run_script bash "$BIN_DIR/setup_ai_tools.sh" add gemini --key "AIzaSECRETKEY123"
LOGF="$(find "$ROOTFORGE_HOME/logs" -name 'ai_tools_*.log' | head -1)"
if [ -z "$LOGF" ]; then
  fail "a log was written" "no ai_tools log found"
else
  pass "a log was written"
  assert_not_contains "the gemini key never reaches the log" "$(cat "$LOGF")" "AIzaSECRETKEY123"
  assert_eq "the log is not world-readable" "$(stat -c %a "$LOGF")" "600"
fi
# The key must still be stored and sourceable — the fix moves where it
# travels, not whether it works.
KEYFILE="$HOME/.rootforge/ai-keys.env"
assert_eq "the key is still stored" \
  "$(bash -c ". '$KEYFILE'; printf %s \"\$GEMINI_API_KEY\"")" "AIzaSECRETKEY123"

# Every other provider authenticates with a header already; check one, so a
# future provider added with a query parameter shows up here.
run_script bash "$BIN_DIR/setup_ai_tools.sh" add openai --key "sk-SECRETOPENAI"
for f in "$ROOTFORGE_HOME"/logs/ai_tools_*.log; do
  assert_not_contains "no key reaches any ai_tools log" "$(cat "$f")" "sk-SECRETOPENAI"
done
drop_sandbox

section "setup_ai_tools.sh — key storage"

new_sandbox
# --no-verify keeps this offline; the point is what lands on disk.
run_script bash "$BIN_DIR/setup_ai_tools.sh" add openai --key "sk-plain123" --no-verify
assert_eq "add stores a key" "$RC" "0"
KEYFILE="$HOME/.rootforge/ai-keys.env"
assert_eq "key file is 0600" "$(stat -c %a "$KEYFILE")" "600"
assert_contains "key file records the provider" "$(cat "$KEYFILE")" "# provider:openai:OPENAI_API_KEY"
GOT="$(bash -c ". '$KEYFILE'; printf %s \"\$OPENAI_API_KEY\"")"
assert_eq "key sources back correctly" "$GOT" "sk-plain123"

# The regression: a quote in the key used to break the file for every key.
new_sandbox
run_script bash "$BIN_DIR/setup_ai_tools.sh" add openai --key "sk-plain" --no-verify
SQ="'"
run_script bash "$BIN_DIR/setup_ai_tools.sh" add anthropic --key "ab${SQ}cd" --no-verify
KEYFILE="$HOME/.rootforge/ai-keys.env"
if bash -n "$KEYFILE" 2>/dev/null; then
  pass "key file stays syntactically valid with a quote in a key"
else
  fail "key file stays syntactically valid with a quote in a key" "bash -n rejected it"
fi
BOTH="$(bash -c ". '$KEYFILE'; printf '%s|%s' \"\$OPENAI_API_KEY\" \"\$ANTHROPIC_API_KEY\"")"
assert_eq "a quoted key does not clobber the others" "$BOTH" "sk-plain|ab${SQ}cd"

# Keys must accumulate, and remove must only touch its own provider.
new_sandbox
run_script bash "$BIN_DIR/setup_ai_tools.sh" add openai --key "k-openai" --no-verify
run_script bash "$BIN_DIR/setup_ai_tools.sh" add groq --key "k-groq" --no-verify
run_script bash "$BIN_DIR/setup_ai_tools.sh" remove openai
assert_eq "remove succeeds" "$RC" "0"
KEYFILE="$HOME/.rootforge/ai-keys.env"
LEFT="$(bash -c ". '$KEYFILE'; printf '%s|%s' \"\${OPENAI_API_KEY:-}\" \"\${GROQ_API_KEY:-}\"")"
assert_eq "remove drops only its own provider" "$LEFT" "|k-groq"
assert_eq "key file still 0600 after remove" "$(stat -c %a "$KEYFILE")" "600"

# Regression: `remove <unknown>` died inside a pipefail'd command
# substitution before its own error message could print.
new_sandbox
run_script bash "$BIN_DIR/setup_ai_tools.sh" add groq --key "k-groq" --no-verify
run_script bash "$BIN_DIR/setup_ai_tools.sh" remove doesnotexist
assert_eq "remove of an unknown provider exits 1" "$RC" "1"
assert_contains "remove of an unknown provider explains itself" "$OUT" "No stored key found"

new_sandbox
run_script bash "$BIN_DIR/setup_ai_tools.sh" add openai --key
assert_eq "--key without a value is rejected" "$RC" "1"
assert_contains "--key without a value explains itself" "$OUT" "needs a value"
drop_sandbox

section "setup_rooted_avd.sh — profile handling"

new_sandbox
mkdir -p "$ROOTFORGE_HOME/avd-profiles"
# Regression: a profile missing any key aborted list/boot under pipefail
# before a single line was printed.
printf 'NAME=partial\n' > "$ROOTFORGE_HOME/avd-profiles/partial.conf"
run_script bash "$BIN_DIR/setup_rooted_avd.sh" list
assert_eq "list survives a profile missing keys" "$RC" "0"
assert_contains "list still names the profile" "$OUT" "partial"

new_sandbox
mkdir -p "$ROOTFORGE_HOME/avd-profiles"
printf 'NAME=full\nMODE=rooted\nAPI=34\nDEVICE=pixel_6\nABI=x86_64\nTAG=google_apis\n' \
  > "$ROOTFORGE_HOME/avd-profiles/full.conf"
run_script bash "$BIN_DIR/setup_rooted_avd.sh" list
assert_contains "list reads a complete profile" "$OUT" "mode=rooted"
assert_contains "list reads the api level" "$OUT" "api=34"
# Regression: a trailing `[[ $found -eq 0 ]] && echo` made the function
# return the condition's status, so a successful listing exited 1 and an
# empty one exited 0 — exactly backwards.
assert_eq "list exits 0 when it found profiles" "$RC" "0"

new_sandbox
mkdir -p "$ROOTFORGE_HOME/avd-profiles"
run_script bash "$BIN_DIR/setup_rooted_avd.sh" list
assert_eq "list exits 0 when there are no profiles" "$RC" "0"
assert_contains "list says so when there are none" "$OUT" "(none)"

new_sandbox
run_script bash "$BIN_DIR/setup_rooted_avd.sh" create --name
assert_eq "--name without a value is rejected" "$RC" "1"
assert_contains "--name without a value explains itself" "$OUT" "requires a value"

new_sandbox
run_script bash "$BIN_DIR/setup_rooted_avd.sh" create --name test --mode unrooted --api notanumber
assert_eq "non-numeric --api is rejected" "$RC" "1"
assert_contains "non-numeric --api names the option" "$OUT" "--api must be a number"

new_sandbox
run_script bash "$BIN_DIR/setup_rooted_avd.sh" create --name test --mode unrooted --abi mips
assert_eq "unknown --abi is rejected" "$RC" "1"
assert_contains "unknown --abi names the option" "$OUT" "--abi must be"

new_sandbox
run_script bash "$BIN_DIR/setup_rooted_avd.sh" create --name test --mode sideways
assert_eq "unknown --mode is rejected" "$RC" "1"
assert_contains "unknown --mode names the option" "$OUT" "--mode must be"

new_sandbox
run_script bash "$BIN_DIR/setup_rooted_avd.sh" create --name test --mode rooted --tag google_apis_playstore
assert_eq "rooted + Play image is refused" "$RC" "1"
assert_contains "rooted + Play image explains why" "$OUT" "Play images are signed"
drop_sandbox

section "flash_pi_image.sh"

new_sandbox
head -c 64 /dev/zero > "$SANDBOX/pi.img"
mkdir -p "$HOME/.ssh"; printf 'ssh-ed25519 AAAA test\n' > "$HOME/.ssh/id_ed25519.pub"
# Regression: an unvalidated role selected no branch in the case at the end,
# so a typo wrote the image and then silently did nothing role-related.
run_script bash "$BIN_DIR/flash_pi_image.sh" "$SANDBOX/pi.img" /dev/null --role hoomlab
assert_eq "unknown --role is rejected" "$RC" "1"
assert_contains "unknown --role explains itself" "$OUT" "Unknown role"

new_sandbox
head -c 64 /dev/zero > "$SANDBOX/pi.img"
run_script bash "$BIN_DIR/flash_pi_image.sh" "$SANDBOX/pi.img" /dev/null --role
assert_eq "--role without a value is rejected" "$RC" "1"
assert_contains "--role without a value says so" "$OUT" "needs a value"

new_sandbox
head -c 64 /dev/zero > "$SANDBOX/pi.img"
run_script bash "$BIN_DIR/flash_pi_image.sh" "$SANDBOX/pi.img" /dev/null --rol bare
assert_eq "unknown flag is rejected" "$RC" "1"
assert_contains "unknown flag explains itself" "$OUT" "Unknown argument"

new_sandbox
head -c 64 /dev/zero > "$SANDBOX/pi.img"
run_script bash "$BIN_DIR/flash_pi_image.sh" "$SANDBOX/pi.img" /dev/null --hostname "bad_host!"
assert_eq "invalid hostname is rejected" "$RC" "1"
assert_contains "invalid hostname says so" "$OUT" "Invalid hostname"
drop_sandbox

section "join_headscale.sh"

new_sandbox
run_script bash "$BIN_DIR/join_headscale.sh" https://hs.example.com --advertise-exitnode
assert_eq "mistyped flag is rejected, not ignored" "$RC" "1"
assert_contains "mistyped flag explains itself" "$OUT" "Unknown argument"

new_sandbox
run_script bash "$BIN_DIR/join_headscale.sh" https://hs.example.com --hostname
assert_eq "--hostname without a value is rejected" "$RC" "1"
assert_contains "--hostname without a value says so" "$OUT" "needs a value"

new_sandbox
run_script bash "$BIN_DIR/join_headscale.sh" "not-a-url"
assert_eq "non-URL login server is rejected" "$RC" "1"
assert_contains "non-URL login server explains itself" "$OUT" "http(s) URL"
drop_sandbox

section "setup_vpn.sh — peer address allocation"

new_sandbox
WGP="$ROOTFORGE_HOME/keys/wireguard/peers"
# Regression: addresses were `10.66.66.$((RANDOM % 200 + 10))` with no check
# against peers already issued — a birthday collision (~63% by the 20th
# peer). A duplicate AllowedIPs doesn't fail loudly, it silently breaks
# routing for one of them.
#
# This block used to re-implement the allocator here and assert on files it
# had written itself, which tested the copy in this file rather than the one
# that ships. Drive the real script instead: 25 real peer-qr runs.
mkdir -p "$SANDBOX/wgbin"
cat > "$SANDBOX/wgbin/wg" <<'EOS'
#!/usr/bin/env bash
case "${1:-}" in
  genkey) printf 'PRIVKEY%s\n' "$RANDOM" ;;
  pubkey) cat >/dev/null; printf 'PUBKEY\n' ;;
esac
EOS
cat > "$SANDBOX/wgbin/qrencode" <<'EOS'
#!/usr/bin/env bash
cat >/dev/null
EOS
chmod +x "$SANDBOX/wgbin/wg" "$SANDBOX/wgbin/qrencode"
export PATH="$SANDBOX/wgbin:$STUB_DIR:$ORIGINAL_PATH"
export ROOTFORGE_WG_CONF="$SANDBOX/wg0.conf"
export ROOTFORGE_WG_ENDPOINT="vpn.example:51820"

run_script bash "$BIN_DIR/setup_vpn.sh" init
assert_eq "init creates a keypair" "$RC" "0"
for i in $(seq 1 25); do
  run_script bash "$BIN_DIR/setup_vpn.sh" peer-qr "peer$i"
done
assert_eq "the 25th peer still succeeds" "$RC" "0"
TOTAL="$(grep -rh '^Address = ' "$WGP" | wc -l | tr -d ' ')"
UNIQUE="$(grep -rh '^Address = ' "$WGP" | sort -u | wc -l | tr -d ' ')"
assert_eq "25 peers get 25 addresses" "$TOTAL" "25"
assert_eq "25 peers get 25 distinct addresses" "$UNIQUE" "$TOTAL"
assert_eq "allocation starts at .10" \
  "$(grep '^Address = ' "$WGP/peer1/peer.conf")" "Address = 10.66.66.10/32"
assert_eq "allocation is sequential, not random" \
  "$(grep '^Address = ' "$WGP/peer25/peer.conf")" "Address = 10.66.66.34/32"
assert_eq "the interface config is never written to /etc" \
  "$([ -e /etc/wireguard/wg0.conf ] && echo leaked || echo clean)" "clean"

# Regression: `read -r -p` reads stdin, so an unattended run (stdin closed)
# ended the script at set -e right after generating the peer's keypair and
# assigning it an address — no message, half a peer left behind.
new_sandbox
mkdir -p "$SANDBOX/wgbin"
cat > "$SANDBOX/wgbin/wg" <<'EOS'
#!/usr/bin/env bash
case "${1:-}" in
  genkey) printf 'PRIVKEY\n' ;;
  pubkey) cat >/dev/null; printf 'PUBKEY\n' ;;
esac
EOS
cat > "$SANDBOX/wgbin/qrencode" <<'EOS'
#!/usr/bin/env bash
cat >/dev/null
EOS
chmod +x "$SANDBOX/wgbin/wg" "$SANDBOX/wgbin/qrencode"
export PATH="$SANDBOX/wgbin:$STUB_DIR:$ORIGINAL_PATH"
export ROOTFORGE_WG_CONF="$SANDBOX/wg0.conf"
run_script bash "$BIN_DIR/setup_vpn.sh" init
run_script bash "$BIN_DIR/setup_vpn.sh" peer-qr nokeyboard
assert_eq "no terminal and no endpoint is an error" "$RC" "1"
assert_contains "the missing endpoint names the way to supply it" "$OUT" "ROOTFORGE_WG_ENDPOINT"

new_sandbox
run_script bash "$BIN_DIR/setup_vpn.sh" peer-qr "../../escape"
assert_eq "a peer name that climbs out of the dir is rejected" "$RC" "1"
assert_contains "path-traversal peer name explains itself" "$OUT" "Peer name must be"
drop_sandbox

section "setup_intercept_proxy.sh"

new_sandbox
run_script bash "$BIN_DIR/setup_intercept_proxy.sh" start 99999
assert_eq "out-of-range port is rejected" "$RC" "1"
assert_contains "out-of-range port explains itself" "$OUT" "Invalid port"

new_sandbox
run_script bash "$BIN_DIR/setup_intercept_proxy.sh" start notaport
assert_eq "non-numeric port is rejected" "$RC" "1"

new_sandbox
run_script bash "$BIN_DIR/setup_intercept_proxy.sh" bogus-subcommand
assert_eq "unknown subcommand is rejected" "$RC" "1"
assert_contains "unknown subcommand prints usage" "$OUT" "Usage:"

new_sandbox
run_script bash "$BIN_DIR/setup_intercept_proxy.sh" trust-cert
assert_eq "trust-cert without a generated CA is rejected" "$RC" "1"
assert_contains "trust-cert says where the CA should be" "$OUT" "mitmproxy-ca-cert.pem"
drop_sandbox

section "harden_kernel.sh — GRUB lockdown edit"

# These drive the real script against a fixture via ROOTFORGE_GRUB_DEFAULTS.
# An earlier draft re-implemented the sed inside this file and asserted on
# that — which passed against the buggy original, because it was testing the
# test's own copy rather than the shipped code.
new_sandbox
printf 'GRUB_CMDLINE_LINUX_DEFAULT="quiet"\nGRUB_CMDLINE_LINUX=""\n' > "$SANDBOX/grub"
export ROOTFORGE_GRUB_DEFAULTS="$SANDBOX/grub"
# Regression: the old sed appended unconditionally, so every run added another
# copy of lockdown=integrity to the kernel command line.
run_script bash "$BIN_DIR/harden_kernel.sh" --lockdown --dry-run
run_script bash "$BIN_DIR/harden_kernel.sh" --lockdown
run_script bash "$BIN_DIR/harden_kernel.sh" --lockdown
run_script bash "$BIN_DIR/harden_kernel.sh" --lockdown
# Asserting on the file rather than on $RC: `sysctl --system` legitimately
# fails inside a container (a knob this kernel lacks), and the script now
# reports that with a non-zero exit *after* completing every requested step.
# The lockdown edit landing is the outcome under test.
OCCURRENCES="$(grep -o 'lockdown=integrity' "$SANDBOX/grub" | wc -l | tr -d ' ')"
assert_eq "three --lockdown runs leave exactly one lockdown=integrity" "$OCCURRENCES" "1"

# Regression: a grub file carrying only GRUB_CMDLINE_LINUX_DEFAULT matched
# nothing, so the script changed nothing and still reported that it would
# take effect on next reboot.
new_sandbox
printf 'GRUB_CMDLINE_LINUX_DEFAULT="quiet"\nGRUB_TIMEOUT=5\n' > "$SANDBOX/grub"
export ROOTFORGE_GRUB_DEFAULTS="$SANDBOX/grub"
run_script bash "$BIN_DIR/harden_kernel.sh" --lockdown
assert_contains "no GRUB_CMDLINE_LINUX line still gets the option" \
  "$(cat "$SANDBOX/grub")" 'GRUB_CMDLINE_LINUX="lockdown=integrity"'

new_sandbox
printf 'GRUB_CMDLINE_LINUX="console=tty0 quiet"\n' > "$SANDBOX/grub"
export ROOTFORGE_GRUB_DEFAULTS="$SANDBOX/grub"
run_script bash "$BIN_DIR/harden_kernel.sh" --lockdown
assert_contains "an existing cmdline is preserved, not replaced" \
  "$(cat "$SANDBOX/grub")" "console=tty0 quiet lockdown=integrity"

new_sandbox
export ROOTFORGE_GRUB_DEFAULTS="$SANDBOX/does-not-exist"
run_script bash "$BIN_DIR/harden_kernel.sh" --lockdown
assert_eq "a missing grub file is an error, not a silent no-op" "$RC" "1"
assert_contains "a missing grub file says so" "$OUT" "not found"

new_sandbox
run_script bash "$BIN_DIR/harden_kernel.sh" --lockdow
assert_eq "a mistyped --lockdown is rejected" "$RC" "1"
assert_contains "a mistyped --lockdown says so" "$OUT" "Unknown argument"

new_sandbox
run_script bash "$BIN_DIR/harden_kernel.sh" --dry-run
assert_eq "--dry-run succeeds" "$RC" "0"
assert_contains "--dry-run shows what it would write" "$OUT" "kernel.yama.ptrace_scope"
# The label used to say "without touching anything" and never checked it.
if [ -e "$ROOTFORGE_SYSCTL_FILE" ]; then
  fail "--dry-run writes no sysctl drop-in" "$ROOTFORGE_SYSCTL_FILE was created"
else
  pass "--dry-run writes no sysctl drop-in"
fi

# And the non-dry path must write to the seam, never to /etc.
new_sandbox
printf 'GRUB_CMDLINE_LINUX=""\n' > "$SANDBOX/grub"
export ROOTFORGE_GRUB_DEFAULTS="$SANDBOX/grub"
run_script bash "$BIN_DIR/harden_kernel.sh"
if [ -f "$ROOTFORGE_SYSCTL_FILE" ]; then
  pass "a real run writes the drop-in where it was told to"
else
  fail "a real run writes the drop-in where it was told to" "$ROOTFORGE_SYSCTL_FILE missing"
fi
assert_contains "the drop-in has the expected content" "$(cat "$ROOTFORGE_SYSCTL_FILE")" "kernel.yama.ptrace_scope"
assert_contains "a redirected drop-in is not applied with sysctl --system" "$OUT" "did not run 'sysctl --system'"

# Regression: `sysctl --system` exits non-zero if any key anywhere under
# /etc/sysctl.d cannot be set, which under set -e aborted the script before
# the lockdown step the caller explicitly asked for. The step must still run.
new_sandbox
printf 'GRUB_CMDLINE_LINUX=""\n' > "$SANDBOX/grub"
export ROOTFORGE_GRUB_DEFAULTS="$SANDBOX/grub"
run_script bash "$BIN_DIR/harden_kernel.sh" --lockdown
assert_contains "a failing sysctl key does not skip the lockdown step" \
  "$(cat "$SANDBOX/grub")" "lockdown=integrity"
drop_sandbox

section "harden_system.sh — USBGuard policy"

new_sandbox
run_script bash "$BIN_DIR/harden_system.sh" --usbguard-lern
assert_eq "a mistyped --usbguard-learn is rejected" "$RC" "1"
assert_contains "a mistyped --usbguard-learn says so" "$OUT" "Unknown argument"

# The empty-policy guard: USBGuard's default posture is block, so writing a
# policy with no allow rules denies every USB device including the keyboard.
#
# This section used to build a policy file itself and grep it — which tested
# grep, not harden_system.sh, and would have passed whether or not the guard
# existed. It now drives the real script through --dry-run, which reaches the
# guard without installing packages or enabling services on the machine
# running the tests.
plant_priv_stubs() {
  mkdir -p "$SANDBOX/priv"
  cat > "$SANDBOX/priv/sudo" <<'EOS'
#!/usr/bin/env bash
printf 'sudo %s
' "$*" >> "$RF_STUB_LOG"
exec "$@"
EOS
  cat > "$SANDBOX/priv/usbguard" <<'EOS'
#!/usr/bin/env bash
printf '%b' "${RF_STUB_USB_POLICY:-}"
EOS
  chmod +x "$SANDBOX/priv/sudo" "$SANDBOX/priv/usbguard"
  export PATH="$SANDBOX/priv:$STUB_DIR:$ORIGINAL_PATH"
}

new_sandbox
plant_priv_stubs
export ROOTFORGE_ASSUME_YES=1 RF_STUB_USB_POLICY=""
export ROOTFORGE_USBGUARD_RULES="$SANDBOX/rules.conf"
run_script bash "$BIN_DIR/harden_system.sh" --usbguard-learn --dry-run
assert_eq "an empty generated policy aborts" "$RC" "1"
assert_contains "the abort explains what it would have done" "$OUT" "produced no allow rules"
if [ -e "$SANDBOX/rules.conf" ]; then
  fail "an empty policy is never written" "rules.conf was created"
else
  pass "an empty policy is never written"
fi

new_sandbox
plant_priv_stubs
export ROOTFORGE_ASSUME_YES=1
export RF_STUB_USB_POLICY='allow id 1d6b:0002 name "root hub"\nallow id 046d:c52b name "keyboard"\n'
export ROOTFORGE_USBGUARD_RULES="$SANDBOX/rules.conf"
run_script bash "$BIN_DIR/harden_system.sh" --usbguard-learn --dry-run
assert_contains "a populated policy is counted" "$OUT" "Generated 2 allow rule(s)"
assert_contains "the devices that would be allowed are shown before writing" "$OUT" "046d:c52b"

# --dry-run must not be a partial run: nothing installed, nothing enabled.
assert_not_contains "--dry-run installs no packages" "$(cat "$RF_STUB_LOG")" "apt-get install"
assert_not_contains "--dry-run enables no services" "$(cat "$RF_STUB_LOG")" "systemctl enable"
assert_contains "--dry-run says what it would have run instead" "$OUT" "would run: apt-get install"

new_sandbox
plant_priv_stubs
run_script bash "$BIN_DIR/harden_system.sh" --dry-run --bogus
assert_eq "an unknown flag is still rejected alongside --dry-run" "$RC" "1"
assert_eq "a rejected flag runs nothing privileged" "$(wc -l < "$RF_STUB_LOG")" "0"
drop_sandbox

section "rpi_fleet_tools.sh"

new_sandbox
# The nmap output shapes that matter: a host WITH a PTR record is reported as
# "for <name> (<ip>)", one WITHOUT as "for <ip>". Matching only the
# parenthesised form dropped every Pi lacking reverse DNS — common on a home
# LAN — so `run` skipped those hosts forever with nothing to say so.
cat > "$SANDBOX/nmap.txt" <<'NMAPOUT'
Nmap scan report for raspberrypi.local (192.168.1.5)
Host is up (0.0021s latency).
MAC Address: DC:A6:32:11:22:33 (Raspberry Pi Trading)
Nmap scan report for 192.168.1.9
Host is up (0.0034s latency).
MAC Address: B8:27:EB:44:55:66 (Raspberry Pi Foundation)
Nmap scan report for pi-node3.lan (192.168.1.14)
Host is up (0.0012s latency).
MAC Address: E4:5F:01:77:88:99 (Raspberry Pi Trading)
NMAPOUT

# Drive the real scan path with recorded nmap output rather than copying the
# extraction expression into this file — a test that re-implements what it
# checks passes whether or not the shipped code is right.
export ROOTFORGE_NMAP_OUTPUT="$SANDBOX/nmap.txt"
run_script bash "$BIN_DIR/rpi_fleet_tools.sh" scan
FLEET="$ROOTFORGE_HOME/devices/pi-fleet.txt"
assert_eq "scan succeeds" "$RC" "0"
assert_eq "all three Pis are found, PTR or not" "$(wc -l < "$FLEET" | tr -d ' ')" "3"
assert_contains "a Pi without reverse DNS is included" "$(cat "$FLEET")" "192.168.1.9"
assert_contains "a Pi with reverse DNS is included" "$(cat "$FLEET")" "192.168.1.5"
assert_contains "a third Pi is included" "$(cat "$FLEET")" "192.168.1.14"

new_sandbox
run_script bash "$BIN_DIR/rpi_fleet_tools.sh"
assert_eq "no subcommand is rejected" "$RC" "1"

new_sandbox
run_script bash "$BIN_DIR/rpi_fleet_tools.sh" bogus-subcommand
assert_eq "unknown subcommand is rejected" "$RC" "1"
assert_contains "unknown subcommand prints usage" "$OUT" "Usage:"

new_sandbox
# `run` with no hosts and no prior scan must fail rather than silently
# iterating over nothing.
run_script bash "$BIN_DIR/rpi_fleet_tools.sh" run "uptime"
assert_eq "run with no hosts and no scan file is rejected" "$RC" "1"

new_sandbox
# A fleet file of only blank lines used to produce `ssh pi@` per line.
mkdir -p "$ROOTFORGE_HOME/devices"
printf '\n\n   \n' > "$ROOTFORGE_HOME/devices/pi-fleet.txt"
run_script bash "$BIN_DIR/rpi_fleet_tools.sh" run "uptime"
assert_eq "an all-blank fleet file is rejected" "$RC" "1"
assert_contains "an all-blank fleet file says so" "$OUT" "No usable hosts"

new_sandbox
# Every host failing must not exit 0: ssh to a reserved-for-doc address
# fails fast under ConnectTimeout.
mkdir -p "$ROOTFORGE_HOME/devices"
printf '192.0.2.1\n' > "$ROOTFORGE_HOME/devices/pi-fleet.txt"
run_script bash "$BIN_DIR/rpi_fleet_tools.sh" run "true"
assert_eq "a run where every host fails exits non-zero" "$RC" "1"
assert_contains "a failed run names the hosts" "$OUT" "host(s) failed"
drop_sandbox

section "check_root_detection.sh — silence is not a pass"

new_sandbox
# Every probe in this script concludes "clean" from empty output. A device
# whose `pm list packages` or mountinfo query returns nothing therefore used
# to be reported as fully clean — the worst direction to fail in for a tool
# whose entire job is telling you whether your hiding config holds.
cp "$STUB_DIR/adb-quiet-probes" "$SANDBOX/adb"
chmod +x "$SANDBOX/adb"
export PATH="$SANDBOX:$ORIGINAL_PATH"
run_script bash "$BIN_DIR/check_root_detection.sh"
assert_contains "an empty package list is reported as unknown, not clean" "$OUT" \
  "'pm list packages' returned nothing"
assert_contains "unreadable mountinfo is reported as unknown, not clean" "$OUT" \
  "mountinfo came back empty"
assert_not_contains "the empty package probe no longer reports a pass" "$OUT" \
  "**PASS** — known root manager package names"
assert_not_contains "the empty mount probe no longer reports a pass" "$OUT" \
  "**PASS** — mount namespace leak"
# The probes that genuinely ran must still pass, so this isn't just blanket
# pessimism.
assert_contains "a probe that really ran still passes" "$OUT" "**PASS** — ro.build.tags"

new_sandbox
# A device that answers nothing at all must be refused outright rather than
# producing a report at all.
printf '#!/bin/sh\ncase "$1" in wait-for-device) exit 0;; *) exit 1;; esac\n' > "$SANDBOX/adb"
chmod +x "$SANDBOX/adb"
export PATH="$SANDBOX:$ORIGINAL_PATH"
run_script bash "$BIN_DIR/check_root_detection.sh"
assert_eq "an unreachable device is refused" "$RC" "1"
assert_contains "an unreachable device explains why no report is produced" "$OUT" "Cannot reach the device"
drop_sandbox

section "new_module_scaffold.sh"

new_sandbox
# The generator and the linter disagreed about what a valid module id is, so
# the scaffold could produce a module that this project's own linter fails.
# Tie them together: whatever the scaffold emits must lint clean.
run_script bash "$BIN_DIR/new_module_scaffold.sh" mymod "My Mod" magisk
assert_eq "scaffolding a magisk module succeeds" "$RC" "0"
run_script bash "$BIN_DIR/lint_module.sh" "$ROOTFORGE_HOME/modules/mymod"
assert_eq "a scaffolded magisk module passes lint_module.sh" "$RC" "0"

new_sandbox
run_script bash "$BIN_DIR/new_module_scaffold.sh" mykmod "My KMod" kernelsu
assert_eq "scaffolding a kernelsu module succeeds" "$RC" "0"
run_script bash "$BIN_DIR/lint_module.sh" "$ROOTFORGE_HOME/modules/mykmod"
assert_eq "a scaffolded kernelsu module passes lint_module.sh" "$RC" "0"

new_sandbox
# Regression: an id the linter rejects was accepted here without comment.
run_script bash "$BIN_DIR/new_module_scaffold.sh" "9bad-id!" "Bad" magisk
assert_eq "an id the linter would reject is refused up front" "$RC" "1"
assert_contains "the refusal cites the same rule the linter uses" "$OUT" "lint_module.sh enforces"

new_sandbox
# Regression: the id was used as a path component with no validation, so
# '../escaped' scaffolded the module outside modules/.
run_script bash "$BIN_DIR/new_module_scaffold.sh" "../escaped" "Escaped" magisk
assert_eq "an id that climbs out of modules/ is refused" "$RC" "1"
if [[ -d "$ROOTFORGE_HOME/escaped" ]]; then
  fail "nothing is created outside modules/" "found $ROOTFORGE_HOME/escaped"
else
  pass "nothing is created outside modules/"
fi

new_sandbox
# Regression: an unrecognized target fell through to the magisk path and then
# announced "Scaffolded magsik module", as if it had done something else.
run_script bash "$BIN_DIR/new_module_scaffold.sh" okid "Ok" magsik
assert_eq "an unknown target is refused" "$RC" "1"
assert_contains "an unknown target lists the real ones" "$OUT" "expected magisk, kernelsu, apatch, zygisk or xposed"
drop_sandbox

section "Termux variants — build flavours"

new_sandbox
BUILD="$REPO_ROOT/termux/build-rootfs.sh"
run_script bash "$BUILD" --flavor bogus
assert_eq "an unknown flavour is rejected" "$RC" "1"
assert_contains "an unknown flavour lists the real ones" "$OUT" "expected proot or chroot"

new_sandbox
run_script bash "$BUILD" --flavor
assert_eq "--flavor without a value is rejected" "$RC" "1"

new_sandbox
run_script bash "$BUILD" --bogus-flag
assert_eq "an unknown flag is rejected" "$RC" "1"

new_sandbox
run_script bash "$BUILD" --help
assert_eq "--help succeeds" "$RC" "0"
assert_contains "--help documents both flavours" "$OUT" "flavor proot|chroot"
assert_contains "--help documents the desktop layer" "$OUT" "with-x11"

# The flavours differ in which scripts they ship. That difference is the
# whole point of having two, so pin it: the chroot flavour keeps the network
# scripts (a real /dev gives it /dev/net/tun), both drop the kernel-hardening
# ones (Android's kernel ships none of what they drive, rooted or not).
assert_contains "chroot keeps the VPN scripts" \
  "$(sed -n '/FLAVOR" == "chroot"/,/^fi$/p' "$BUILD")" 'EXCLUDE_SCRIPTS="00_bootstrap_distro.sh harden_kernel.sh harden_system.sh"'
assert_contains "proot drops the VPN scripts too" \
  "$(sed -n '/FLAVOR" == "chroot"/,/^fi$/p' "$BUILD")" "setup_vpn.sh join_headscale.sh"
assert_contains "both drop harden_kernel.sh" "$(cat "$BUILD")" "harden_kernel.sh"
drop_sandbox

section "Termux variants — X11 desktop launcher"

new_sandbox
DESKTOP="$BIN_DIR/rootforge_desktop.sh"
run_script bash "$DESKTOP" --bogus
assert_eq "an unknown argument is rejected" "$RC" "1"
assert_contains "an unknown argument lists the real ones" "$OUT" "expected --start, --check, --install"

new_sandbox
export ROOTFORGE_X11_SOCKET_DIR="$SANDBOX/nope"
run_script bash "$DESKTOP" --check
assert_eq "--check reports without failing" "$RC" "0"
assert_contains "--check names the missing socket dir" "$OUT" "missing"
assert_contains "--check tells you about --shared-tmp" "$OUT" "shared-tmp"

new_sandbox
# The socket directory existing but empty is the other common failure: the
# user logged in correctly but never opened the Termux:X11 app.
mkdir -p "$SANDBOX/x11"
export ROOTFORGE_X11_SOCKET_DIR="$SANDBOX/x11"
run_script bash "$DESKTOP" --check
assert_contains "--check distinguishes an empty socket dir from a missing one" "$OUT" "directory exists but is empty"

new_sandbox
export ROOTFORGE_X11_SOCKET_DIR="$SANDBOX/nope"
run_script bash "$DESKTOP" --start
assert_eq "starting with no desktop installed fails" "$RC" "1"
assert_contains "starting with no desktop says how to get one" "$OUT" "--install"
drop_sandbox

section "Termux variants — rooted chroot launcher"

new_sandbox
CHROOT_LAUNCHER="$REPO_ROOT/termux/rootforge-chroot.sh"
run_script bash "$CHROOT_LAUNCHER" bogus-command
assert_eq "an unknown command is rejected" "$RC" "1"
assert_contains "an unknown command lists the real ones" "$OUT" "expected install, login, umount"

new_sandbox
run_script bash "$CHROOT_LAUNCHER"
assert_eq "no command exits non-zero" "$RC" "1"
assert_contains "no command prints usage" "$OUT" "Usage"

new_sandbox
# An unrooted Termux has no `su` at all, and the launcher must say so and
# point at the PRoot variant rather than failing deep inside a mount.
#
# Isolating that needs care: /bin/su exists on the test host, so leaving it on
# PATH sends the script down its other branch — and this container runs as
# root, so su there even succeeds, which would make the assertion pass for the
# wrong reason. But emptying PATH hides `bash` too. A directory holding only
# the interpreter gives a PATH with bash and without su.
mkdir -p "$SANDBOX/nosu-bin"
ln -sf "$(command -v bash)" "$SANDBOX/nosu-bin/bash"
OUT="$(cd "$SANDBOX" && PATH="$SANDBOX/nosu-bin" "$SANDBOX/nosu-bin/bash" "$CHROOT_LAUNCHER" login 2>&1 </dev/null)"; RC=$?
assert_eq "no su present is refused" "$RC" "1"
assert_contains "no su present points at the PRoot variant" "$OUT" "proot-distro login rootforge"

new_sandbox
export PATH="$SANDBOX:$ORIGINAL_PATH"
run_script bash "$CHROOT_LAUNCHER" install
assert_eq "install with no tarball is rejected" "$RC" "1"
assert_contains "install with no tarball prints usage" "$OUT" "install <rootfs.tar.xz>"

# --- install: verified, scanned, staged ---

# make_rootfs_tar <out.tar.xz> — a tiny rootfs-shaped archive.
make_rootfs_tar() {
  local out="$1" src="$SANDBOX/rootfs-src"
  mkdir -p "$src/etc" "$src/bin"
  printf 'original\n' > "$src/etc/hostname"
  printf '#!/bin/sh\n' > "$src/bin/sh"
  tar -C "$src" -cJf "$out" .
}

new_sandbox
export ROOTFORGE_CHROOT_DIR="$SANDBOX/rootfs"
make_rootfs_tar "$SANDBOX/rootfs.tar.xz"
run_script bash "$CHROOT_LAUNCHER" install "$SANDBOX/rootfs.tar.xz"
assert_eq "install without a checksum is refused" "$RC" "1"
assert_contains "the missing checksum is explained" "$OUT" "No checksum given"
assert_eq "nothing is unpacked without a checksum" "$([ -e "$ROOTFORGE_CHROOT_DIR" ] && echo present || echo absent)" "absent"

WRONG="$(printf '0%.0s' $(seq 1 64))"
run_script bash "$CHROOT_LAUNCHER" install "$SANDBOX/rootfs.tar.xz" --sha256 "$WRONG"
assert_eq "a wrong checksum is refused" "$RC" "1"
assert_contains "the mismatch shows expected and actual" "$OUT" "Checksum mismatch"
assert_eq "nothing is unpacked on a mismatch" "$([ -e "$ROOTFORGE_CHROOT_DIR" ] && echo present || echo absent)" "absent"

run_script bash "$CHROOT_LAUNCHER" install "$SANDBOX/rootfs.tar.xz" --sha256 "not-hex"
assert_eq "a malformed digest is refused" "$RC" "1"

GOOD="$(sha256sum "$SANDBOX/rootfs.tar.xz" | cut -d' ' -f1)"
run_script bash "$CHROOT_LAUNCHER" install "$SANDBOX/rootfs.tar.xz" --sha256 "$GOOD"
assert_eq "a verified archive installs" "$RC" "0"
assert_eq "the completion marker records the digest" "$(cat "$ROOTFORGE_CHROOT_DIR/etc/rootforge/install-complete")" "$GOOD"
assert_eq "the container hostname is set" "$(cat "$ROOTFORGE_CHROOT_DIR/etc/hostname")" "rootforge-chroot"
assert_eq "no staging directory is left behind" "$([ -e "$ROOTFORGE_CHROOT_DIR.partial" ] && echo present || echo absent)" "absent"
run_script bash "$CHROOT_LAUNCHER" install "$SANDBOX/rootfs.tar.xz" --sha256 "$GOOD"
assert_eq "an existing install is not overwritten" "$RC" "1"
assert_contains "the existing install is named" "$OUT" "already exists"

# A digest-only .sha256 (what build-rootfs.sh writes) and the sha256sum
# "digest  name" format are both accepted.
new_sandbox
export ROOTFORGE_CHROOT_DIR="$SANDBOX/rootfs"
make_rootfs_tar "$SANDBOX/rootfs.tar.xz"
sha256sum "$SANDBOX/rootfs.tar.xz" | cut -d' ' -f1 > "$SANDBOX/rootfs.tar.xz.sha256"
run_script bash "$CHROOT_LAUNCHER" install "$SANDBOX/rootfs.tar.xz" --sha256-file "$SANDBOX/rootfs.tar.xz.sha256"
assert_eq "--sha256-file accepts a digest-only file" "$RC" "0"
new_sandbox
export ROOTFORGE_CHROOT_DIR="$SANDBOX/rootfs"
make_rootfs_tar "$SANDBOX/rootfs.tar.xz"
( cd "$SANDBOX" && sha256sum rootfs.tar.xz > SHA256SUMS )
run_script bash "$CHROOT_LAUNCHER" install "$SANDBOX/rootfs.tar.xz" --sha256-file "$SANDBOX/SHA256SUMS"
assert_eq "--sha256-file accepts sha256sum output" "$RC" "0"

# craft_tar <out> <python-body> — build a hostile archive with tarfile.
craft_tar() {
  python3 -I - "$1" "$2" <<'PY'
import io, sys, tarfile
out, kind = sys.argv[1], sys.argv[2]
with tarfile.open(out, "w:xz") as tar:
    def add(name, data=b"x", link=None):
        info = tarfile.TarInfo(name)
        if link is not None:
            info.type = tarfile.SYMTYPE
            info.linkname = link
            tar.addfile(info)
        else:
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    add("etc/hostname", b"ok\n")
    if kind == "absolute":
        add("/tmp/rf-evil-absolute")
    elif kind == "dotdot":
        add("../rf-evil-dotdot")
    elif kind == "symlink-through":
        add("escape", link="/tmp")
        add("escape/rf-evil-through")
PY
}

for kind in absolute dotdot symlink-through; do
  new_sandbox
  export ROOTFORGE_CHROOT_DIR="$SANDBOX/rootfs"
  craft_tar "$SANDBOX/evil.tar.xz" "$kind"
  EVIL_SUM="$(sha256sum "$SANDBOX/evil.tar.xz" | cut -d' ' -f1)"
  run_script bash "$CHROOT_LAUNCHER" install "$SANDBOX/evil.tar.xz" --sha256 "$EVIL_SUM"
  assert_eq "a $kind member is refused even with a matching checksum" "$RC" "1"
  assert_contains "the $kind refusal says it is not safe to unpack as root" "$OUT" "not safe to unpack as root"
  assert_eq "a $kind archive unpacks nothing" "$([ -e "$ROOTFORGE_CHROOT_DIR" ] && echo present || echo absent)" "absent"
  assert_eq "a $kind archive leaves no staging directory" "$([ -e "$ROOTFORGE_CHROOT_DIR.partial" ] && echo present || echo absent)" "absent"
done

# A truncated download whose checksum was computed *from the truncated file*
# still must not install.
new_sandbox
export ROOTFORGE_CHROOT_DIR="$SANDBOX/rootfs"
make_rootfs_tar "$SANDBOX/rootfs.tar.xz"
head -c 100 "$SANDBOX/rootfs.tar.xz" > "$SANDBOX/trunc.tar.xz"
TRUNC_SUM="$(sha256sum "$SANDBOX/trunc.tar.xz" | cut -d' ' -f1)"
run_script bash "$CHROOT_LAUNCHER" install "$SANDBOX/trunc.tar.xz" --sha256 "$TRUNC_SUM"
assert_eq "a truncated archive is refused" "$RC" "1"
assert_eq "a truncated archive installs nothing" "$([ -e "$ROOTFORGE_CHROOT_DIR" ] && echo present || echo absent)" "absent"

# login must not enter a rootfs that was never completed and verified.
new_sandbox
export ROOTFORGE_CHROOT_DIR="$SANDBOX/rootfs"
mkdir -p "$ROOTFORGE_CHROOT_DIR/etc"
run_script bash "$CHROOT_LAUNCHER" login
assert_eq "login refuses a rootfs without the completion marker" "$RC" "1"
assert_contains "the marker refusal says how to reinstall" "$OUT" "completed-install marker"
drop_sandbox

new_sandbox
# Android's `su -c` takes one string, so every privileged command in this
# launcher is built as text and re-parsed by a shell. ROOTFORGE_CHROOT_DIR is
# user-settable, so that quoting has to survive spaces and quotes. A first
# draft of rf_q used a sed pipeline with two backslashes where it needed
# four; that collapses to ''' and silently breaks the first path containing a
# quote while still reading as correct.
eval "$(sed -n '/^rf_q()/,/^}/p' "$CHROOT_LAUNCHER")"
SQ="'"
for hostile in "/data/local/rootforge" "/data/local/my dir" '/data/local/a$HOME' '/data/local/"dq"' '/data/local/back\slash'; do
  got="$(sh -c "printf %s $(rf_q "$hostile")")"
  assert_eq "rf_q round-trips [$hostile]" "$got" "$hostile"
done
QUOTED="/data/local/o${SQ}brien"
got="$(sh -c "printf %s $(rf_q "$QUOTED")")"
assert_eq "rf_q round-trips a path containing a quote" "$got" "$QUOTED"

rm -f "$SANDBOX/pwned"
EVIL="/data/local/x${SQ};touch $SANDBOX/pwned;${SQ}"
sh -c "printf %s $(rf_q "$EVIL")" >/dev/null 2>&1 || true
if [ -f "$SANDBOX/pwned" ]; then
  fail "rf_q blocks command injection through a rootfs path" "the payload executed"
else
  pass "rf_q blocks command injection through a rootfs path"
fi
drop_sandbox

section "termux/make-release-metadata.sh — release metadata from the real artifacts"

GEN="$REPO_ROOT/termux/make-release-metadata.sh"

# make_termux_tar <dist> <flavor> <arch> [build-info flavor] [build-info arch]
# A small rootfs-shaped archive whose /etc/rootforge/build-info says what the
# real build-rootfs.sh would record, plus the sha256sum-format .sha256 it writes.
make_termux_tar() {
  local dist="$1" flavor="$2" arch="$3" bflavor="${4:-$2}" barch="${5:-$3}"
  local src="$SANDBOX/src-$flavor-$arch"
  mkdir -p "$src/etc/rootforge" "$dist"
  printf 'flavor=%s\narch=%s\nx11=0\nbuilt=20260101_000000\n' "$bflavor" "$barch" > "$src/etc/rootforge/build-info"
  tar -C "$src" -cJf "$dist/rootforge-$flavor-$arch.tar.xz" .
  ( cd "$dist" && sha256sum "rootforge-$flavor-$arch.tar.xz" > "rootforge-$flavor-$arch.tar.xz.sha256" )
}
make_all_termux_tars() {
  local f a
  for f in proot chroot; do for a in arm64 amd64; do make_termux_tar "$1" "$f" "$a"; done; done
}

new_sandbox
make_all_termux_tars "$SANDBOX/dist"
run_script bash "$GEN" --tag v1.2.3 --dist "$SANDBOX/dist" --out "$SANDBOX/out"
assert_eq "a complete set of artifacts generates metadata" "$RC" "0"
ARM_SUM="$(cut -d' ' -f1 "$SANDBOX/dist/rootforge-proot-arm64.tar.xz.sha256")"
AMD_SUM="$(cut -d' ' -f1 "$SANDBOX/dist/rootforge-proot-amd64.tar.xz.sha256")"
assert_contains "the plugin carries the real arm64 digest" "$(cat "$SANDBOX/out/rootforge-proot-plugin.sh")" "$ARM_SUM"
assert_contains "the plugin carries the real amd64 digest" "$(cat "$SANDBOX/out/rootforge-proot-plugin.sh")" "$AMD_SUM"
assert_contains "the plugin URL is bound to the tag, not 'latest'" "$(cat "$SANDBOX/out/rootforge-proot-plugin.sh")" "releases/download/v1.2.3/rootforge-proot-arm64.tar.xz"
assert_not_contains "no moving 'latest' URL survives" "$(cat "$SANDBOX/out/rootforge-proot-plugin.sh" "$SANDBOX/out/install.sh")" "releases/latest"
assert_eq "no placeholder survives in the generated files" "$(cat "$SANDBOX/out/rootforge-proot-plugin.sh" "$SANDBOX/out/install.sh" | grep -cE '@[A-Z0-9_]+@|REPLACE_WITH' || true)" "0"
assert_eq "the generated installer is valid shell" "$(bash -n "$SANDBOX/out/install.sh" && echo ok)" "ok"
assert_eq "the generated plugin is valid shell" "$(bash -n "$SANDBOX/out/rootforge-proot-plugin.sh" && echo ok)" "ok"
PLUGIN_SUM="$(sha256sum "$SANDBOX/out/rootforge-proot-plugin.sh" | cut -d' ' -f1)"
assert_contains "the installer pins the digest of the plugin it ships with" "$(cat "$SANDBOX/out/install.sh")" "PLUGIN_SHA256=\"$PLUGIN_SUM\""
LAUNCHER_SUM="$(sha256sum "$SANDBOX/out/rootforge-chroot.sh" | cut -d' ' -f1)"
assert_contains "the installer pins the digest of the launcher" "$(cat "$SANDBOX/out/install.sh")" "$LAUNCHER_SUM"
assert_eq "the published launcher is the repository's launcher" "$(cmp -s "$SANDBOX/out/rootforge-chroot.sh" "$REPO_ROOT/termux/rootforge-chroot.sh" && echo same)" "same"
# SHA256SUMS must verify every listed file, in sha256sum's own format.
mkdir -p "$SANDBOX/assets"
cp "$SANDBOX"/dist/*.tar.xz "$SANDBOX"/out/* "$SANDBOX/assets/"
assert_eq "SHA256SUMS verifies every published file" "$(cd "$SANDBOX/assets" && sha256sum -c --quiet SHA256SUMS >/dev/null 2>&1 && echo ok)" "ok"
assert_eq "the metadata binds arch, flavor and URL to each digest" \
  "$(jq -r '.artifacts[] | select(.flavor=="chroot" and .arch=="arm64") | "\(.url) \(.sha256)"' "$SANDBOX/out/release-metadata.json")" \
  "https://github.com/Victorious93/rootforge-os/releases/download/v1.2.3/rootforge-chroot-arm64.tar.xz $(cut -d' ' -f1 "$SANDBOX/dist/rootforge-chroot-arm64.tar.xz.sha256")"

# The digest-only sidecar older builds wrote is still accepted.
new_sandbox; make_all_termux_tars "$SANDBOX/dist"
cut -d' ' -f1 "$SANDBOX/dist/rootforge-proot-arm64.tar.xz.sha256" > "$SANDBOX/dist/rootforge-proot-arm64.tar.xz.sha256.tmp"
mv "$SANDBOX/dist/rootforge-proot-arm64.tar.xz.sha256.tmp" "$SANDBOX/dist/rootforge-proot-arm64.tar.xz.sha256"
run_script bash "$GEN" --tag v1.2.3 --dist "$SANDBOX/dist" --out "$SANDBOX/out"
assert_eq "a digest-only .sha256 is accepted" "$RC" "0"

# The checked-in templates must not be usable as they are.
run_script bash "$REPO_ROOT/termux/templates/install.sh.in"
assert_eq "the installer template refuses to run" "$RC" "1"
assert_contains "the installer template says it is a template" "$OUT" "unreleased installer template"
OUT="$(bash -c 'declare -A TARBALL_URL TARBALL_SHA256; . "$1"; echo "loaded rc=$?"' _ "$REPO_ROOT/termux/templates/proot-plugin.sh.in" 2>&1)"
assert_contains "the plugin template refuses to load" "$OUT" "unreleased template"
assert_not_contains "the plugin template does not reach distro_setup" "$OUT" "loaded rc=0"

# A tampered installer is caught by its own embedded digest check: the plugin
# it downloads must match the digest in the installer.
assert_contains "the installer verifies the plugin before installing it" "$(cat "$SANDBOX/out/install.sh")" 'if [[ "$ACTUAL" != "$PLUGIN_SHA256" ]]'

# --- refusals: each must fail and write nothing -------------------------------
gen_refused() {  # gen_refused <label> <expected-text> [extra args...]
  local label="$1" expect="$2"; shift 2
  rm -rf "$SANDBOX/out"
  run_script bash "$GEN" --tag v1.2.3 --dist "$SANDBOX/dist" --out "$SANDBOX/out" "$@"
  assert_eq "$label: refused" "$RC" "1"
  assert_contains "$label: says why" "$OUT" "$expect"
  assert_eq "$label: writes nothing" "$([ -e "$SANDBOX/out/install.sh" ] && echo wrote || echo clean)" "clean"
}

new_sandbox; make_all_termux_tars "$SANDBOX/dist"
rm "$SANDBOX/dist/rootforge-chroot-amd64.tar.xz"
gen_refused "a missing artifact" "missing artifact: rootforge-chroot-amd64.tar.xz"

new_sandbox; make_all_termux_tars "$SANDBOX/dist"
: > "$SANDBOX/dist/rootforge-proot-arm64.tar.xz"
gen_refused "an empty artifact" "artifact is empty"

new_sandbox; make_all_termux_tars "$SANDBOX/dist"
rm "$SANDBOX/dist/rootforge-proot-arm64.tar.xz.sha256"
gen_refused "a missing digest file" "missing digest file"

new_sandbox; make_all_termux_tars "$SANDBOX/dist"
printf 'not a digest\n' > "$SANDBOX/dist/rootforge-proot-arm64.tar.xz.sha256"
gen_refused "a malformed digest file" "does not contain a SHA-256 digest"

new_sandbox; make_all_termux_tars "$SANDBOX/dist"
printf 'tampered' >> "$SANDBOX/dist/rootforge-proot-arm64.tar.xz"
gen_refused "a tarball that no longer matches its digest" "does not match its recorded digest"

new_sandbox; make_all_termux_tars "$SANDBOX/dist"
make_termux_tar "$SANDBOX/dist" proot arm64 proot amd64
gen_refused "an arm64 file whose build-info says amd64" "claims arch 'arm64' but its build-info says 'amd64'"

new_sandbox; make_all_termux_tars "$SANDBOX/dist"
make_termux_tar "$SANDBOX/dist" chroot arm64 proot arm64
gen_refused "a chroot file whose build-info says proot" "claims flavor 'chroot' but its build-info says 'proot'"

new_sandbox; make_all_termux_tars "$SANDBOX/dist"
mkdir -p "$SANDBOX/bare/etc"; printf 'x\n' > "$SANDBOX/bare/etc/hostname"
tar -C "$SANDBOX/bare" -cJf "$SANDBOX/dist/rootforge-proot-amd64.tar.xz" .
sha256sum "$SANDBOX/dist/rootforge-proot-amd64.tar.xz" | cut -d' ' -f1 > "$SANDBOX/dist/rootforge-proot-amd64.tar.xz.sha256"
gen_refused "an artifact with no build-info" "has no /etc/rootforge/build-info"

new_sandbox; make_all_termux_tars "$SANDBOX/dist"
printf 'this is not an archive' > "$SANDBOX/dist/rootforge-proot-amd64.tar.xz"
sha256sum "$SANDBOX/dist/rootforge-proot-amd64.tar.xz" | cut -d' ' -f1 > "$SANDBOX/dist/rootforge-proot-amd64.tar.xz.sha256"
gen_refused "a file that is not an archive" "not a readable .tar.xz archive"

new_sandbox; make_all_termux_tars "$SANDBOX/dist"
gen_refused "a tag that is not a release tag" "is not a release tag" --tag main
new_sandbox; make_all_termux_tars "$SANDBOX/dist"
rm -rf "$SANDBOX/out"; run_script bash "$GEN" --tag v1.2.3 --dist "$SANDBOX/dist" --out "$SANDBOX/out" --repo 'a b/c'
assert_eq "a malformed --repo is refused" "$RC" "1"

# A locally built, single-architecture rootfs: explicit inputs, no GitHub.
new_sandbox
make_termux_tar "$SANDBOX/dist" proot arm64
run_script bash "$GEN" --tag local1 --dist "$SANDBOX/dist" --out "$SANDBOX/out" \
  --base-url http://192.168.1.5:8000 --arches arm64 --flavors proot
assert_eq "a local single-arch build generates metadata" "$RC" "0"
assert_contains "the local plugin points at the given host" "$(cat "$SANDBOX/out/rootforge-proot-plugin.sh")" "http://192.168.1.5:8000/rootforge-proot-arm64.tar.xz"
assert_not_contains "the local plugin has no x86_64 entry for an arch that was not built" "$(cat "$SANDBOX/out/rootforge-proot-plugin.sh")" "TARBALL_URL['x86_64']"
assert_not_contains "nor a digest check for it" "$(cat "$SANDBOX/out/rootforge-proot-plugin.sh")" "TARBALL_SHA256['x86_64']"
assert_contains "the installer says when no chroot rootfs was published" "$(cat "$SANDBOX/out/install.sh")" 'CHROOT_SHA256_ARM64=""'
run_script bash "$GEN" --tag local1 --dist "$SANDBOX/dist" --out "$SANDBOX/out2" --base-url 'http://h/x&y'
assert_eq "a --base-url with shell/sed metacharacters is refused" "$RC" "1"
drop_sandbox

section "tests/verify-release-assets.sh — what a release must contain"

VERIFY_ASSETS_SCRIPT="$REPO_ROOT/tests/verify-release-assets.sh"
MIN_ISO=1048576

# make_release_assets <dir> — the files release.yml assembles: generator output,
# the four tarballs with their digests, and a small ISO-shaped image (ISO 9660
# signature at byte 32769) whose digest is appended to SHA256SUMS.
make_release_assets() {
  local dir="$1"
  make_all_termux_tars "$SANDBOX/dist"
  bash "$GEN" --tag v1.2.3 --dist "$SANDBOX/dist" --out "$dir" >/dev/null
  cp "$SANDBOX"/dist/*.tar.xz "$SANDBOX"/dist/*.tar.xz.sha256 "$dir/"
  truncate -s 2M "$dir/rootforge-os-amd64.hybrid.iso"
  printf 'CD001' | dd of="$dir/rootforge-os-amd64.hybrid.iso" bs=1 seek=32769 conv=notrunc 2>/dev/null
  ( cd "$dir" && sha256sum rootforge-os-amd64.hybrid.iso > rootforge-os-amd64.hybrid.iso.sha256 \
      && cat rootforge-os-amd64.hybrid.iso.sha256 >> SHA256SUMS )
}
verify_assets() { run_script bash "$VERIFY_ASSETS_SCRIPT" "$SANDBOX/assets" --min-iso-bytes "$MIN_ISO" "$@"; }

new_sandbox; make_release_assets "$SANDBOX/assets"
verify_assets --tag v1.2.3
assert_eq "a complete, consistent release passes" "$RC" "0"
assert_contains "and says so" "$OUT" "release assets OK"

new_sandbox; make_release_assets "$SANDBOX/assets"
rm "$SANDBOX/assets/rootforge-os-amd64.hybrid.iso"
verify_assets
assert_eq "a release without the ISO is refused" "$RC" "1"
assert_contains "naming the missing file" "$OUT" "missing: rootforge-os-amd64.hybrid.iso"

new_sandbox; make_release_assets "$SANDBOX/assets"
: > "$SANDBOX/assets/rootforge-os-amd64.hybrid.iso"
verify_assets
assert_eq "an empty ISO is refused" "$RC" "1"
assert_contains "naming it empty" "$OUT" "empty: rootforge-os-amd64.hybrid.iso"

new_sandbox; make_release_assets "$SANDBOX/assets"
rm "$SANDBOX/assets/rootforge-chroot-arm64.tar.xz.sha256"
verify_assets
assert_eq "a missing rootfs digest file is refused" "$RC" "1"

new_sandbox; make_release_assets "$SANDBOX/assets"
printf 'tampered' >> "$SANDBOX/assets/rootforge-proot-arm64.tar.xz"
verify_assets
assert_eq "a tarball changed after its digest was recorded is refused" "$RC" "1"
assert_contains "the digest check names it" "$OUT" "rootforge-proot-arm64.tar.xz.sha256 does not verify"

new_sandbox; make_release_assets "$SANDBOX/assets"
printf 'tampered' >> "$SANDBOX/assets/rootforge-os-amd64.hybrid.iso"
verify_assets
assert_eq "an ISO changed after checksumming is refused" "$RC" "1"
assert_contains "SHA256SUMS notices too" "$OUT" "SHA256SUMS does not verify"

new_sandbox; make_release_assets "$SANDBOX/assets"
dd if=/dev/zero of="$SANDBOX/assets/rootforge-os-amd64.hybrid.iso" bs=1 seek=32769 count=5 conv=notrunc 2>/dev/null
( cd "$SANDBOX/assets" && sha256sum rootforge-os-amd64.hybrid.iso > rootforge-os-amd64.hybrid.iso.sha256 \
    && grep -v 'hybrid.iso$' SHA256SUMS > S && cat rootforge-os-amd64.hybrid.iso.sha256 >> S && mv S SHA256SUMS )
verify_assets
assert_eq "a file that is not an ISO 9660 image is refused even when its digest is right" "$RC" "1"
assert_contains "saying there is no ISO signature" "$OUT" "no ISO 9660 signature"

new_sandbox; make_release_assets "$SANDBOX/assets"
run_script bash "$VERIFY_ASSETS_SCRIPT" "$SANDBOX/assets"
assert_eq "an ISO below the default minimum size is refused" "$RC" "1"
assert_contains "naming the minimum" "$OUT" "below the"

new_sandbox; make_release_assets "$SANDBOX/assets"
grep -v 'hybrid.iso$' "$SANDBOX/assets/SHA256SUMS" > "$SANDBOX/S" && mv "$SANDBOX/S" "$SANDBOX/assets/SHA256SUMS"
verify_assets
assert_eq "a SHA256SUMS that leaves out the ISO is refused" "$RC" "1"
assert_contains "naming the gap" "$OUT" "SHA256SUMS has no entry for rootforge-os-amd64.hybrid.iso"

new_sandbox; make_release_assets "$SANDBOX/assets"
verify_assets --tag v9.9.9
assert_eq "metadata for a different tag is refused" "$RC" "1"
assert_contains "naming the tag" "$OUT" "release-metadata.json tag is not v9.9.9"

new_sandbox; make_release_assets "$SANDBOX/assets"
printf 'build log' > "$SANDBOX/assets/rootforge-build-20260101_000000.log"
verify_assets
assert_eq "a stray file in the asset directory is refused" "$RC" "1"
assert_contains "naming it" "$OUT" "unexpected file: rootforge-build-20260101_000000.log"

new_sandbox; make_release_assets "$SANDBOX/assets"
printf '# @PLUGIN_SHA256@\n' >> "$SANDBOX/assets/install.sh"
verify_assets
assert_eq "a surviving placeholder is refused" "$RC" "1"
assert_contains "naming it" "$OUT" "install.sh still contains an unfilled placeholder"
drop_sandbox

section "Makefile and auto/build — a failed build is a failed build"

# make_build_project — a throwaway copy of the build entry points.
make_build_project() {
  mkdir -p "$SANDBOX/proj/auto"
  cp "$REPO_ROOT/Makefile" "$SANDBOX/proj/Makefile"
  cp "$REPO_ROOT/auto/build" "$SANDBOX/proj/auto/build"
}

# make: the build wrapper fails -> make fails, and checksum never runs.
new_sandbox; make_build_project
printf 'stale-iso' > "$SANDBOX/proj/rootforge-os-amd64.hybrid.iso"
printf 'stale-digest  rootforge-os-amd64.hybrid.iso\n' > "$SANDBOX/proj/rootforge-os-amd64.hybrid.iso.sha256"
printf '#!/bin/sh\necho "lb build blew up" >&2\nexit 7\n' > "$SANDBOX/proj/fakebuild"; chmod +x "$SANDBOX/proj/fakebuild"
run_script make -C "$SANDBOX/proj" build ID_U=0 AUTO_BUILD=./fakebuild
assert_eq "make build fails when the build wrapper fails" "$([ "$RC" -ne 0 ] && echo failed || echo masked)" "failed"
assert_not_contains "no checksum is written after a failed build" "$OUT" "sha256 written"
assert_eq "the stale digest file is not rewritten" "$(cat "$SANDBOX/proj/rootforge-os-amd64.hybrid.iso.sha256")" "stale-digest  rootforge-os-amd64.hybrid.iso"

# make: a successful build produces a checksum that verifies.
new_sandbox; make_build_project
printf '#!/bin/sh\nprintf fresh-iso > rootforge-os-amd64.hybrid.iso\n' > "$SANDBOX/proj/fakebuild"; chmod +x "$SANDBOX/proj/fakebuild"
run_script make -C "$SANDBOX/proj" build ID_U=0 AUTO_BUILD=./fakebuild
assert_eq "make build succeeds when the wrapper does" "$RC" "0"
assert_eq "and the checksum verifies the new ISO" "$(cd "$SANDBOX/proj" && sha256sum -c --quiet rootforge-os-amd64.hybrid.iso.sha256 >/dev/null 2>&1 && echo ok)" "ok"

# make: not root -> refuse before building anything.
new_sandbox; make_build_project
printf '#!/bin/sh\ntouch ran\n' > "$SANDBOX/proj/fakebuild"; chmod +x "$SANDBOX/proj/fakebuild"
run_script make -C "$SANDBOX/proj" build ID_U=1000 AUTO_BUILD=./fakebuild
assert_eq "make build without root is refused" "$([ "$RC" -ne 0 ] && echo refused || echo ran)" "refused"
assert_contains "telling the operator to use sudo" "$OUT" "Run with sudo"
assert_eq "the build wrapper was never started" "$([ -e "$SANDBOX/proj/ran" ] && echo started || echo not-started)" "not-started"

# auto/build with a stubbed live-build: stale outputs must not survive.
make_fake_lb_env() {  # make_fake_lb_env <lb-body>
  mkdir -p "$SANDBOX/fakebin"
  printf '#!/bin/sh\necho 0\n' > "$SANDBOX/fakebin/id"
  printf '#!/bin/sh\nexit 0\n' > "$SANDBOX/fakebin/losetup"
  printf '#!/bin/sh\n%s\n' "$1" > "$SANDBOX/fakebin/lb"
  chmod +x "$SANDBOX/fakebin/"*
  printf 'stale' > "$SANDBOX/proj/rootforge-os-amd64.hybrid.iso"
  printf 'stale-digest  x\n' > "$SANDBOX/proj/rootforge-os-amd64.hybrid.iso.sha256"
  printf 'stale' > "$SANDBOX/proj/binary.iso"
}
run_auto_build() { run_script env PATH="$SANDBOX/fakebin:$PATH" bash -c 'cd "$1" && bash auto/build' _ "$SANDBOX/proj"; }

new_sandbox; make_build_project
make_fake_lb_env 'echo "E: package not found"; exit 100'
run_auto_build
assert_eq "auto/build exits with lb build's own status" "$RC" "100"
assert_eq "a failed build leaves no stale ISO" "$([ -e "$SANDBOX/proj/rootforge-os-amd64.hybrid.iso" ] && echo stale || echo clean)" "clean"
assert_eq "nor a stale digest file" "$([ -e "$SANDBOX/proj/rootforge-os-amd64.hybrid.iso.sha256" ] && echo stale || echo clean)" "clean"
assert_eq "nor a stale binary.iso that a later step could pick up" "$([ -e "$SANDBOX/proj/binary.iso" ] && echo stale || echo clean)" "clean"

new_sandbox; make_build_project
make_fake_lb_env 'echo "lb build: skipped, stages already done"; exit 0'
run_auto_build
assert_eq "a build that exits 0 but produces no ISO is an error" "$RC" "1"
assert_contains "and the message points at 'make clean'" "$OUT" "sudo make clean"
assert_eq "it cannot succeed on a stale binary.iso from an earlier run" "$([ -e "$SANDBOX/proj/rootforge-os-amd64.hybrid.iso" ] && echo present || echo absent)" "absent"

new_sandbox; make_build_project
make_fake_lb_env 'printf fresh > binary.hybrid.iso; exit 0'
run_auto_build
assert_eq "a build that produces an ISO succeeds" "$RC" "0"
assert_eq "and the ISO is this build's, not the stale one" "$(cat "$SANDBOX/proj/rootforge-os-amd64.hybrid.iso")" "fresh"
drop_sandbox

section "termux/bootstrap_proot.sh — only what this CPU can run"

# The script finds common.sh at ../lib/rootforge/sh relative to itself, as it
# does once installed in the rootfs, so build that layout.
install_bootstrap_layout() {
  INST="$SANDBOX/inst"
  mkdir -p "$INST/usr/local/bin" "$INST/usr/local/lib/rootforge/sh" "$SANDBOX/fakebin"
  cp "$REPO_ROOT/termux/bootstrap_proot.sh" "$INST/usr/local/bin/"
  cp "$LIB_DIR/rootforge/sh/common.sh" "$INST/usr/local/lib/rootforge/sh/"
  BOOT="$INST/usr/local/bin/bootstrap_proot.sh"
}
# fake_uname <machine> — a uname that reports a chosen CPU.
fake_uname() {
  printf '#!/bin/sh\n[ "$1" = "-m" ] && echo %s || echo Linux\n' "$1" > "$SANDBOX/fakebin/uname"
  chmod +x "$SANDBOX/fakebin/uname"
  export PATH="$SANDBOX/fakebin:$PATH"
}
# A curl that records the call and never touches a network.
fake_curl() {
  mkdir -p "$SANDBOX/fakebin"
  export PATH="$SANDBOX/fakebin:$PATH"
  printf '#!/bin/sh\necho "curl $*" >> "%s"\nout=""; while [ $# -gt 0 ]; do [ "$1" = "-o" ] && out="$2"; shift; done\n[ -n "$out" ] && printf "%s" "${RF_FAKE_CURL_BODY:-wrong-content}" > "$out"\nexit 0\n' "$RF_STUB_LOG" '%s' > "$SANDBOX/fakebin/curl"
  chmod +x "$SANDBOX/fakebin/curl"
}

new_sandbox; install_bootstrap_layout; fake_curl; fake_uname x86_64
run_script bash "$BOOT" --plan
assert_eq "--plan succeeds on an x86-64 host" "$RC" "0"
assert_contains "x86-64 gets the platform-tools" "$OUT" "install: platform-tools"
assert_contains "x86-64 gets build-tools" "$OUT" "install: build-tools;34.0.0"
assert_contains "x86-64 gets the NDK" "$OUT" "install: ndk;26.1.10909125"
assert_not_contains "x86-64 skips nothing by default" "$OUT" "skip:"
assert_not_contains "no emulator unless asked" "$OUT" "emulator"
assert_eq "--plan makes no network call" "$(grep -c '^curl' "$RF_STUB_LOG" || true)" "0"
run_script bash "$BOOT" --plan --with-system-image
assert_contains "an x86-64 host's image matches the host ABI" "$OUT" "system-images;android-34;google_apis;x86_64"
assert_not_contains "no arm64 image on an x86-64 host" "$OUT" "arm64-v8a"

new_sandbox; install_bootstrap_layout; fake_curl; fake_uname aarch64
run_script bash "$BOOT" --plan
assert_contains "arm64 still gets the CPU-independent platform jar" "$OUT" "install: platforms;android-34"
assert_not_contains "arm64 does not install x86-64 platform-tools" "$OUT" "install: platform-tools"
assert_contains "arm64 explains the platform-tools skip" "$OUT" "native Debian adb and fastboot"
assert_not_contains "arm64 does not install x86-64 build-tools" "$OUT" "install: build-tools"
assert_not_contains "arm64 does not install the x86-64 NDK" "$OUT" "install: ndk"
assert_contains "arm64 says why the NDK is skipped" "$OUT" "x86-64 only"
run_script bash "$BOOT" --plan --with-system-image
assert_not_contains "arm64 never installs an emulator" "$OUT" "install: emulator"
assert_contains "arm64 explains that no Linux arm64 emulator exists" "$OUT" "no Linux arm64 build"
run_script bash "$BOOT" --plan --allow-nonnative-sdk
assert_contains "--allow-nonnative-sdk installs build-tools on request" "$OUT" "install: build-tools;34.0.0"
assert_contains "--allow-nonnative-sdk installs the NDK on request" "$OUT" "install: ndk;26.1.10909125"
assert_contains "--allow-nonnative-sdk warns that it is unverified" "$OUT" "not verified to run"

new_sandbox; install_bootstrap_layout; fake_curl; fake_uname riscv64
run_script bash "$BOOT" --plan
assert_contains "an unsupported CPU gets only the Java parts" "$OUT" "install: platforms;android-34"
assert_contains "an unsupported CPU is named" "$OUT" "riscv64"
assert_not_contains "an unsupported CPU installs no native tool" "$OUT" "install: platform-tools"

new_sandbox; install_bootstrap_layout; fake_curl; fake_uname aarch64
run_script bash "$BOOT" --bogus
assert_eq "an unknown option is rejected" "$RC" "1"

# Capabilities are probed, not assumed.
new_sandbox; install_bootstrap_layout; fake_uname aarch64
mkdir -p "$SANDBOX/dev/net" "$SANDBOX/dev/bus/usb"
: > "$SANDBOX/dev/loop-control"
export ROOTFORGE_DEV_ROOT="$SANDBOX/dev"
run_script bash "$BOOT" --capabilities
assert_eq "--capabilities succeeds" "$RC" "0"
assert_eq "the capability record is valid JSON" "$(printf '%s' "$OUT" | jq -e . >/dev/null 2>&1 && echo ok)" "ok"
assert_eq "arm64 is reported as arm64" "$(printf '%s' "$OUT" | jq -r .host_arch)" "arm64"
assert_eq "an absent /dev/kvm is reported false, not assumed" "$(printf '%s' "$OUT" | jq -r .kvm)" "false"
assert_eq "a present loop-control is reported true" "$(printf '%s' "$OUT" | jq -r .loop_devices)" "true"
assert_eq "an absent /dev/net/tun is reported false" "$(printf '%s' "$OUT" | jq -r .tun)" "false"
assert_eq "a present USB bus is reported true" "$(printf '%s' "$OUT" | jq -r .usb_bus)" "true"
assert_eq "Google's x86-64 binaries are not native on arm64" "$(printf '%s' "$OUT" | jq -r .google_sdk_binaries_native)" "false"
assert_eq "no emulator is available on arm64" "$(printf '%s' "$OUT" | jq -r .android_emulator_available)" "false"
fake_uname x86_64
run_script bash "$BOOT" --capabilities
assert_eq "x86-64 has native Google binaries and an emulator" "$(printf '%s' "$OUT" | jq -r '[.google_sdk_binaries_native, .android_emulator_available] | all')" "true"
unset ROOTFORGE_DEV_ROOT

# The pinned archive digest: a download that does not match is refused and
# nothing is installed.
new_sandbox; install_bootstrap_layout; fake_curl; fake_uname x86_64
printf '#!/bin/sh\nexit 0\n' > "$SANDBOX/fakebin/javac"; chmod +x "$SANDBOX/fakebin/javac"
run_script bash "$BOOT"
assert_eq "an archive that does not match the pinned digest is refused" "$RC" "1"
assert_contains "the digest mismatch is reported" "$OUT" "SHA-256 mismatch"
assert_eq "nothing is installed after a mismatch" "$([ -e "$ROOTFORGE_HOME/android-sdk/cmdline-tools/latest" ] && echo present || echo absent)" "absent"
assert_eq "no staging directory is left behind" "$(ls -A "$ROOTFORGE_HOME" | grep -c '^\.cmdline-tools' || true)" "0"

# rf_fetch_verified on its own.
new_sandbox; fake_curl
. "$LIB_DIR/rootforge/sh/common.sh"
GOODSUM="$(printf 'payload' | sha256sum | cut -d' ' -f1)"
RF_FAKE_CURL_BODY=payload rf_fetch_verified "http://x/y" "$SANDBOX/got" "$GOODSUM"
assert_eq "a matching download is kept" "$(cat "$SANDBOX/got" 2>/dev/null)" "payload"
rf_fetch_verified "http://x/y" "$SANDBOX/bad" "$GOODSUM" 2>/dev/null; RC=$?
assert_eq "a mismatching download fails" "$RC" "1"
assert_eq "a mismatching download leaves nothing at the destination" "$([ -e "$SANDBOX/bad" ] && echo present || echo absent)" "absent"
assert_eq "a mismatching download leaves no partial file" "$(ls "$SANDBOX" | grep -c 'bad.part' || true)" "0"
assert_contains "the pinned cmdline-tools digest is a 64-hex SHA-256" "$RF_CMDLINE_TOOLS_SHA256" "2d2d5085"
assert_eq "both bootstrap scripts use the same pinned digest" "$(grep -c 'RF_CMDLINE_TOOLS_SHA256' "$BIN_DIR/00_bootstrap_distro.sh" || true)" "1"
drop_sandbox

section "rootforge module — the wrapped path end to end"

new_sandbox
export PYTHONPATH="$LIB_DIR"
RF() { python3 -m rootforge.core.cli "$@"; }

# Scaffold -> lint -> build, driven through the CLI rather than the scripts,
# so the wrapper is exercised as shipped.
OUT="$(cd "$SANDBOX" && RF module scaffold mymod "My Module" --target magisk 2>&1)"; RC=$?
assert_eq "module scaffold succeeds" "$RC" "0"
assert_contains "module scaffold reports where it landed" "$OUT" "modules/mymod"

OUT="$(cd "$SANDBOX" && RF module lint "$ROOTFORGE_HOME/modules/mymod" 2>&1)"; RC=$?
assert_eq "a CLI-scaffolded module lints clean" "$RC" "0"
assert_contains "lint reports PASS" "$OUT" "PASS"

OUT="$(cd "$SANDBOX" && RF module build mymod 2>&1)"; RC=$?
assert_eq "module build succeeds" "$RC" "0"

# The wrapper must not swallow a real failure into a success.
mkdir -p "$SANDBOX/broken"
printf 'id=x\n' > "$SANDBOX/broken/module.prop"
OUT="$(cd "$SANDBOX" && RF module lint "$SANDBOX/broken" 2>&1)"; RC=$?
assert_eq "a failing lint propagates its exit code" "$RC" "1"

# The four shell failure modes, now rejected by argparse before any script
# runs. Each was a real bug found in the hand-written parsing.
OUT="$(cd "$SANDBOX" && RF module build mymod --framework 2>&1)"; RC=$?
assert_eq "a missing option value is rejected" "$RC" "2"
assert_contains "a missing option value names the option" "$OUT" "--framework"

OUT="$(cd "$SANDBOX" && RF module build mymod --frmework magisk 2>&1)"; RC=$?
assert_eq "an unknown flag is rejected, not ignored" "$RC" "2"
assert_contains "an unknown flag is named" "$OUT" "unrecognized arguments"

OUT="$(cd "$SANDBOX" && RF module 2>&1)"; RC=$?
assert_eq "a missing subcommand is rejected" "$RC" "2"

OUT="$(cd "$SANDBOX" && RF module scaffold '9bad!' "Bad" 2>&1)"; RC=$?
assert_eq "an id the linter would reject never reaches the shell" "$RC" "2"
assert_contains "the id error cites the linter's rule" "$OUT" "lint_module.sh"
if [ -d "$ROOTFORGE_HOME/modules/9bad!" ]; then
  fail "a rejected id creates nothing" "the directory was created anyway"
else
  pass "a rejected id creates nothing"
fi

# A display name with spaces must stay one argument through the wrapper.
OUT="$(cd "$SANDBOX" && RF module scaffold spacedmod "Name With Spaces" 2>&1)"; RC=$?
assert_eq "a display name with spaces scaffolds" "$RC" "0"
assert_contains "the spaced name reaches module.prop intact" \
  "$(cat "$ROOTFORGE_HOME/modules/spacedmod/module.prop")" "name=Name With Spaces"
drop_sandbox

section "rootforge flash / backup — the wrapped path end to end"

new_sandbox
export PYTHONPATH="$LIB_DIR"
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1

# The happy path: the wrapper must reach fastboot with the image as an image,
# not as a serial. This is the shell bug the CLI is meant to make unreachable.
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img"
assert_eq "CLI flash boot succeeds" "$RC" "0"
assert_contains "CLI flash boot reaches fastboot" "$(cat "$RF_STUB_LOG")" "flash boot"
assert_not_contains "CLI never passes the image as a serial" \
  "$(cat "$RF_STUB_LOG")" "-s $SANDBOX/boot.img"

new_sandbox
export PYTHONPATH="$LIB_DIR"
make_boot_img "$SANDBOX/boot.img"
fb_device SERIAL9 a
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img" \
  --partition init_boot --serial SERIAL9
assert_contains "CLI honors --partition" "$(cat "$RF_STUB_LOG")" "flash init_boot"
assert_contains "CLI honors --serial" "$(cat "$RF_STUB_LOG")" "-s SERIAL9"

new_sandbox
export PYTHONPATH="$LIB_DIR"
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img" --both-slots --slots-same-build
assert_eq "CLI --both-slots with the assertion succeeds" "$RC" "0"
assert_contains "CLI --both-slots writes the active slot" "$(cat "$RF_STUB_LOG")" "--slot a flash boot"
assert_contains "CLI --both-slots writes the other slot" "$(cat "$RF_STUB_LOG")" "--slot b flash boot"
assert_not_contains "CLI --both-slots never changes the active slot" "$(cat "$RF_STUB_LOG")" "set-active"
assert_not_contains "CLI --both-slots is never read as a partition" \
  "$(cat "$RF_STUB_LOG")" "flash --both-slots"

new_sandbox
export PYTHONPATH="$LIB_DIR"
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img" --both-slots
assert_eq "CLI --both-slots alone is refused" "$RC" "2"
assert_contains "the CLI refusal explains the assertion" "$OUT" "--slots-same-build"
assert_eq "the refusal touches no device" "$(wc -l < "$RF_STUB_LOG")" "0"

# argparse prefix matching accepted --both-slot for --both-slots until
# allow_abbrev=False was set on every parser (subparsers do not inherit it).
# A near-miss flag must be an error, not a silent guess at what was meant.
new_sandbox
export PYTHONPATH="$LIB_DIR"
make_boot_img "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img" --both-slot
assert_eq "an abbreviated flag is rejected, not guessed" "$RC" "2"
assert_contains "the abbreviated flag is named" "$OUT" "unrecognized arguments"
assert_not_contains "a rejected flag flashes nothing" "$(cat "$RF_STUB_LOG")" "flash"

# Validation the shell did after picking a device up: argparse does it before
# fastboot is invoked at all.
new_sandbox
export PYTHONPATH="$LIB_DIR"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/missing.img"
assert_eq "a missing image is rejected" "$RC" "2"
assert_contains "a missing image says so" "$OUT" "image not found"
assert_eq "a missing image touches no device" "$(wc -l < "$RF_STUB_LOG")" "0"

new_sandbox
export PYTHONPATH="$LIB_DIR"
: > "$SANDBOX/empty.img"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/empty.img"
assert_eq "a zero-byte image is rejected" "$RC" "2"
assert_contains "a zero-byte image says so" "$OUT" "image is empty"
assert_eq "a zero-byte image touches no device" "$(wc -l < "$RF_STUB_LOG")" "0"

new_sandbox
export PYTHONPATH="$LIB_DIR"
make_boot_img "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img" --partition system
assert_eq "an unsupported partition is rejected" "$RC" "2"
assert_contains "the supported partitions are listed" "$OUT" "init_boot"
assert_not_contains "an unsupported partition is never written" "$(cat "$RF_STUB_LOG")" "flash system"

new_sandbox
export PYTHONPATH="$LIB_DIR"
make_boot_img "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img" --serial
assert_eq "a missing option value is rejected" "$RC" "2"
assert_contains "the missing value names its option" "$OUT" "--serial"

# The wrapper must pass a real failure through rather than reporting success.
new_sandbox
export PYTHONPATH="$LIB_DIR"
make_boot_img "$SANDBOX/boot.img"
fb_device FB1 a
export ROOTFORGE_ASSUME_YES=1 RF_STUB_FLASH_RC=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img"
assert_eq "a failed flash propagates its exit code" "$RC" "1"

# A blocked write propagates its own distinct exit code through the wrapper.
new_sandbox
export PYTHONPATH="$LIB_DIR"
make_boot_img "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img"
assert_eq "a blocked flash propagates exit 3" "$RC" "3"
assert_not_contains "a blocked flash makes no write" "$(cat "$RF_STUB_LOG")" " flash "

# --- backup / restore through the CLI ---

new_sandbox
export PYTHONPATH="$LIB_DIR"
make_backup testdev 20240101_000000 testdev a boot=realboot
fb_device FB1 a testdev
export ROOTFORGE_ASSUME_YES=1

run_script python3 -m rootforge.core.cli backup list testdev
assert_eq "backup list succeeds" "$RC" "0"
assert_contains "backup list shows the stored backup" "$OUT" "20240101_000000"
assert_eq "backup list touches no device" "$(wc -l < "$RF_STUB_LOG")" "0"

run_script python3 -m rootforge.core.cli backup verify testdev 20240101_000000
assert_eq "backup verify passes an intact backup" "$RC" "0"
assert_eq "backup verify touches no device" "$(wc -l < "$RF_STUB_LOG")" "0"

run_script python3 -m rootforge.core.cli backup restore testdev 20240101_000000
assert_eq "backup restore succeeds" "$RC" "0"
assert_contains "backup restore flashes the stored image" "$(cat "$RF_STUB_LOG")" "flash boot"

printf 'x' >> "$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000/boot.img"
run_script python3 -m rootforge.core.cli backup verify testdev 20240101_000000
assert_eq "backup verify fails a tampered backup" "$RC" "1"

# backup create through the CLI, then verify what it made.
new_sandbox
export PYTHONPATH="$LIB_DIR"
fb_device FB1 a testdev
run_script python3 -m rootforge.core.cli backup create testdev --partitions boot
assert_eq "backup create succeeds through the CLI" "$RC" "0"
BDIR="$(printf '%s\n' "$OUT" | sed -n 's/^BACKUP_DIR=//p' | tail -n 1)"
run_script python3 -m rootforge.core.cli backup verify testdev "$(basename "$BDIR")"
assert_eq "the CLI verifies a CLI-made backup" "$RC" "0"

# Path traversal, now rejected by argparse before the script runs. Passing
# these through used to write a backup outside devices/, and — on restore —
# read .img files from an arbitrary directory and flash them to the device.
new_sandbox
export PYTHONPATH="$LIB_DIR"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli backup create ../../escaped
assert_eq "a traversing codename is rejected" "$RC" "2"
assert_contains "the codename error cites the rule" "$OUT" "devices/"
if [ -e "$SANDBOX/home/escaped" ]; then
  fail "a rejected codename creates nothing outside devices/" "$SANDBOX/home/escaped exists"
else
  pass "a rejected codename creates nothing outside devices/"
fi

new_sandbox
export PYTHONPATH="$LIB_DIR"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli backup restore testdev ../../../../evil
assert_eq "a traversing timestamp is rejected" "$RC" "2"
assert_not_contains "a traversing timestamp flashes nothing" "$(cat "$RF_STUB_LOG")" "flash"

new_sandbox
export PYTHONPATH="$LIB_DIR"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli backup restore testdev .
assert_eq "a bare '.' timestamp is rejected" "$RC" "2"

# A serial is a serial, not a path: it is interpolated into a fastboot
# command line by the scripts.
new_sandbox
export PYTHONPATH="$LIB_DIR"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli backup create testdev --serial 'x; rm -rf /'
assert_eq "a serial with shell metacharacters is rejected" "$RC" "2"
assert_contains "the serial error shows the expected shape" "$OUT" "device serial"

new_sandbox
export PYTHONPATH="$LIB_DIR"
run_script python3 -m rootforge.core.cli backup
assert_eq "a missing backup subcommand is rejected" "$RC" "2"
run_script python3 -m rootforge.core.cli flash
assert_eq "a missing flash subcommand is rejected" "$RC" "2"
drop_sandbox

section "install_lsposed.sh — argument handling and asset selection"

new_sandbox
cp "$STUB_DIR/curl-github-releases" "$SANDBOX/curl"
chmod +x "$SANDBOX/curl"
export PATH="$SANDBOX:$STUB_DIR:$ORIGINAL_PATH"

# Regression: `--framework) FRAMEWORK="$2"` with nothing after it hit "$2"
# under set -u and died with a raw bash message naming a line number.
run_script bash "$BIN_DIR/install_lsposed.sh" --framework
assert_eq "--framework with no value is rejected" "$RC" "1"
assert_contains "--framework with no value explains itself" "$OUT" "needs a value"
assert_not_contains "--framework with no value is not a bash crash" "$OUT" "unbound variable"

# Regression: the catch-all arm took an unknown FLAG as a device serial, then
# its value replaced it. `--frmework kernelsu` ran `adb -s kernelsu`, left the
# framework at magisk, and exited 0 — a wrong install reported as a success.
run_script bash "$BIN_DIR/install_lsposed.sh" --frmework kernelsu
assert_eq "a typo'd flag is rejected, not read as a serial" "$RC" "1"
assert_contains "the typo'd flag is named" "$OUT" "Unknown option: --frmework"
assert_not_contains "a typo'd flag never reaches adb" "$(cat "$RF_STUB_LOG")" "adb"

# Regression: the framework was validated only after the download and the
# push, so a typo left an unusable zip sitting in /data/local/tmp.
run_script bash "$BIN_DIR/install_lsposed.sh" --framework bogus
assert_eq "an unknown framework is rejected" "$RC" "1"
assert_contains "an unknown framework says what it expected" "$OUT" "magisk|kernelsu"
assert_not_contains "an unknown framework downloads nothing" "$(cat "$RF_STUB_LOG")" "curl"
assert_not_contains "an unknown framework pushes nothing" "$(cat "$RF_STUB_LOG")" "push"

new_sandbox
cp "$STUB_DIR/curl-github-releases" "$SANDBOX/curl"
chmod +x "$SANDBOX/curl"
export PATH="$SANDBOX:$STUB_DIR:$ORIGINAL_PATH"
# Regression: `jq ... | head -1` installed whichever zip the API listed first.
# The stub lists the riru build first and the zygisk release build last, which
# is the wrong way round for a Zygisk-based framework.
run_script bash "$BIN_DIR/install_lsposed.sh"
assert_eq "a default run succeeds" "$RC" "0"
assert_contains "the zygisk release build is selected" "$OUT" "zygisk-release.zip"
assert_not_contains "the riru build is not what gets pushed" \
  "$(grep push "$RF_STUB_LOG" || true)" "riru"
assert_contains "the alternatives are named so a wrong pick is visible" "$OUT" "not installed"

new_sandbox
cp "$STUB_DIR/curl-github-releases" "$SANDBOX/curl"
chmod +x "$SANDBOX/curl"
export PATH="$SANDBOX:$STUB_DIR:$ORIGINAL_PATH"
# Regression: curl wrote straight to the cache path, so a download interrupted
# by Ctrl-C or a dropped connection left a partial file there. Every later run
# took the "-f" branch, logged "Using cached", and pushed the truncated zip to
# the device to be installed as a module.
mkdir -p "$ROOTFORGE_HOME/modules/.cache"
printf 'PK\003\004TRUNC' > "$ROOTFORGE_HOME/modules/.cache/LSPosed-v1.9.2-zygisk-release.zip"
run_script bash "$BIN_DIR/install_lsposed.sh"
assert_eq "a truncated cache entry does not fail the run" "$RC" "0"
assert_contains "a truncated cache entry is detected" "$OUT" "incomplete download"
assert_eq "the cache entry is replaced with the full download" \
  "$(wc -c < "$ROOTFORGE_HOME/modules/.cache/LSPosed-v1.9.2-zygisk-release.zip")" "200000"

new_sandbox
cp "$STUB_DIR/curl-github-releases" "$SANDBOX/curl"
chmod +x "$SANDBOX/curl"
export PATH="$SANDBOX:$STUB_DIR:$ORIGINAL_PATH"
# A short download must leave nothing behind: no cache entry for the next run
# to trust, and nothing pushed to the device.
export RF_STUB_DL_BYTES=10
run_script bash "$BIN_DIR/install_lsposed.sh"
assert_eq "a short download fails the run" "$RC" "1"
assert_contains "a short download says why" "$OUT" "below the"
assert_eq "a short download caches nothing" \
  "$(find "$ROOTFORGE_HOME/modules/.cache" -name '*.zip' | wc -l)" "0"
assert_not_contains "a short download pushes nothing" "$(cat "$RF_STUB_LOG")" "push"
drop_sandbox

section "install_adb_ime.sh — text reaches the device intact"

new_sandbox
cp "$STUB_DIR/adb-device-shell" "$SANDBOX/adb"
chmod +x "$SANDBOX/adb"
export PATH="$SANDBOX:$STUB_DIR:$ORIGINAL_PATH"

# `adb shell a b c` joins its arguments with spaces and hands the string to
# the device's /system/bin/sh. The text being typed was interpolated into that
# string, so it was parsed as shell source on the phone. This script exists
# precisely to type text that ordinary input handling mangles, so the bug
# defeated its own purpose before it was ever a security question.
run_script bash "$BIN_DIR/install_adb_ime.sh" type "it's a test"
assert_eq "typing text with an apostrophe succeeds" "$RC" "0"
DECODED="$(grep 'AM-RECEIVED' "$RF_STUB_LOG" | sed 's/.*msg //' | base64 -d 2>/dev/null || true)"
assert_eq "an apostrophe reaches the device intact" "$DECODED" "it's a test"

new_sandbox
cp "$STUB_DIR/adb-device-shell" "$SANDBOX/adb"
chmod +x "$SANDBOX/adb"
export PATH="$SANDBOX:$STUB_DIR:$ORIGINAL_PATH"
# Before the fix, `am` received only "hello" and `touch` ran as a second
# command on the device shell.
run_script bash "$BIN_DIR/install_adb_ime.sh" type "hello; touch $SANDBOX/EXECUTED"
DECODED="$(grep 'AM-RECEIVED' "$RF_STUB_LOG" | sed 's/.*msg //' | base64 -d 2>/dev/null || true)"
assert_eq "a semicolon is text, not a command separator" "$DECODED" "hello; touch $SANDBOX/EXECUTED"
if [ -e "$SANDBOX/EXECUTED" ]; then
  fail "nothing runs on the device shell" "the injected command executed"
else
  pass "nothing runs on the device shell"
fi

new_sandbox
cp "$STUB_DIR/adb-device-shell" "$SANDBOX/adb"
chmod +x "$SANDBOX/adb"
export PATH="$SANDBOX:$STUB_DIR:$ORIGINAL_PATH"
# The stated purpose: characters `adb shell input text` chokes on.
run_script bash "$BIN_DIR/install_adb_ime.sh" type 'héllo 🌍 "quoted" $HOME `x` \'
DECODED="$(grep 'AM-RECEIVED' "$RF_STUB_LOG" | sed 's/.*msg //' | base64 -d 2>/dev/null || true)"
assert_eq "non-ASCII, quotes, \$, backticks and a backslash all survive" \
  "$DECODED" 'héllo 🌍 "quoted" $HOME `x` \'

new_sandbox
cp "$STUB_DIR/adb-device-shell" "$SANDBOX/adb"
chmod +x "$SANDBOX/adb"
export PATH="$SANDBOX:$STUB_DIR:$ORIGINAL_PATH"
# A flag in the serial position used to become `adb -s --whatever`.
run_script bash "$BIN_DIR/install_adb_ime.sh" type "x" --serial
assert_eq "a flag is not accepted as a device serial" "$RC" "1"
assert_contains "the rejected flag is named" "$OUT" "Unknown option: --serial"
run_script bash "$BIN_DIR/install_adb_ime.sh" install --foo
assert_eq "install rejects a flag in the serial position too" "$RC" "1"
drop_sandbox

section "00_bootstrap_distro.sh — who it provisions for"

BOOTSTRAP_SCRIPT="$BIN_DIR/00_bootstrap_distro.sh"

new_sandbox
mkdir -p "$SANDBOX/devhome"
export RF_STUB_PASSWD="dev:x:1000:1000::$SANDBOX/devhome:/bin/bash"

# The service case: rootforge-firstboot.service runs as root with no login
# user. The old script fell back to root and provisioned /root/rootforge.
OUT="$(cd "$SANDBOX" && env -i PATH="$PATH" HOME=/root ROOTFORGE_TEST_EUID=0 \
  ROOTFORGE_INSTALL_USER_FILE="$SANDBOX/missing" RF_STUB_PASSWD="$RF_STUB_PASSWD" \
  bash "$BOOTSTRAP_SCRIPT" --check 2>&1)"; RC=$?
assert_eq "root with no identity at all is refused, not defaulted to root" "$RC" "1"
assert_contains "the refusal says how to name the user" "$OUT" "--user <login name>"
assert_not_contains "root's home is never chosen by default" "$OUT" "/root/rootforge"

OUT="$(cd "$SANDBOX" && HOME=/root SUDO_USER=root ROOTFORGE_TEST_EUID=0 ROOTFORGE_HOME= \
  ROOTFORGE_INSTALL_USER_FILE="$SANDBOX/missing" bash "$BOOTSTRAP_SCRIPT" --check 2>&1)"; RC=$?
assert_eq "SUDO_USER=root does not count as an identity" "$RC" "1"

# The recorded installer identity is the supported contract.
printf 'dev\n' > "$SANDBOX/install-user"
OUT="$(cd "$SANDBOX" && env -i PATH="$PATH" HOME=/root ROOTFORGE_TEST_EUID=0 \
  ROOTFORGE_INSTALL_USER_FILE="$SANDBOX/install-user" RF_STUB_PASSWD="$RF_STUB_PASSWD" \
  bash "$BOOTSTRAP_SCRIPT" --check 2>&1)"; RC=$?
assert_eq "the installer-recorded user resolves" "$RC" "0"
assert_contains "the recorded user is the target" "$OUT" "target user:     dev  (from $SANDBOX/install-user)"
assert_contains "the workspace is under that user's home" "$OUT" "ROOTFORGE_HOME:  $SANDBOX/devhome/rootforge"
assert_not_contains "the workspace is never under /root" "$OUT" "ROOTFORGE_HOME:  /root"

printf 'dev; touch %s/pwned\n' "$SANDBOX" > "$SANDBOX/install-user"
OUT="$(cd "$SANDBOX" && env -i PATH="$PATH" HOME=/root ROOTFORGE_TEST_EUID=0 \
  ROOTFORGE_INSTALL_USER_FILE="$SANDBOX/install-user" RF_STUB_PASSWD="$RF_STUB_PASSWD" \
  bash "$BOOTSTRAP_SCRIPT" --check 2>&1)"; RC=$?
assert_eq "hostile recorded content is refused" "$RC" "1"
assert_contains "the refusal says it is not a valid login name" "$OUT" "not a valid login name"
assert_eq "hostile recorded content executes nothing" "$([ -e "$SANDBOX/pwned" ] && echo ran || echo clean)" "clean"

# Precedence: --user beats SUDO_USER beats the recorded file.
printf 'someoneelse\n' > "$SANDBOX/install-user"
export RF_STUB_PASSWD="dev:x:1000:1000::$SANDBOX/devhome:/bin/bash
other:x:1001:1001::$SANDBOX/devhome:/bin/bash"
OUT="$(cd "$SANDBOX" && HOME=/root SUDO_USER=other ROOTFORGE_TEST_EUID=0 ROOTFORGE_HOME= \
  ROOTFORGE_INSTALL_USER_FILE="$SANDBOX/install-user" bash "$BOOTSTRAP_SCRIPT" --check 2>&1)"; RC=$?
assert_contains "SUDO_USER beats the recorded file" "$OUT" "target user:     other  (from \$SUDO_USER)"
OUT="$(cd "$SANDBOX" && HOME=/root SUDO_USER=other ROOTFORGE_TEST_EUID=0 ROOTFORGE_HOME= \
  ROOTFORGE_INSTALL_USER_FILE="$SANDBOX/install-user" bash "$BOOTSTRAP_SCRIPT" --user dev --check 2>&1)"; RC=$?
assert_contains "--user beats SUDO_USER" "$OUT" "target user:     dev  (from --user)"
assert_contains "the workspace follows the named user, not HOME" "$OUT" "ROOTFORGE_HOME:  $SANDBOX/devhome/rootforge"
assert_contains "the SDK follows the workspace" "$OUT" "SDK_ROOT:        $SANDBOX/devhome/rootforge/android-sdk"

# The remaining cases use the real account database (root, nobody, the invoker).
unset RF_STUB_PASSWD

# A non-root invoker with no other identity provisions for themselves.
OUT="$(cd "$SANDBOX" && env -u SUDO_USER ROOTFORGE_TEST_EUID=1000 ROOTFORGE_HOME= \
  ROOTFORGE_INSTALL_USER_FILE="$SANDBOX/missing" bash "$BOOTSTRAP_SCRIPT" --check 2>&1)"; RC=$?
assert_contains "a non-root invoker provisions for themselves" "$OUT" "(from the invoking user)"

# An explicit --user root is the operator's decision and is honored.
OUT="$(cd "$SANDBOX" && ROOTFORGE_TEST_EUID=0 ROOTFORGE_HOME= bash "$BOOTSTRAP_SCRIPT" --user root --check 2>&1)"; RC=$?
assert_eq "an explicit --user root is honored" "$RC" "0"
assert_contains "an explicit --user root is the target" "$OUT" "target user:     root  (from --user)"

# A user getent does not know at all is a hard stop, not a guess.
OUT="$(cd "$SANDBOX" && ROOTFORGE_TEST_EUID=0 ROOTFORGE_HOME= bash "$BOOTSTRAP_SCRIPT" --user ghost --check 2>&1)"; RC=$?
assert_eq "an unknown user is refused" "$RC" "1"
assert_contains "the refusal names getent" "$OUT" "getent"

# A system account's home is /nonexistent; a 15 GB SDK must not be aimed there.
OUT="$(cd "$SANDBOX" && ROOTFORGE_TEST_EUID=0 ROOTFORGE_HOME= bash "$BOOTSTRAP_SCRIPT" --user nobody --check 2>&1)"; RC=$?
assert_eq "a system account is refused" "$RC" "1"
assert_contains "the refusal names the missing home" "$OUT" "does not exist"
OUT="$(cd "$SANDBOX" && ROOTFORGE_TEST_EUID=0 ROOTFORGE_HOME=/srv/rf bash "$BOOTSTRAP_SCRIPT" --user nobody --check 2>&1)"; RC=$?
assert_eq "an explicit ROOTFORGE_HOME overrides the refusal" "$RC" "0"
assert_contains "an explicit ROOTFORGE_HOME is honored" "$OUT" "ROOTFORGE_HOME:  /srv/rf"

# A unexported USER is not a crash.
OUT="$(cd "$SANDBOX" && env -u USER -u SUDO_USER -u ROOTFORGE_HOME ROOTFORGE_TEST_EUID=1000 HOME=/root \
  bash "$BOOTSTRAP_SCRIPT" --check 2>&1)"; RC=$?
assert_not_contains "an unexported USER is not an unbound-variable crash" "$OUT" "unbound variable"

# Regression: `--headless` typos used to be ignored, installing the full desktop.
export RF_STUB_PASSWD="dev:x:1000:1000::$SANDBOX/devhome:/bin/bash"
OUT="$(cd "$SANDBOX" && ROOTFORGE_TEST_EUID=0 bash "$BOOTSTRAP_SCRIPT" --user dev --headles --check 2>&1)"; RC=$?
assert_eq "a typo'd --headless is rejected" "$RC" "1"
assert_contains "the typo'd flag is named" "$OUT" "Unknown option: --headles"
OUT="$(cd "$SANDBOX" && ROOTFORGE_TEST_EUID=0 ROOTFORGE_HOME= bash "$BOOTSTRAP_SCRIPT" --user dev --headless --check 2>&1)"
assert_contains "--headless is actually reflected" "$OUT" "desktop install: skipped"
OUT="$(cd "$SANDBOX" && bash "$BOOTSTRAP_SCRIPT" --only both 2>&1)"; RC=$?
assert_eq "an unknown --only stage is rejected" "$RC" "1"
OUT="$(cd "$SANDBOX" && ROOTFORGE_TEST_EUID=1000 ROOTFORGE_INSTALL_USER_FILE="$SANDBOX/missing" bash "$BOOTSTRAP_SCRIPT" --user dev --only system 2>&1)"; RC=$?
assert_eq "the system stages refuse to run without root" "$RC" "1"
assert_contains "that refusal points at --only user" "$OUT" "--only user"
unset RF_STUB_PASSWD
drop_sandbox

section "00_bootstrap_distro.sh — resumable, user-owned stages"

# System stages, with every system command and path redirected.
sys_env() {
  mkdir -p "$SANDBOX/fakebin" "$SANDBOX/devhome"
  export PATH="$SANDBOX/fakebin:$PATH"
  export RF_STUB_PASSWD="dev:x:1000:1000::$SANDBOX/devhome:/bin/bash"
  export ROOTFORGE_TEST_EUID=0 ROOTFORGE_STATE_DIR="$SANDBOX/state" ROOTFORGE_UDEV_RULES="$SANDBOX/udev/51-android.rules"
  printf '#!/bin/sh\necho "apt-get $*" >> "%s"\n[ -n "$RF_STUB_APT_FAIL" ] && [ "$1" = install ] && exit 100\nexit 0\n' "$RF_STUB_LOG" > "$SANDBOX/fakebin/apt-get"
  printf '#!/bin/sh\necho "usermod $*" >> "%s"\n' "$RF_STUB_LOG" > "$SANDBOX/fakebin/usermod"
  printf '#!/bin/sh\necho "udevadm $*" >> "%s"\n' "$RF_STUB_LOG" > "$SANDBOX/fakebin/udevadm"
  printf '#!/bin/sh\nif [ "$1" = group ]; then [ "$2" = "$RF_STUB_NO_GROUP" ] && exit 2; echo "$2:x:1:"; exit 0; fi\nif [ "$1" = passwd ]; then printf "%%s\\n" "$RF_STUB_PASSWD" | grep "^$2:" || exit 2; exit 0; fi\nexec /usr/bin/getent "$@"\n' > "$SANDBOX/fakebin/getent"
  printf '#!/bin/sh\nexit 0\n' > "$SANDBOX/fakebin/dpkg"
  chmod +x "$SANDBOX"/fakebin/*
  export ROOTFORGE_APT_GET="$SANDBOX/fakebin/apt-get"
}
unset RF_STUB_APT_FAIL RF_STUB_NO_GROUP

new_sandbox; sys_env
run_script bash "$BOOTSTRAP_SCRIPT" --user dev --only system --headless
assert_eq "the system stages complete" "$RC" "0"
assert_contains "packages are installed" "$(cat "$RF_STUB_LOG")" "apt-get install"
assert_not_contains "no incidental whole-system upgrade" "$(cat "$RF_STUB_LOG")" "upgrade"
assert_contains "the udev rules are written" "$(cat "$SANDBOX/udev/51-android.rules")" 'idVendor}=="18d1"'
assert_contains "the user joins the device groups" "$(cat "$RF_STUB_LOG")" "usermod -aG kvm,plugdev,docker dev"
assert_eq "every system stage leaves a marker" "$(ls "$SANDBOX/state/provision" | tr '\n' ' ')" "groups.done packages.done udev.done "
APT_CALLS_BEFORE="$(grep -c '^apt-get' "$RF_STUB_LOG")"
run_script bash "$BOOTSTRAP_SCRIPT" --user dev --only system --headless
assert_eq "a completed run does nothing the second time" "$(grep -c '^apt-get' "$RF_STUB_LOG")" "$APT_CALLS_BEFORE"
assert_contains "completed stages are reported as skipped" "$OUT" "stage packages: already complete"

# A failed stage leaves no marker; the retry resumes there and skips the rest.
new_sandbox; sys_env
export RF_STUB_APT_FAIL=1
run_script bash "$BOOTSTRAP_SCRIPT" --user dev --only system --headless
assert_eq "a failing package stage fails the run" "$RC" "100"
assert_eq "the failed stage left no marker" "$([ -e "$SANDBOX/state/provision/packages.done" ] && echo marked || echo unmarked)" "unmarked"
assert_eq "later stages did not run after the failure" "$([ -e "$SANDBOX/state/provision/udev.done" ] && echo ran || echo not-run)" "not-run"
unset RF_STUB_APT_FAIL
run_script bash "$BOOTSTRAP_SCRIPT" --user dev --only system --headless
assert_eq "the retry completes" "$RC" "0"
assert_eq "the retry finished every stage" "$(ls "$SANDBOX/state/provision" | wc -l | tr -d ' ')" "3"

# Only groups that exist are joined; a missing one is said, not silently dropped.
new_sandbox; sys_env
export RF_STUB_NO_GROUP=docker
run_script bash "$BOOTSTRAP_SCRIPT" --user dev --only system --headless
assert_contains "a missing group is skipped by name" "$OUT" "group docker does not exist"
assert_contains "the groups that exist are still joined" "$(cat "$RF_STUB_LOG")" "usermod -aG kvm,plugdev dev"
unset RF_STUB_NO_GROUP

# The desktop is not reinstalled when it is already there (dpkg -s succeeds).
new_sandbox; sys_env
run_script bash "$BOOTSTRAP_SCRIPT" --user dev --only system
assert_contains "an existing desktop is not reinstalled" "$OUT" "GNOME already installed"
assert_not_contains "no desktop packages on an installed desktop" "$(cat "$RF_STUB_LOG")" "gdm3"

# udev rules are rewritten only when they differ.
new_sandbox; sys_env
mkdir -p "$SANDBOX/udev"
run_script bash "$BOOTSTRAP_SCRIPT" --user dev --only system --headless
touch -d '2001-01-01' "$SANDBOX/udev/51-android.rules"
rm "$SANDBOX/state/provision/udev.done"
run_script bash "$BOOTSTRAP_SCRIPT" --user dev --only system --headless
assert_eq "identical udev rules are not rewritten" "$(date -r "$SANDBOX/udev/51-android.rules" +%Y)" "2001"

# User stages run as the user and resume. The SDK download is pinned, so a
# wrong archive fails the sdk stage; the workspace stage stays done.
new_sandbox; sys_env
fake_uname x86_64; fake_curl
printf '#!/bin/sh\nexit 0\n' > "$SANDBOX/fakebin/javac"; chmod +x "$SANDBOX/fakebin/javac"
ME="$(id -un)"
export RF_STUB_PASSWD="$ME:x:$(id -u):$(id -g)::$SANDBOX/home:/bin/bash"
unset ROOTFORGE_HOME
run_script bash "$BOOTSTRAP_SCRIPT" --user "$ME" --only user
assert_eq "a wrong SDK archive fails the user stages" "$RC" "1"
assert_contains "the failure is the pinned-digest mismatch" "$OUT" "SHA-256 mismatch"
assert_eq "the workspace stage still completed" "$([ -f "$SANDBOX/home/rootforge/.provision/workspace.done" ] && echo done || echo missing)" "done"
assert_eq "the sdk stage left no marker" "$([ -e "$SANDBOX/home/rootforge/.provision/sdk.done" ] && echo marked || echo unmarked)" "unmarked"
assert_eq "no staging directory is left in the workspace" "$(ls -A "$SANDBOX/home/rootforge" | grep -c '^\.sdk-stage' || true)" "0"
assert_eq "no half-installed SDK is left" "$([ -e "$SANDBOX/home/rootforge/android-sdk/cmdline-tools/latest" ] && echo present || echo absent)" "absent"
assert_eq "the keys directory is private" "$(stat -c %a "$SANDBOX/home/rootforge/keys")" "700"
run_script bash "$BOOTSTRAP_SCRIPT" --user "$ME" --only user
assert_contains "the retry skips the finished workspace stage" "$OUT" "stage workspace: already complete"
assert_not_contains "the shared profile is never rewritten by provisioning" "$(cat "$BOOTSTRAP_SCRIPT")" "cat > \"\$PROFILE_D\""
drop_sandbox

section "installer cleanup — live user, sudo rule, installed identity"

CAL="$REPO_ROOT/config/includes.chroot/etc/calamares/modules"
LIVEUSER="$(sed -n 's/^LIVE_USERNAME="\(.*\)"/\1/p' "$REPO_ROOT/config/includes.chroot/etc/live/config.conf")"
assert_eq "removeuser targets exactly the live account" "$(sed -n 's/^username: //p' "$CAL/removeuser.conf")" "$LIVEUSER"
assert_contains "the live username cannot be chosen for the installed user" "$(cat "$CAL/users.conf")" "forbidden_names: [ root, $LIVEUSER ]"
SUDOERS="$REPO_ROOT/config/includes.chroot/etc/sudoers.d/rootforge-live"
assert_contains "the live sudo rule is for the live account" "$(cat "$SUDOERS")" "$LIVEUSER ALL=(ALL) NOPASSWD: ALL"
assert_contains "the install removes that sudoers file explicitly" "$(cat "$CAL/shellprocess.conf")" "rm -f /etc/sudoers.d/rootforge-live"
assert_contains "the install records the installed user for first boot" "$(cat "$CAL/shellprocess.conf")" '${USER}'
assert_contains "the identity is written where the provisioning script reads it" "$(cat "$CAL/shellprocess.conf")" "/var/lib/rootforge/install-user"
assert_contains "the script reads that same file" "$(cat "$BOOTSTRAP_SCRIPT")" 'install-user'
# Execute the installer's recorded commands against a sandbox, then let the
# provisioning script resolve the identity they wrote: the contract between
# the installer and first boot, end to end. Only paths are redirected; the
# commands themselves run as shipped.
mkdir -p "$SANDBOX/etc/sudoers.d" "$SANDBOX/devhome"
printf 'rootforge ALL=(ALL) NOPASSWD: ALL\n' > "$SANDBOX/etc/sudoers.d/rootforge-live"
python3 -I - "$CAL/shellprocess.conf" "$SANDBOX" > "$SANDBOX/commands.txt" <<'PY'
import sys, yaml
conf = yaml.safe_load(open(sys.argv[1]))
box = sys.argv[2]
for cmd in conf["command"]:
    if cmd.startswith(("touch /etc", "systemctl")):
        continue
    cmd = cmd.replace("${USER}", "dev").replace("/etc/sudoers.d", box + "/etc/sudoers.d").replace("/var/lib/rootforge", box + "/state")
    print(cmd.replace("\n", " "))
PY
while IFS= read -r cmd; do sh -c "$cmd" || fail "installer command failed: $cmd"; done < "$SANDBOX/commands.txt"
assert_eq "the installer removes the live sudo rule" "$([ -e "$SANDBOX/etc/sudoers.d/rootforge-live" ] && echo present || echo removed)" "removed"
assert_eq "the installer records the chosen user" "$(cat "$SANDBOX/state/install-user")" "dev"
export RF_STUB_PASSWD="dev:x:1000:1000::$SANDBOX/devhome:/bin/bash"
OUT="$(cd "$SANDBOX" && env -i PATH="$PATH" ROOTFORGE_TEST_EUID=0 ROOTFORGE_INSTALL_USER_FILE="$SANDBOX/state/install-user" \
  RF_STUB_PASSWD="$RF_STUB_PASSWD" bash "$BOOTSTRAP_SCRIPT" --check 2>&1)"; RC=$?
assert_eq "first boot resolves exactly the user the installer recorded" "$RC" "0"
assert_contains "first boot provisions for the installed user" "$OUT" "target user:     dev"
unset RF_STUB_PASSWD
drop_sandbox

section "esp32_toolkit.sh"

new_sandbox
mkdir -p "$SANDBOX/pathdir"
cat > "$SANDBOX/pathdir/esptool.py" <<'EOS'
#!/usr/bin/env bash
printf 'esptool %s\n' "$*" >> "$RF_STUB_LOG"
EOS
chmod +x "$SANDBOX/pathdir/esptool.py"
export PATH="$SANDBOX/pathdir:$STUB_DIR:$ORIGINAL_PATH"
head -c 2048 /dev/zero > "$SANDBOX/firmware.bin"

# Regression: `write_flash 0x0 "$FW"`. `pio run` — which this script's own
# scaffold tells you to run — emits an APPLICATION image, and the app
# partition starts at 0x10000. 0x0 on an ESP32-S3, the target in that same
# scaffold, is the second-stage bootloader. The old command wrote the app
# over the bootloader and the board stopped booting.
run_script bash "$BIN_DIR/esp32_toolkit.sh" flash "$SANDBOX/firmware.bin" /dev/ttyFAKE
assert_contains "a plain firmware.bin goes to the app offset" "$(cat "$RF_STUB_LOG")" "write_flash 0x10000"
assert_not_contains "a plain firmware.bin does not overwrite the bootloader" \
  "$(cat "$RF_STUB_LOG")" "write_flash 0x0 "

# A merged image genuinely does belong at 0x0, so that stays reachable —
# explicitly, and with a warning.
: > "$RF_STUB_LOG"
run_script bash "$BIN_DIR/esp32_toolkit.sh" flash "$SANDBOX/firmware.bin" /dev/ttyFAKE 0x0
assert_contains "an explicit 0x0 is still honored" "$(cat "$RF_STUB_LOG")" "write_flash 0x0"
assert_contains "an explicit 0x0 warns about the bootloader" "$OUT" "overwrites the bootloader"

: > "$RF_STUB_LOG"
run_script bash "$BIN_DIR/esp32_toolkit.sh" flash "$SANDBOX/firmware.bin" /dev/ttyFAKE notanoffset
assert_eq "a non-numeric offset is rejected" "$RC" "1"
assert_eq "a rejected offset flashes nothing" "$(wc -l < "$RF_STUB_LOG")" "0"

# The same directory-name traversal the backup paths had.
run_script bash "$BIN_DIR/esp32_toolkit.sh" new-project ../../escaped
assert_eq "a traversing project name is rejected" "$RC" "1"
assert_contains "the rejection explains itself" "$OUT" "Invalid project name"
if [ -e "$SANDBOX/home/escaped" ]; then
  fail "a traversing project name scaffolds nothing outside the tree" "it was created anyway"
else
  pass "a traversing project name scaffolds nothing outside the tree"
fi

run_script bash "$BIN_DIR/esp32_toolkit.sh" new-project tool-node_1
assert_eq "an ordinary project name still scaffolds" "$RC" "0"
if [ -f "$ROOTFORGE_HOME/esp32-projects/tool-node_1/platformio.ini" ]; then
  pass "the scaffold lands under esp32-projects/"
else
  fail "the scaffold lands under esp32-projects/" "platformio.ini missing"
fi
drop_sandbox

section "extract_ota.sh — an empty extraction is not a success"

# A dumper that exits 0 and writes whatever RF_STUB_DUMPER_WRITES names. That
# is not a contrived failure: a payload that simply does not contain the
# requested partitions is the ordinary way to reach it.
plant_dumper() {
  mkdir -p "$ROOTFORGE_HOME/bin"
  cat > "$ROOTFORGE_HOME/bin/payload-dumper-go" <<'EOS'
#!/usr/bin/env bash
OUT=""
prev=""
for a in "$@"; do [ "$prev" = "-o" ] && OUT="$a"; prev="$a"; done
[ -n "${RF_STUB_LOG:-}" ] && printf 'dumper %s\n' "$*" >> "$RF_STUB_LOG"
for f in ${RF_STUB_DUMPER_WRITES:-}; do
  head -c 1024 /dev/zero > "$OUT/$f"
done
exit 0
EOS
  chmod +x "$ROOTFORGE_HOME/bin/payload-dumper-go"
}

new_sandbox
plant_dumper
head -c 512 /dev/zero > "$SANDBOX/payload.bin"
# Regression: the script printed "Extraction complete" and exited 0 over an
# empty output directory. The failure then surfaced one step later as a
# confusing "no such file" from whatever was going to patch the boot image.
run_script bash "$BIN_DIR/extract_ota.sh" "$SANDBOX/payload.bin" "$SANDBOX/out" --partitions boot
assert_eq "an extraction that produced nothing fails" "$RC" "1"
assert_contains "the empty extraction says what to check" "$OUT" "produced no files"
assert_not_contains "an empty extraction is never called complete" "$OUT" "Extraction complete"

new_sandbox
plant_dumper
head -c 512 /dev/zero > "$SANDBOX/payload.bin"
export RF_STUB_DUMPER_WRITES="boot.img"
run_script bash "$BIN_DIR/extract_ota.sh" "$SANDBOX/payload.bin" "$SANDBOX/out" --partitions boot
assert_eq "a real extraction still succeeds" "$RC" "0"
assert_contains "a real extraction counts what it produced" "$OUT" "Extraction complete (1 file(s))"
drop_sandbox

section "rootforge ota — the wrapped path end to end"

new_sandbox
export PYTHONPATH="$LIB_DIR"
plant_dumper
head -c 512 /dev/zero > "$SANDBOX/payload.bin"
export RF_STUB_DUMPER_WRITES="boot.img init_boot.img"

# The shell bug this group exists to make unrepresentable: with an optional
# positional output directory, `extract_ota.sh ota.zip --partitions boot` read
# the flag as the directory name and left the partition list at its default.
run_script python3 -m rootforge.core.cli ota extract "$SANDBOX/payload.bin" \
  --partitions boot --output "$SANDBOX/out"
assert_eq "CLI ota extract succeeds" "$RC" "0"
assert_contains "the partition list reaches the dumper" "$(cat "$RF_STUB_LOG")" "-p boot"
assert_contains "the output directory reaches the dumper" "$(cat "$RF_STUB_LOG")" "-o $SANDBOX/out"
if [ -d "$SANDBOX/--partitions" ]; then
  fail "no directory is ever named after a flag" "$SANDBOX/--partitions was created"
else
  pass "no directory is ever named after a flag"
fi

new_sandbox
export PYTHONPATH="$LIB_DIR"
plant_dumper
run_script python3 -m rootforge.core.cli ota extract "$SANDBOX/missing.zip"
assert_eq "a missing input is rejected" "$RC" "2"
assert_contains "a missing input says so" "$OUT" "not found"
assert_eq "a missing input never runs the dumper" "$(wc -l < "$RF_STUB_LOG")" "0"

new_sandbox
export PYTHONPATH="$LIB_DIR"
plant_dumper
head -c 512 /dev/zero > "$SANDBOX/payload.bin"
# 'boot,' reaches payload-dumper-go as a request for a partition named '',
# which is a silent no-op rather than an error.
run_script python3 -m rootforge.core.cli ota extract "$SANDBOX/payload.bin" --partitions "boot,"
assert_eq "a trailing comma in the partition list is rejected" "$RC" "2"
assert_contains "the trailing comma is named" "$OUT" "trailing comma"

run_script python3 -m rootforge.core.cli ota extract "$SANDBOX/payload.bin" --partition boot
assert_eq "an abbreviated flag is rejected, not guessed" "$RC" "2"
assert_eq "a rejected flag never runs the dumper" "$(wc -l < "$RF_STUB_LOG")" "0"

run_script python3 -m rootforge.core.cli ota
assert_eq "a missing ota subcommand is rejected" "$RC" "2"
drop_sandbox

section "script logs — private, and tied to the CLI's execution ID"

# mode_of <path> — octal permission bits.
mode_of() { stat -c %a "$1"; }
LOGSH="$LIB_DIR/rootforge/sh/common.sh"

# The helper itself, under a permissive umask so a default-mode file would show.
new_sandbox
run_script bash -c '
  umask 000
  . "$1"
  unset ROOTFORGE_EXECUTION_ID
  rf_log_init "$2/logs/a.log"
  printf "%s|%s\n" "$ROOTFORGE_EXECUTION_ID" "$(stat -c %a "$2/logs/a.log")"
' _ "$LOGSH" "$SANDBOX"
assert_contains "a new script log is created 0600 even under umask 000" "$OUT" "|600"
GENERATED_ID="${OUT%%|*}"
assert_eq "a script run on its own generates a plain hex execution ID" "$(printf '%s' "$GENERATED_ID" | grep -cE '^[0-9a-f]{8}$')" "1"
assert_contains "the log's first line names the run" "$(head -n 1 "$SANDBOX/logs/a.log")" "# rootforge execution $GENERATED_ID: "

run_script bash -c '. "$1"; ROOTFORGE_EXECUTION_ID=feedbeef; rf_log_init "$2/logs/b.log"; bash -c "echo child:\$ROOTFORGE_EXECUTION_ID"' _ "$LOGSH" "$SANDBOX"
assert_contains "an inherited ID is kept and handed on to child processes" "$OUT" "child:feedbeef"
assert_contains "and stamped into the log" "$(cat "$SANDBOX/logs/b.log")" "execution feedbeef:"

for bad in '../../etc/x' 'a b' 'ab' 'bad;id'; do
  run_script bash -c '. "$1"; ROOTFORGE_EXECUTION_ID="$3"; rf_log_init "$2/logs/c.log"; printf "%s" "$ROOTFORGE_EXECUTION_ID"' _ "$LOGSH" "$SANDBOX" "$bad"
  assert_eq "an unsafe inherited ID ($bad) is replaced, not trusted" "$(printf '%s' "$OUT" | grep -cE '^[0-9a-f]{8}$')" "1"
done

# An existing file keeps the mode it already has: the helper creates, it does not chmod.
printf 'old\n' > "$SANDBOX/logs/existing.log"; chmod 644 "$SANDBOX/logs/existing.log"
run_script bash -c '. "$1"; rf_log_init "$2/logs/existing.log"' _ "$LOGSH" "$SANDBOX"
assert_eq "an existing log keeps its own mode" "$(mode_of "$SANDBOX/logs/existing.log")" "644"
assert_contains "and is appended to, not truncated" "$(cat "$SANDBOX/logs/existing.log")" "old"

# rf_private_file writes no header (it is for reports, where a '#' line would be a heading).
run_script bash -c 'umask 000; . "$1"; rf_private_file "$2/logs/report.md"' _ "$LOGSH" "$SANDBOX"
assert_eq "a report file is 0600" "$(mode_of "$SANDBOX/logs/report.md")" "600"
assert_eq "and has no header line" "$(wc -c < "$SANDBOX/logs/report.md" | tr -d ' ')" "0"

# Under sudo, a new file is handed to the invoking user; a stub `id`/`chown` stands in for root.
mkdir -p "$SANDBOX/fakebin2"
printf '#!/bin/sh\necho 0\n' > "$SANDBOX/fakebin2/id"
printf '#!/bin/sh\necho "chown $*" >> "$RF_STUB_LOG"\n' > "$SANDBOX/fakebin2/chown"
chmod +x "$SANDBOX/fakebin2/id" "$SANDBOX/fakebin2/chown"
run_script env PATH="$SANDBOX/fakebin2:$PATH" SUDO_USER=alice bash -c '. "$1"; rf_private_file "$2/logs/sudo.log"; rf_private_file "$2/logs/sudo.log"' _ "$LOGSH" "$SANDBOX"
assert_eq "under sudo a new log is chowned to the invoking user, once" "$(grep -c "^chown alice " "$RF_STUB_LOG")" "1"
: > "$RF_STUB_LOG"
run_script env PATH="$SANDBOX/fakebin2:$PATH" SUDO_USER=root bash -c '. "$1"; rf_private_file "$2/logs/sudo2.log"' _ "$LOGSH" "$SANDBOX"
assert_eq "SUDO_USER=root is never chowned" "$(grep -c '^chown' "$RF_STUB_LOG")" "0"
drop_sandbox

# End to end: one CLI invocation, one ID, in the CLI's JSON-lines log AND the
# wrapped script's own log, both private.
new_sandbox
export PYTHONPATH="$LIB_DIR"
plant_dumper
head -c 512 /dev/zero > "$SANDBOX/payload.bin"
export RF_STUB_DUMPER_WRITES="boot.img"
old_umask="$(umask)"; umask 000
run_script python3 -m rootforge.core.cli ota extract "$SANDBOX/payload.bin" --partitions boot --output "$SANDBOX/out"
umask "$old_umask"
assert_eq "ota extract (CLI + wrapped script) succeeds" "$RC" "0"
SCRIPT_LOG="$(ls "$ROOTFORGE_HOME"/logs/extract_ota_*.log 2>/dev/null | head -n 1)"
JSON_LOG="$(ls "$ROOTFORGE_HOME"/logs/rootforge-ota-extract-*.jsonl 2>/dev/null | head -n 1)"
assert_eq "the wrapped script wrote its own log" "$([ -n "$SCRIPT_LOG" ] && echo yes || echo no)" "yes"
assert_eq "the CLI wrote its JSON-lines log" "$([ -n "$JSON_LOG" ] && echo yes || echo no)" "yes"
CLI_ID="$(basename "$JSON_LOG" .jsonl)"; CLI_ID="${CLI_ID##*-}"
assert_contains "the script log carries the CLI's execution ID" "$(head -n 1 "$SCRIPT_LOG")" "execution $CLI_ID:"
assert_eq "the script log is 0600 under umask 000" "$(mode_of "$SCRIPT_LOG")" "600"
assert_eq "the CLI log is 0600 under umask 000" "$(mode_of "$JSON_LOG")" "600"
drop_sandbox

section "audit trail — flash and backup leave a CLI-side record"

# audit_field <jsonl> <event> <jq filter> — one value from a named event.
audit_field() { jq -r --arg e "$2" "select(.event == \$e) | $3" "$1" | head -n 1; }

# A blocked flash: nothing is written, and the record says so.
new_sandbox
export PYTHONPATH="$LIB_DIR"
make_boot_img "$SANDBOX/boot.img"
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img"
assert_eq "a flash with no device is blocked (exit 3)" "$RC" "3"
AUDIT_LOG="$(ls "$ROOTFORGE_HOME"/logs/rootforge-flash-boot-*.jsonl 2>/dev/null | head -n 1)"
assert_eq "the CLI wrote an audit log for it" "$([ -n "$AUDIT_LOG" ] && echo yes || echo no)" "yes"
assert_eq "it records the command" "$(audit_field "$AUDIT_LOG" 'command started' .command)" "flash boot"
assert_contains "it records the image argument" "$(audit_field "$AUDIT_LOG" 'command started' '.argv | join(" ")')" "$SANDBOX/boot.img"
assert_eq "it records the exit status the user saw" "$(audit_field "$AUDIT_LOG" 'command finished' .returncode)" "3"
assert_eq "a blocked run is a warning, not an error" "$(audit_field "$AUDIT_LOG" 'command finished' .level)" "warn"
assert_eq "it names the script that ran" "$(audit_field "$AUDIT_LOG" 'command finished' '.scripts[0].script')" "flash_patched_boot.sh"
assert_eq "and that script's exit status" "$(audit_field "$AUDIT_LOG" 'command finished' '.scripts[0].returncode')" "3"
SCRIPT_LOG_PATH="$(audit_field "$AUDIT_LOG" 'command finished' '.script_logs[0]')"
assert_eq "it links the script's own log" "$([ -f "$SCRIPT_LOG_PATH" ] && echo yes || echo no)" "yes"
AUDIT_ID="$(audit_field "$AUDIT_LOG" 'command started' .execution_id)"
assert_contains "and that log carries the same execution ID" "$(head -n 1 "$SCRIPT_LOG_PATH")" "execution $AUDIT_ID:"
assert_eq "the fastboot write was never reached" "$(grep -c 'flash' "$RF_STUB_LOG" || true)" "0"

# A script-backed read-only command still leaves a record.
new_sandbox
export PYTHONPATH="$LIB_DIR"
run_script python3 -m rootforge.core.cli backup list testdev
assert_eq "backup list succeeds" "$RC" "0"
AUDIT_LOG="$(ls "$ROOTFORGE_HOME"/logs/rootforge-backup-list-*.jsonl 2>/dev/null | head -n 1)"
assert_eq "backup list is audited" "$(audit_field "$AUDIT_LOG" 'command finished' .returncode)" "0"
assert_eq "at info level" "$(audit_field "$AUDIT_LOG" 'command finished' .level)" "info"

# A Python-native command (no script) is audited too.
new_sandbox
export PYTHONPATH="$LIB_DIR"
run_script python3 -m rootforge.core.cli backup verify nodev 20240101_000000
assert_eq "verifying a backup that does not exist fails" "$([ "$RC" -ne 0 ] && echo failed || echo passed)" "failed"
AUDIT_LOG="$(ls "$ROOTFORGE_HOME"/logs/rootforge-backup-verify-*.jsonl 2>/dev/null | head -n 1)"
assert_eq "backup verify is audited" "$(audit_field "$AUDIT_LOG" 'command finished' .command)" "backup verify"
assert_eq "no script was involved" "$(audit_field "$AUDIT_LOG" 'command finished' '.scripts | length')" "0"
assert_eq "its status matches what the user saw" "$(audit_field "$AUDIT_LOG" 'command finished' .returncode)" "$RC"

# An unwritable log location must not stop the command, and says so.
new_sandbox
export PYTHONPATH="$LIB_DIR"
printf 'x' > "$SANDBOX/blocker"
run_script env ROOTFORGE_HOME="$SANDBOX/blocker/rf" python3 -m rootforge.core.cli backup verify nodev 20240101_000000
assert_contains "an unrecordable run is announced" "$OUT" "will not be recorded"
assert_not_contains "and does not crash" "$OUT" "Traceback"
drop_sandbox

section "kernelsu_patch_boot.sh — what ends up as the kernel"

# curl and magiskboot shaped like the real ones: the release API answers with
# a KernelSU-style asset list, and a download writes RF_STUB_DL_BYTES bytes,
# which is how a truncated transfer is simulated.
plant_ksu_stubs() {
  mkdir -p "$SANDBOX/ksubin"
  cat > "$SANDBOX/ksubin/curl" <<'EOS'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >> "$RF_STUB_LOG"
OUT=""; prev=""
for a in "$@"; do [ "$prev" = "-o" ] && OUT="$a"; prev="$a"; done
if [ -n "$OUT" ]; then head -c "${RF_STUB_DL_BYTES:-4000000}" /dev/zero > "$OUT"; exit 0; fi
case "$*" in
  *releases/latest*) echo '{"tag_name":"v1.0.0"}' ;;
  *tags/*) echo '{"assets":[{"name":"android14-5.15-Image.gz","browser_download_url":"https://example.invalid/Image.gz"}]}' ;;
esac
EOS
  cat > "$SANDBOX/ksubin/magiskboot" <<'EOS'
#!/usr/bin/env bash
case "${1:-}" in
  unpack) head -c 100 /dev/zero > kernel ;;
  repack) head -c 200 /dev/zero > new-boot.img ;;
esac
EOS
  chmod +x "$SANDBOX/ksubin/curl" "$SANDBOX/ksubin/magiskboot"
  export PATH="$SANDBOX/ksubin:$STUB_DIR:$ORIGINAL_PATH"
  head -c 2048 /dev/zero > "$SANDBOX/boot.img"
}

new_sandbox
plant_ksu_stubs
# Regression, and the highest-consequence input bug in this repository.
# KSU_VERSION is interpolated into a GitHub API URL path, and curl resolves
# ../ segments before sending the request (RFC 3986 remove_dot_segments).
# Verified against the real api.github.com:
#
#   tags/../../../../octocat/Hello-World/releases/latest
#     > GET /repos/octocat/Hello-World/releases/latest HTTP/1.1
#
# The query has left tiann/KernelSU. Whatever that release names is
# downloaded and written in as the KERNEL of a boot image the user flashes.
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --stock-boot "$SANDBOX/boot.img" \
  --android-version 14 --ksu-version '../../../../octocat/Hello-World/releases/latest'
assert_eq "a tag that redirects the API query is rejected" "$RC" "1"
assert_contains "the rejection says what a tag looks like" "$OUT" "release tag"
assert_eq "a rejected tag makes no request at all" "$(wc -l < "$RF_STUB_LOG")" "0"

# An ordinary tag must still work.
new_sandbox
plant_ksu_stubs
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --stock-boot "$SANDBOX/boot.img" \
  --android-version 14 --ksu-version v0.9.5 --device pixel
assert_eq "an ordinary tag is accepted" "$RC" "0"
assert_contains "the ordinary tag reaches the API path" "$(cat "$RF_STUB_LOG")" "tags/v0.9.5"

new_sandbox
plant_ksu_stubs
# Regression: a truncated download was copied in as the kernel and repacked,
# and the script reported "Patched image: ..." as if nothing were wrong.
# Verified: a 12-byte "kernel" produced a patched boot image. Flashing that
# leaves the device unbootable — the one outcome this script exists to avoid.
export RF_STUB_DL_BYTES=12
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --stock-boot "$SANDBOX/boot.img" \
  --android-version 14 --device pixel
assert_eq "a truncated kernel download fails the patch" "$RC" "1"
assert_contains "the truncated download says why" "$OUT" "below the"
assert_eq "no boot image is produced from a truncated kernel" \
  "$(find "$ROOTFORGE_HOME/kernelsu-work" -name 'boot-ksu-patched-*' 2>/dev/null | wc -l)" "0"

new_sandbox
plant_ksu_stubs
# Not a traversal — a codename containing '/' just names a path whose parent
# does not exist. But it failed at the very last step with a raw cp error,
# after the kernel had been downloaded and magiskboot had run twice.
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --stock-boot "$SANDBOX/boot.img" \
  --android-version 14 --device '../../escaped'
assert_eq "an unusable device codename is rejected" "$RC" "1"
assert_contains "the codename rejection explains itself" "$OUT" "output filename"
assert_eq "the codename is rejected before anything is downloaded" \
  "$(wc -l < "$RF_STUB_LOG")" "0"

new_sandbox
plant_ksu_stubs
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --stock-boot "$SANDBOX/boot.img" \
  --android-version 14 --device pixel_6a
assert_eq "an ordinary codename still patches" "$RC" "0"
assert_contains "the patched image is named after the device" "$OUT" "boot-ksu-patched-pixel_6a"
drop_sandbox

section "rootforge boot — the wrapped path end to end"

new_sandbox
export PYTHONPATH="$LIB_DIR"
plant_ksu_stubs

run_script python3 -m rootforge.core.cli boot patch \
  --stock-boot "$SANDBOX/boot.img" --android-version 14 --device pixel_6a
assert_eq "CLI boot patch succeeds" "$RC" "0"
assert_contains "the patched image is named after the device" "$OUT" "boot-ksu-patched-pixel_6a"
assert_contains "the default tag reaches the API path" "$(cat "$RF_STUB_LOG")" "releases/latest"

new_sandbox
export PYTHONPATH="$LIB_DIR"
plant_ksu_stubs
# The tag that redirected the API query to another repository, now refused by
# argparse before a single request is made.
run_script python3 -m rootforge.core.cli boot patch \
  --stock-boot "$SANDBOX/boot.img" --android-version 14 \
  --ksu-version '../../../../octocat/Hello-World/releases/latest'
assert_eq "a URL-redirecting tag is rejected" "$RC" "2"
assert_contains "the rejection explains what the tag would do" "$OUT" "different repository"
assert_eq "a rejected tag makes no request" "$(wc -l < "$RF_STUB_LOG")" "0"

new_sandbox
export PYTHONPATH="$LIB_DIR"
plant_ksu_stubs
run_script python3 -m rootforge.core.cli boot patch \
  --stock-boot "$SANDBOX/missing.img" --android-version 14
assert_eq "a missing stock image is rejected" "$RC" "2"
assert_contains "a missing stock image says so" "$OUT" "boot image not found"
assert_eq "a missing stock image downloads nothing" "$(wc -l < "$RF_STUB_LOG")" "0"

run_script python3 -m rootforge.core.cli boot patch \
  --stock-boot "$SANDBOX/boot.img" --android-version 140
assert_eq "an implausible Android version is rejected" "$RC" "2"

run_script python3 -m rootforge.core.cli boot patch \
  --stock "$SANDBOX/boot.img" --android-version 14
assert_eq "an abbreviated flag is rejected, not guessed" "$RC" "2"

run_script python3 -m rootforge.core.cli boot
assert_eq "a missing boot subcommand is rejected" "$RC" "2"

new_sandbox
export PYTHONPATH="$LIB_DIR"
plant_ksu_stubs
# flash-last writes the boot partition. With no patched image to flash it must
# say so rather than reaching fastboot.
run_script python3 -m rootforge.core.cli boot flash-last
assert_eq "flash-last with nothing patched fails" "$RC" "1"
assert_contains "flash-last says what to do first" "$OUT" "run without --flash first"
drop_sandbox

section "rootforge boot — inspect, unpack, repack, cpio and verify end to end"

# plant_boot_tools — magiskboot and avbtool shaped like the real ones: unpack
# writes kernel + ramdisk.cpio into the current directory, repack writes
# new-boot.img, cpio edits the ramdisk, avbtool's verdict is its exit status.
# Knobs: RF_STUB_MB_RC (magiskboot exit), RF_STUB_NO_REPACK, RF_STUB_AVB_RC.
plant_boot_tools() {
  mkdir -p "$SANDBOX/bootbin"
  cat > "$SANDBOX/bootbin/magiskboot" <<'EOS'
#!/usr/bin/env bash
printf 'magiskboot %s\n' "$*" >> "$RF_STUB_LOG"
[ "${RF_STUB_MB_RC:-0}" != 0 ] && exit "$RF_STUB_MB_RC"
case "${1:-}" in
  unpack) head -c 100 /dev/zero > kernel; printf 'CPIO' > ramdisk.cpio ;;
  repack) [ -n "${RF_STUB_NO_REPACK:-}" ] || printf 'NEWBOOT-IMAGE' > new-boot.img ;;
  cpio)   shift; f="$1"; shift; printf '%s\n' "$*" >> "$f" ;;
esac
exit 0
EOS
  cat > "$SANDBOX/bootbin/avbtool" <<'EOS'
#!/usr/bin/env bash
printf 'avbtool %s\n' "$*" >> "$RF_STUB_LOG"
case "${1:-}" in
  version) echo "avbtool 1.2.3" ;;
  verify_image) exit "${RF_STUB_AVB_RC:-0}" ;;
esac
exit 0
EOS
  chmod +x "$SANDBOX/bootbin/magiskboot" "$SANDBOX/bootbin/avbtool"
  export PATH="$SANDBOX/bootbin:$STUB_DIR:$ORIGINAL_PATH"
  unset RF_STUB_MB_RC RF_STUB_NO_REPACK RF_STUB_AVB_RC
  make_boot_img "$SANDBOX/boot.img"
}
# the single JSON-lines log a command wrote, by command name
boot_json_log() { ls "$ROOTFORGE_HOME"/logs/rootforge-"$1"-*.jsonl 2>/dev/null | head -n 1; }

new_sandbox
export PYTHONPATH="$LIB_DIR"
plant_boot_tools
BOOT_SUM="$(sha256sum "$SANDBOX/boot.img" | cut -d' ' -f1)"
run_script python3 -m rootforge.core.cli boot inspect "$SANDBOX/boot.img"
assert_eq "boot inspect succeeds" "$RC" "0"
assert_contains "inspect lists the unpacked kernel" "$OUT" "kernel"
assert_contains "inspect lists the unpacked ramdisk" "$OUT" "ramdisk.cpio"
assert_eq "inspect never modifies the original image" "$(sha256sum "$SANDBOX/boot.img" | cut -d' ' -f1)" "$BOOT_SUM"
assert_eq "inspect leaves nothing next to the image" "$(ls "$SANDBOX" | grep -cE '^(kernel|ramdisk\.cpio|boot\.img\.bak)$' || true)" "0"
assert_contains "the audit log records the image hash" "$(cat "$(boot_json_log boot-inspect)")" "$BOOT_SUM"

new_sandbox
export PYTHONPATH="$LIB_DIR"
plant_boot_tools
export RF_STUB_MB_RC=3
run_script python3 -m rootforge.core.cli boot inspect "$SANDBOX/boot.img"
assert_eq "a magiskboot failure is the command's exit status" "$RC" "3"
assert_contains "and is recorded as a failure" "$(cat "$(boot_json_log boot-inspect)")" "inspect failed"
unset RF_STUB_MB_RC
: > "$RF_STUB_LOG"
run_script python3 -m rootforge.core.cli boot inspect "$SANDBOX/nope.img"
assert_eq "a missing image is rejected before any tool runs" "$RC" "1"
assert_eq "no tool was invoked for it" "$(grep -c '^magiskboot unpack' "$RF_STUB_LOG")" "0"

# unpack -> cpio -> repack, the real workflow, as separate CLI invocations.
new_sandbox
export PYTHONPATH="$LIB_DIR"
plant_boot_tools
run_script python3 -m rootforge.core.cli boot unpack "$SANDBOX/boot.img" "$SANDBOX/work"
assert_eq "boot unpack succeeds" "$RC" "0"
assert_eq "unpack keeps the original as repack's template" "$([ -f "$SANDBOX/work/boot.img" ] && echo yes || echo no)" "yes"
assert_eq "unpack produced the ramdisk" "$([ -f "$SANDBOX/work/ramdisk.cpio" ] && echo yes || echo no)" "yes"

run_script python3 -m rootforge.core.cli boot cpio "$SANDBOX/work" ramdisk.cpio -- 'add 0750 init magiskinit'
assert_eq "boot cpio succeeds" "$RC" "0"
assert_contains "the cpio commands reached magiskboot untouched" "$(cat "$RF_STUB_LOG")" "magiskboot cpio ramdisk.cpio add 0750 init magiskinit"
assert_contains "the ramdisk was changed" "$(cat "$SANDBOX/work/ramdisk.cpio")" "add 0750 init magiskinit"
assert_contains "cpio reports the patched ramdisk's hash" "$OUT" "SHA-256: $(sha256sum "$SANDBOX/work/ramdisk.cpio" | cut -d' ' -f1)"

run_script python3 -m rootforge.core.cli boot repack "$SANDBOX/work"
assert_eq "boot repack succeeds" "$RC" "0"
assert_contains "repack names the new image and its hash" "$OUT" "SHA-256: $(sha256sum "$SANDBOX/work/new-boot.img" | cut -d' ' -f1)"
assert_contains "the audit log records the output hash" "$(cat "$(boot_json_log boot-repack)")" "$(sha256sum "$SANDBOX/work/new-boot.img" | cut -d' ' -f1)"

# cpio refuses to run without a ramdisk or without commands.
run_script python3 -m rootforge.core.cli boot cpio "$SANDBOX/work" ramdisk.cpio
assert_eq "boot cpio with no commands fails" "$RC" "1"
run_script python3 -m rootforge.core.cli boot cpio "$SANDBOX/work" missing.cpio -- 'add 0750 init magiskinit'
assert_eq "boot cpio on a missing ramdisk fails" "$RC" "1"
assert_contains "and says to unpack first" "$OUT" "boot unpack"

# repack failure modes: no template, and a tool that exits 0 but produces nothing.
mkdir -p "$SANDBOX/empty"
run_script python3 -m rootforge.core.cli boot repack "$SANDBOX/empty"
assert_eq "repack without an unpacked template fails" "$RC" "1"
assert_contains "and says to unpack first" "$OUT" "boot unpack"
export RF_STUB_NO_REPACK=1
rm -f "$SANDBOX/work/new-boot.img"
run_script python3 -m rootforge.core.cli boot repack "$SANDBOX/work"
assert_eq "repack that produced no image is a failure, not a success" "$RC" "1"
unset RF_STUB_NO_REPACK

# verify: avbtool's verdict is the exit status.
run_script python3 -m rootforge.core.cli boot verify "$SANDBOX/boot.img"
assert_eq "boot verify passes when avbtool does" "$RC" "0"
assert_contains "and says so" "$OUT" "AVB verification passed"
assert_contains "avbtool was asked about this image" "$(cat "$RF_STUB_LOG")" "avbtool verify_image --image $SANDBOX/boot.img"
export RF_STUB_AVB_RC=1
run_script python3 -m rootforge.core.cli boot verify "$SANDBOX/boot.img"
assert_eq "boot verify fails when avbtool does" "$RC" "1"
assert_contains "and says why it may have failed" "$OUT" "failed or image is unsigned"
unset RF_STUB_AVB_RC

# a missing tool is a clear error, not a traceback
run_script env PATH="$SANDBOX/no-such-dir" "$(command -v python3)" -m rootforge.core.cli boot inspect "$SANDBOX/boot.img"
assert_eq "a missing magiskboot is reported" "$RC" "1"
assert_contains "naming the tool" "$OUT" "magiskboot not found"
assert_not_contains "without a traceback" "$OUT" "Traceback"
run_script env PATH="$SANDBOX/no-such-dir" "$(command -v python3)" -m rootforge.core.cli boot verify "$SANDBOX/boot.img"
assert_eq "a missing avbtool is reported" "$RC" "1"
assert_contains "naming the tool" "$OUT" "avbtool not found"
drop_sandbox

section "setup_rooted_avd.sh — name validation and the cached Magisk APK"

new_sandbox
mkdir -p "$SANDBOX/avdbin"
cat > "$SANDBOX/avdbin/emulator" <<'EOS'
#!/usr/bin/env bash
printf 'emulator %s\n' "$*" >> "$RF_STUB_LOG"
EOS
cat > "$SANDBOX/avdbin/avdmanager" <<'EOS'
#!/usr/bin/env bash
printf 'avdmanager %s\n' "$*" >> "$RF_STUB_LOG"
EOS
chmod +x "$SANDBOX/avdbin"/*
export PATH="$SANDBOX/avdbin:$STUB_DIR:$ORIGINAL_PATH"

# Regression: `create` validated --name and `boot` did not, so
# `boot --name '../../escaped'` read MODE from a .conf outside the profile
# directory and handed the name straight to `emulator`.
mkdir -p "$ROOTFORGE_HOME/avd-profiles"
printf 'MODE=rooted\n' > "$HOME/escaped.conf"
run_script bash "$BIN_DIR/setup_rooted_avd.sh" boot --name '../../escaped'
assert_eq "boot rejects a traversing name, as create already did" "$RC" "1"
assert_contains "the rejection explains what the name becomes" "$OUT" "profile filename"
assert_not_contains "a rejected name never reaches the emulator" "$(cat "$RF_STUB_LOG")" "emulator"

run_script bash "$BIN_DIR/setup_rooted_avd.sh" create --name '../../escaped' --mode unrooted
assert_eq "create still rejects it too" "$RC" "1"

# An ordinary name must still boot.
run_script bash "$BIN_DIR/setup_rooted_avd.sh" boot --name testavd
assert_eq "an ordinary name still boots" "$RC" "0"
assert_contains "the ordinary name reaches the emulator" "$(cat "$RF_STUB_LOG")" "emulator -avd testavd"

new_sandbox
# Regression: a truncated cached magisk.apk was reused, unzip extracted
# nothing, and the script died with "magiskinit not present ... pick an ABI
# Magisk actually ships lib/<abi>/ for" — sending the user to change an ABI
# that was never wrong.
#
# Driving the real `create --mode rooted` for this needs the SDK tools
# stubbed; grepping the script for the fix would test the grep, which is the
# shape tests/check-tests.sh exists to catch.
mkdir -p "$SANDBOX/avdbin" "$ROOTFORGE_HOME/bin" "$HOME/.android/avd/rooty.avd"
head -c 1024 /dev/zero > "$HOME/.android/avd/rooty.avd/ramdisk.img"
for t in avdmanager sdkmanager emulator magiskboot; do
  cat > "$SANDBOX/avdbin/$t" <<'EOS'
#!/usr/bin/env bash
printf '%s %s\n' "$(basename "$0")" "$*" >> "$RF_STUB_LOG"
exit 0
EOS
  chmod +x "$SANDBOX/avdbin/$t"
done
cat > "$SANDBOX/avdbin/curl" <<'EOS'
#!/usr/bin/env bash
printf 'curl %s\n' "$*" >> "$RF_STUB_LOG"
OUT=""; prev=""
for a in "$@"; do [ "$prev" = "-o" ] && OUT="$a"; prev="$a"; done
if [ -n "$OUT" ]; then head -c "${RF_STUB_DL_BYTES:-12000000}" /dev/zero > "$OUT"; exit 0; fi
echo '{"assets":[{"name":"Magisk-v27.apk","browser_download_url":"https://example.invalid/m.apk"}]}'
EOS
chmod +x "$SANDBOX/avdbin/curl"
export PATH="$SANDBOX/avdbin:$STUB_DIR:$ORIGINAL_PATH"

# A truncated cache entry is now treated as absent, so it is refetched — and
# the refetched file (zeros, not a zip) is caught by the readability check
# rather than being blamed on the ABI.
printf 'PK\003\004TRUNCATED' > "$ROOTFORGE_HOME/bin/magisk.apk"
run_script bash "$BIN_DIR/setup_rooted_avd.sh" create --name rooty --mode rooted --abi x86_64
assert_eq "a bad Magisk APK fails the rooted create" "$RC" "1"
assert_contains "the failure names the APK, not the ABI" "$OUT" "not a readable zip"
assert_not_contains "the failure does not blame the ABI" "$OUT" "pick an ABI"
assert_contains "the truncated cache entry was refetched, not reused" "$(cat "$RF_STUB_LOG")" "curl"
drop_sandbox

section "rootforge avd — the wrapped path end to end"

new_sandbox
export PYTHONPATH="$LIB_DIR"
mkdir -p "$SANDBOX/avdbin"
cat > "$SANDBOX/avdbin/emulator" <<'EOS'
#!/usr/bin/env bash
printf 'emulator %s\n' "$*" >> "$RF_STUB_LOG"
EOS
cat > "$SANDBOX/avdbin/avdmanager" <<'EOS'
#!/usr/bin/env bash
printf 'avdmanager %s\n' "$*" >> "$RF_STUB_LOG"
EOS
chmod +x "$SANDBOX/avdbin"/*
export PATH="$SANDBOX/avdbin:$STUB_DIR:$ORIGINAL_PATH"

run_script python3 -m rootforge.core.cli avd list
assert_eq "CLI avd list succeeds" "$RC" "0"
assert_contains "avd list names the profile directory" "$OUT" "RootForge profiles"

run_script python3 -m rootforge.core.cli avd boot --name testavd
assert_eq "CLI avd boot succeeds" "$RC" "0"
assert_contains "CLI avd boot reaches the emulator" "$(cat "$RF_STUB_LOG")" "emulator -avd testavd"

run_script python3 -m rootforge.core.cli avd boot --name '../../escaped'
assert_eq "a traversing name is rejected by the CLI" "$RC" "2"
assert_contains "the CLI rejection names the profile directory" "$OUT" "avd-profiles"

run_script python3 -m rootforge.core.cli avd create --name t --mode semirooted
assert_eq "an unknown mode is rejected" "$RC" "2"
assert_contains "the valid modes are listed" "$OUT" "unrooted"

run_script python3 -m rootforge.core.cli avd create --name t --mode rooted --abi mips
assert_eq "an unknown ABI is rejected" "$RC" "2"

# A rooted AVD cannot be built from a Play image. Refusing here means the
# error arrives before sdkmanager downloads a multi-GB system image.
# Reset the recorded calls: this block has already run `avd boot`, and the
# assertion below is about what this one command did.
: > "$RF_STUB_LOG"
run_script python3 -m rootforge.core.cli avd create --name t --mode rooted \
  --tag google_apis_playstore
assert_eq "rooted on a Play image is refused" "$RC" "1"
assert_contains "the refusal explains why" "$OUT" "signed and locked"
assert_not_contains "the refusal downloads nothing" "$(cat "$RF_STUB_LOG")" "avdmanager"

run_script python3 -m rootforge.core.cli avd create --nam t --mode rooted
assert_eq "an abbreviated flag is rejected, not guessed" "$RC" "2"

run_script python3 -m rootforge.core.cli avd
assert_eq "a missing avd subcommand is rejected" "$RC" "2"
drop_sandbox

section "lint_module.sh"

new_sandbox
MOD="$SANDBOX/mod"
mkdir -p "$MOD/common" "$MOD/META-INF/com/google/android"
printf 'id=testmod\nname=T\nversion=1\nversionCode=1\nauthor=a\ndescription=d\n' > "$MOD/module.prop"
touch "$MOD/META-INF/com/google/android/update-binary" "$MOD/META-INF/com/google/android/updater-script"
printf '#!/system/bin/sh\r\necho hi\r\n' > "$MOD/common/nested.sh"
run_script bash "$BIN_DIR/lint_module.sh" "$MOD"
# Regression: the CRLF sweep stopped at -maxdepth 1 and never looked in
# common/, where module scripts most often live.
assert_contains "CRLF in a subdirectory is caught" "$OUT" "CRLF line endings in nested.sh"
assert_eq "CRLF is a blocking failure" "$RC" "1"

new_sandbox
MOD="$SANDBOX/mod"
mkdir -p "$MOD/META-INF/com/google/android"
# A duplicated field used to SIGPIPE grep through `head -1`, which pipefail
# turned into a mid-lint abort.
printf 'id=testmod\nid=dupe\nname=T\nversion=1\nversionCode=1\nauthor=a\ndescription=d\n' > "$MOD/module.prop"
touch "$MOD/META-INF/com/google/android/update-binary" "$MOD/META-INF/com/google/android/updater-script"
run_script bash "$BIN_DIR/lint_module.sh" "$MOD"
assert_contains "duplicate field does not abort the lint" "$OUT" "PASS"
# Regression: a clean directory target printed PASS and then exited 1. The
# cleanup trap's last command is `[[ -n "$WORKDIR" ]]`, which is false when
# no temp dir was created — i.e. for every directory target — and a bash EXIT
# trap whose last command fails overrides the script's own `exit 0`. The
# script was therefore unusable as a CI gate for the case its usage line
# lists first, while printing PASS the whole time.
assert_eq "a clean directory target exits 0, not just prints PASS" "$RC" "0"

new_sandbox
MOD="$SANDBOX/mod"
mkdir -p "$MOD/META-INF/com/google/android"
printf 'id=zipmod\nname=Z\nversion=1\nversionCode=1\nauthor=a\ndescription=d\n' > "$MOD/module.prop"
touch "$MOD/META-INF/com/google/android/update-binary" "$MOD/META-INF/com/google/android/updater-script"
( cd "$MOD" && zip -qr "$SANDBOX/mod.zip" . )
run_script bash "$BIN_DIR/lint_module.sh" "$SANDBOX/mod.zip"
assert_eq "a clean zip target still exits 0" "$RC" "0"
assert_contains "a clean zip target passes" "$OUT" "PASS"

new_sandbox
MOD="$SANDBOX/mod"
mkdir -p "$MOD"
printf 'id=incomplete\n' > "$MOD/module.prop"
run_script bash "$BIN_DIR/lint_module.sh" "$MOD"
assert_eq "a genuinely broken module still exits 1" "$RC" "1"
drop_sandbox

}

# ---------------------------------------------------------------------------
# Python tests
# ---------------------------------------------------------------------------

test_python() {
  section "Python unit tests"
  if PYTHONPATH="$LIB_DIR" python3 -m unittest discover -s "$REPO_ROOT/tests" -p 'test_*.py' -v 2>&1 | tail -40; then
    pass "python unittest suite"
  else
    fail "python unittest suite"
  fi
}

case "$WHICH" in
  shell)  test_shell ;;
  python) test_python ;;
  all)    test_shell; test_python ;;
  *) echo "Usage: tests/run-tests.sh [shell|python|all]" >&2; exit 2 ;;
esac

printf '\n----------------------------------------\n'
printf '%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ] || exit 1
