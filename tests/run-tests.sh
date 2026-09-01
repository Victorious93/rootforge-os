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
  # harden_kernel.sh writes a sysctl drop-in and runs `sysctl --system`.
  # Without this the suite modifies the machine it runs on — verified: the
  # drop-in was present on the host, timestamped by the last run. Set here
  # rather than per-section so a future test cannot forget it.
  export ROOTFORGE_SYSCTL_FILE="$SANDBOX/sysctl-dropin.conf"
  unset RF_STUB_ADB_DEVICES RF_STUB_FASTBOOT_DEVICES RF_STUB_SLOT \
        RF_STUB_FLASH_RC RF_STUB_GETVAR_ALL RF_STUB_ADB_SHELL_OUT \
        RF_STUB_FLASH_FAIL_ON_CALL RF_STUB_ADB_SHELL_RC \
        ROOTFORGE_ASSUME_YES 2>/dev/null || true
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

section "flash_patched_boot.sh — argument parsing"

new_sandbox
mkdir -p "$SANDBOX"
head -c 1024 /dev/zero > "$SANDBOX/boot.img"

# Regression: `shift 2 || true` left the image path in "$@", so a
# single-argument run set SERIAL to the image and ran `fastboot -s boot.img`.
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img"
assert_not_contains "single arg does not become a serial" "$(cat "$RF_STUB_LOG")" "-s $SANDBOX/boot.img"
assert_contains "single arg flashes boot" "$(cat "$RF_STUB_LOG")" "flash boot"

# Regression: PARTITION="${2:-boot}" swallowed the flag, so this flashed a
# partition literally named "--both-slots" and never mirrored.
new_sandbox
head -c 1024 /dev/zero > "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1
export RF_STUB_SLOT=a
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img" --both-slots
assert_not_contains "flag is not read as a partition" "$(cat "$RF_STUB_LOG")" "flash --both-slots"
assert_contains "--both-slots switches to the other slot" "$(cat "$RF_STUB_LOG")" "--set-active=b"
assert_contains "--both-slots restores the original slot" "$(cat "$RF_STUB_LOG")" "--set-active=a"

new_sandbox
head -c 1024 /dev/zero > "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img" init_boot SERIAL9
assert_contains "explicit partition honored" "$(cat "$RF_STUB_LOG")" "flash init_boot"
assert_contains "explicit serial honored" "$(cat "$RF_STUB_LOG")" "-s SERIAL9"

new_sandbox
head -c 1024 /dev/zero > "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img" system
assert_eq "unsupported partition rejected" "$RC" "1"
assert_not_contains "unsupported partition never flashes" "$(cat "$RF_STUB_LOG")" "flash system"

new_sandbox
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/missing.img"
assert_eq "missing image rejected" "$RC" "1"

# The safety gate itself: with no terminal and no explicit opt-in, nothing
# may be written.
new_sandbox
head -c 1024 /dev/zero > "$SANDBOX/boot.img"
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img"
assert_eq "unconfirmed flash aborts" "$RC" "1"
assert_not_contains "unconfirmed flash writes nothing" "$(cat "$RF_STUB_LOG")" "flash boot"
drop_sandbox

section "flash_patched_boot.sh — slot restore on mirror failure"

new_sandbox
head -c 1024 /dev/zero > "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1 RF_STUB_SLOT=a
export RF_STUB_FLASH_FAIL_ON_CALL=2   # active slot succeeds, mirror fails
run_script bash "$BIN_DIR/flash_patched_boot.sh" "$SANDBOX/boot.img" --both-slots
# Even when the mirrored write fails, the device must not be left booting
# the slot that was only half-written.
assert_contains "failed mirror still restores the active slot" "$(cat "$RF_STUB_LOG")" "--set-active=a"
assert_eq "failed flash reports failure" "$RC" "1"
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

section "backup_partitions.sh + restore_partitions.sh"

new_sandbox
# Regression: with nothing attached this used to select MODE=adb and then
# report every partition as unfetchable.
export RF_STUB_ADB_DEVICES=""
export RF_STUB_FASTBOOT_DEVICES=""
run_script bash "$BIN_DIR/backup_partitions.sh" testdev
assert_eq "no device -> backup fails" "$RC" "1"
assert_contains "no device -> explains why" "$OUT" "No device found"
drop_sandbox

new_sandbox
# Restore must verify checksums before it flashes anything.
BACKUP="$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000"
mkdir -p "$BACKUP"
printf 'realboot' > "$BACKUP/boot.img"
( cd "$BACKUP" && sha256sum boot.img > SHA256SUMS )
printf 'testdev backup 20240101_000000\n' > "$BACKUP/manifest.txt"

export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "matching checksum restores" "$RC" "0"
assert_contains "matching checksum flashes" "$(cat "$RF_STUB_LOG")" "flash boot"

# Now corrupt the image and confirm the restore refuses.
new_sandbox
BACKUP="$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000"
mkdir -p "$BACKUP"
printf 'realboot' > "$BACKUP/boot.img"
( cd "$BACKUP" && sha256sum boot.img > SHA256SUMS )
printf 'CORRUPTED' > "$BACKUP/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "checksum mismatch aborts restore" "$RC" "1"
assert_contains "checksum mismatch explains itself" "$OUT" "CHECKSUM MISMATCH"
assert_not_contains "checksum mismatch flashes nothing" "$(cat "$RF_STUB_LOG")" "flash boot"

# A failed flash must not be reported as a completed restore.
new_sandbox
BACKUP="$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000"
mkdir -p "$BACKUP"
printf 'realboot' > "$BACKUP/boot.img"
( cd "$BACKUP" && sha256sum boot.img > SHA256SUMS )
export ROOTFORGE_ASSUME_YES=1 RF_STUB_FLASH_RC=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev 20240101_000000
assert_eq "failed flash -> non-zero exit" "$RC" "1"
assert_contains "failed flash -> says so" "$OUT" "RESTORE INCOMPLETE"
drop_sandbox

# Regression: the codename and timestamp are interpolated straight into a
# path under $ROOTFORGE_HOME/devices/, and nothing validated them. Before the
# guard, `backup_partitions.sh '../../escaped'` wrote to $HOME/escaped, and
# `restore_partitions.sh testdev '../../../../evil'` read every .img from an
# arbitrary directory and flashed it — the SHA256SUMS gate degrades to a
# warning when the directory has no SHA256SUMS, so the write went ahead.
new_sandbox
export RF_STUB_ADB_DEVICES="X1\tdevice"
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/backup_partitions.sh" '../../escaped'
assert_eq "a traversing codename aborts the backup" "$RC" "1"
assert_contains "the codename abort explains itself" "$OUT" "Invalid device codename"
if [ -e "$SANDBOX/home/escaped" ]; then
  fail "a traversing codename writes nothing outside devices/" "$SANDBOX/home/escaped exists"
else
  pass "a traversing codename writes nothing outside devices/"
fi

new_sandbox
# The escape target is a real directory holding a real image, so the only
# thing standing between it and the device is the guard.
# ../../../../evil resolves out of devices/ to $HOME/evil, four levels up
# from $ROOTFORGE_HOME/devices/<codename>/backups. The backups directory has
# to exist for the traversal to resolve, which it does for any device the
# user has ever backed up.
mkdir -p "$ROOTFORGE_HOME/devices/testdev/backups" "$HOME/evil"
printf 'attacker' > "$HOME/evil/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" testdev "../../../../evil"
assert_eq "a traversing timestamp aborts the restore" "$RC" "1"
assert_contains "the timestamp abort explains itself" "$OUT" "Invalid backup timestamp"
assert_not_contains "a traversing timestamp flashes nothing" "$(cat "$RF_STUB_LOG")" "flash"

new_sandbox
# A codename with a separator but no '..' is just as much an escape.
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" 'testdev/../../elsewhere'
assert_eq "a codename containing a separator is rejected" "$RC" "1"

new_sandbox
# The guard must not reject the codenames people actually use. Real device
# codenames carry digits, underscores and hyphens.
BACKUP="$ROOTFORGE_HOME/devices/oriole_5g-2/backups/20240101_000000"
mkdir -p "$BACKUP"
printf 'realboot' > "$BACKUP/boot.img"
( cd "$BACKUP" && sha256sum boot.img > SHA256SUMS )
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/restore_partitions.sh" oriole_5g-2 20240101_000000
assert_eq "an ordinary codename still restores" "$RC" "0"
assert_contains "an ordinary codename still flashes" "$(cat "$RF_STUB_LOG")" "flash boot"
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
printf 'img' > "$ROOTFORGE_HOME/kernelsu-work/boot-ksu-patched-old-20240101_000000.img"
sleep 0.01
printf 'img' > "$ROOTFORGE_HOME/kernelsu-work/boot-ksu-patched-new-20240102_000000.img"
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --flash
assert_eq "unconfirmed --flash aborts" "$RC" "1"
assert_not_contains "unconfirmed --flash writes nothing" "$(cat "$RF_STUB_LOG")" "flash boot"

new_sandbox
mkdir -p "$ROOTFORGE_HOME/kernelsu-work"
printf 'img' > "$ROOTFORGE_HOME/kernelsu-work/boot-ksu-patched-old-20240101_000000.img"
sleep 0.01
printf 'img' > "$ROOTFORGE_HOME/kernelsu-work/boot-ksu-patched-new-20240102_000000.img"
export ROOTFORGE_ASSUME_YES=1
run_script bash "$BIN_DIR/kernelsu_patch_boot.sh" --flash
assert_contains "confirmed --flash picks the newest image" "$(cat "$RF_STUB_LOG")" "boot-ksu-patched-new"

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
mkdir -p "$WGP"
# Regression: addresses were `10.66.66.$((RANDOM % 200 + 10))` with no check
# against peers already issued — a birthday collision (~63% by the 20th
# peer). A duplicate AllowedIPs doesn't fail loudly, it silently breaks
# routing for one of them. Replay the allocator over many peers and assert
# every address is distinct.
alloc_octet() {
  local c
  for c in $(seq 10 250); do
    grep -rqs "^Address = 10\.66\.66\.${c}/32\b" "$WGP" || { printf '%s' "$c"; return 0; }
  done
  return 1
}
for i in $(seq 1 25); do
  oct="$(alloc_octet)"
  mkdir -p "$WGP/peer$i"
  printf 'Address = 10.66.66.%s/32\n' "$oct" > "$WGP/peer$i/peer.conf"
done
TOTAL="$(grep -rh '^Address = ' "$WGP" | wc -l | tr -d ' ')"
UNIQUE="$(grep -rh '^Address = ' "$WGP" | sort -u | wc -l | tr -d ' ')"
assert_eq "25 peers get 25 distinct addresses" "$UNIQUE" "$TOTAL"
assert_eq "allocation starts at .10" "$(head -1 "$WGP/peer1/peer.conf")" "Address = 10.66.66.10/32"

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
assert_contains "an unknown target lists the real ones" "$OUT" "expected magisk, kernelsu or xposed"
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
head -c 1024 /dev/zero > "$SANDBOX/boot.img"
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
head -c 1024 /dev/zero > "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img" \
  --partition init_boot --serial SERIAL9
assert_contains "CLI honors --partition" "$(cat "$RF_STUB_LOG")" "flash init_boot"
assert_contains "CLI honors --serial" "$(cat "$RF_STUB_LOG")" "-s SERIAL9"

new_sandbox
export PYTHONPATH="$LIB_DIR"
head -c 1024 /dev/zero > "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1 RF_STUB_SLOT=a
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img" --both-slots
assert_contains "CLI --both-slots mirrors to the other slot" "$(cat "$RF_STUB_LOG")" "--set-active=b"
assert_contains "CLI --both-slots restores the original slot" "$(cat "$RF_STUB_LOG")" "--set-active=a"
assert_not_contains "CLI --both-slots is never read as a partition" \
  "$(cat "$RF_STUB_LOG")" "flash --both-slots"

# argparse prefix matching accepted --both-slot for --both-slots until
# allow_abbrev=False was set on every parser (subparsers do not inherit it).
# A near-miss flag must be an error, not a silent guess at what was meant.
new_sandbox
export PYTHONPATH="$LIB_DIR"
head -c 1024 /dev/zero > "$SANDBOX/boot.img"
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
head -c 1024 /dev/zero > "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img" --partition system
assert_eq "an unsupported partition is rejected" "$RC" "2"
assert_contains "the supported partitions are listed" "$OUT" "init_boot"
assert_not_contains "an unsupported partition is never written" "$(cat "$RF_STUB_LOG")" "flash system"

new_sandbox
export PYTHONPATH="$LIB_DIR"
head -c 1024 /dev/zero > "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img" --serial
assert_eq "a missing option value is rejected" "$RC" "2"
assert_contains "the missing value names its option" "$OUT" "--serial"

# The wrapper must pass a real failure through rather than reporting success.
new_sandbox
export PYTHONPATH="$LIB_DIR"
head -c 1024 /dev/zero > "$SANDBOX/boot.img"
export ROOTFORGE_ASSUME_YES=1 RF_STUB_FLASH_RC=1
run_script python3 -m rootforge.core.cli flash boot "$SANDBOX/boot.img"
assert_eq "a failed flash propagates its exit code" "$RC" "1"

# --- backup / restore through the CLI ---

new_sandbox
export PYTHONPATH="$LIB_DIR"
BACKUP="$ROOTFORGE_HOME/devices/testdev/backups/20240101_000000"
mkdir -p "$BACKUP"
printf 'realboot' > "$BACKUP/boot.img"
( cd "$BACKUP" && sha256sum boot.img > SHA256SUMS )
export ROOTFORGE_ASSUME_YES=1

run_script python3 -m rootforge.core.cli backup list testdev
assert_eq "backup list succeeds" "$RC" "0"
assert_contains "backup list shows the stored backup" "$OUT" "20240101_000000"
assert_eq "backup list touches no device" "$(wc -l < "$RF_STUB_LOG")" "0"

run_script python3 -m rootforge.core.cli backup restore testdev 20240101_000000
assert_eq "backup restore succeeds" "$RC" "0"
assert_contains "backup restore flashes the stored image" "$(cat "$RF_STUB_LOG")" "flash boot"

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

section "00_bootstrap_distro.sh — where the workspace lands"

new_sandbox
# Regression, and the worst one found so far: ROOTFORGE_HOME was
# "${ROOTFORGE_HOME:-$HOME/rootforge}", and sudo sets HOME to /root. So the
# workspace resolved to /root/rootforge, and the next use of it was
#
#   sudo -u "$TARGET_USER" mkdir -p "$ROOTFORGE_HOME"/{devices,modules,...}
#
# — the unprivileged user creating directories inside /root, which is mode
# 0700. It fails, set -e ends the run, and by then apt upgrade, the whole
# cross-toolchain, GNOME and the udev rules are already installed. The
# bootstrap could not finish on an ordinary machine.
#
# --check resolves the paths and exits without touching anything, which is
# what makes this testable at all.
OUT="$(cd "$SANDBOX" && HOME=/root SUDO_USER=root ROOTFORGE_HOME= \
  bash "$BIN_DIR/00_bootstrap_distro.sh" --check 2>&1)"; RC=$?
assert_eq "--check succeeds without root" "$RC" "0"
assert_contains "the workspace follows the sudo user, not \$HOME" "$OUT" "target user:     root"

# The real shape of the bug: HOME says /root while the invoking user is
# someone else. The workspace must follow the user, not HOME. The getent stub
# supplies that user, so the assertion does not depend on who runs the suite —
# as root, the ambient user's home *is* /root and the test would prove nothing.
mkdir -p "$SANDBOX/devhome"
export RF_STUB_PASSWD="dev:x:1000:1000::$SANDBOX/devhome:/bin/bash"
OUT="$(cd "$SANDBOX" && HOME=/root SUDO_USER=dev ROOTFORGE_HOME= \
  bash "$BIN_DIR/00_bootstrap_distro.sh" --check 2>&1)"; RC=$?
assert_eq "--check resolves for a non-root sudo user" "$RC" "0"
assert_contains "the workspace is under the invoking user's home" "$OUT" "ROOTFORGE_HOME:  $SANDBOX/devhome/rootforge"
assert_not_contains "the workspace is never placed under /root by accident" "$OUT" "ROOTFORGE_HOME:  /root/rootforge"
assert_contains "the SDK follows the workspace" "$OUT" "SDK_ROOT:        $SANDBOX/devhome/rootforge/android-sdk"

# A user getent does not know at all is a hard stop, not a guess.
OUT="$(cd "$SANDBOX" && HOME=/root SUDO_USER=ghost ROOTFORGE_HOME= \
  bash "$BIN_DIR/00_bootstrap_distro.sh" --check 2>&1)"; RC=$?
assert_eq "an unknown user is refused" "$RC" "1"
assert_contains "the refusal names getent" "$OUT" "getent"
unset RF_STUB_PASSWD

# `${SUDO_USER:-$USER}` was itself an unbound-variable crash under set -u
# wherever USER is not exported — cron, `sh -c`, some CI runners.
OUT="$(cd "$SANDBOX" && env -u USER -u SUDO_USER -u ROOTFORGE_HOME HOME=/root \
  bash "$BIN_DIR/00_bootstrap_distro.sh" --check 2>&1)"; RC=$?
assert_eq "an unexported USER is not a crash" "$RC" "0"
assert_not_contains "an unexported USER is not a crash (message)" "$OUT" "unbound variable"

# rootforge-firstboot.service runs this script, and a systemd unit with no
# User= is documented to get no $HOME. The old first line was
# `${ROOTFORGE_HOME:-$HOME/rootforge}` under set -u, so an empty environment
# ended the script at line 18 with "HOME: unbound variable" — and with
# Type=oneshot a failed ExecStart skips ExecStartPost, so the completion
# sentinel was never written and first boot failed the same way every time.
OUT="$(cd "$SANDBOX" && env -i /bin/bash "$BIN_DIR/00_bootstrap_distro.sh" --check 2>&1)"; RC=$?
assert_eq "an empty environment is survivable" "$RC" "0"
assert_not_contains "an empty environment is not an unbound-variable crash" "$OUT" "unbound variable"
assert_contains "an empty environment still resolves a workspace" "$OUT" "ROOTFORGE_HOME:"

# A system account's home is /nonexistent; a 15 GB SDK must not be aimed there.
# ROOTFORGE_HOME is cleared because the sandbox exports it, and an explicit
# value is exactly what suppresses this guard.
OUT="$(cd "$SANDBOX" && SUDO_USER=nobody ROOTFORGE_HOME= bash "$BIN_DIR/00_bootstrap_distro.sh" --check 2>&1)"; RC=$?
assert_eq "a system account is refused" "$RC" "1"
assert_contains "the refusal names the missing home" "$OUT" "does not exist"

OUT="$(cd "$SANDBOX" && SUDO_USER=nobody ROOTFORGE_HOME=/srv/rf \
  bash "$BIN_DIR/00_bootstrap_distro.sh" --check 2>&1)"; RC=$?
assert_eq "an explicit ROOTFORGE_HOME overrides the refusal" "$RC" "0"
assert_contains "an explicit ROOTFORGE_HOME is honored" "$OUT" "ROOTFORGE_HOME:  /srv/rf"

# Regression: `[[ "${1:-}" == "--headless" ]] && HEADLESS=1` ignored anything
# else, so a typo installed the full desktop on a build server with no sign
# the flag had been dropped.
OUT="$(cd "$SANDBOX" && SUDO_USER=root bash "$BIN_DIR/00_bootstrap_distro.sh" --headles --check 2>&1)"; RC=$?
assert_eq "a typo'd --headless is rejected" "$RC" "1"
assert_contains "the typo'd flag is named" "$OUT" "Unknown option: --headles"

OUT="$(cd "$SANDBOX" && SUDO_USER=root bash "$BIN_DIR/00_bootstrap_distro.sh" --headless --check 2>&1)"; RC=$?
assert_contains "--headless is actually reflected" "$OUT" "desktop install: skipped"
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
