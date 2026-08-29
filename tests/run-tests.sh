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
