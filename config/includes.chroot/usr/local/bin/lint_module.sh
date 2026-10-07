#!/usr/bin/env bash
# RootForge OS — module linter
# Victorious Framework
#
# Catches the two most common "why won't this install" module bugs before
# you push to a device: a nested top-level folder in the zip (module.prop
# must be at zip root), and CRLF line endings in shell scripts (breaks the
# shebang parse on-device). Also checks required module.prop fields and
# META-INF boilerplate presence.
#
# Works against either a module source directory or an already-built zip.
#
# Usage: ./lint_module.sh [--json] <module_dir_or_zip>
#   --json  emit machine-readable findings on stdout instead of the human
#           report (for CI consumption); still exits 0/1 the same way.

set -euo pipefail

JSON_MODE=0
TARGET=""
for arg in "$@"; do
  case "$arg" in
    --json) JSON_MODE=1 ;;
    *) TARGET="$arg" ;;
  esac
done
[[ -n "$TARGET" ]] || { echo "Usage: lint_module.sh [--json] <module_dir_or_zip>" >&2; exit 1; }

WORKDIR=""
# [Certain] under `set -e`, an EXIT trap whose last command evaluates false
# overrides the script's own explicit exit code with 1 — reproduced
# directly: `set -euo pipefail; trap 'cmd_that_returns_false' EXIT; exit 0`
# exits 1, not 0. WORKDIR is empty whenever TARGET is a directory (not a
# zip), so `[[ -n "$WORKDIR" ]]` is false here on every directory lint —
# meaning a passing `lint_module.sh some_dir` has always actually exited 1.
# The trailing `true` keeps cleanup's own exit status from leaking out.
cleanup() { [[ -n "$WORKDIR" ]] && rm -rf "$WORKDIR"; true; }
trap cleanup EXIT

ISSUES=0
FINDINGS=()  # each entry: "<level>\t<message>", level one of fail/warn/ok

note() {
  # [Certain] `[[ cond ]] && echo ...` as a standalone statement returns
  # the *test's* exit status when cond is false — under `set -e` that
  # aborts the whole script right there the first time JSON_MODE
  # suppresses output, before FINDINGS/ISSUES ever get updated.
  # Reproduced directly: `set -e; false && echo hi; echo unreachable`
  # never prints "unreachable". `if`/`fi` sidesteps it: an `if` whose
  # condition is false and has no `else` always exits 0 itself.
  if [[ $JSON_MODE -eq 0 ]]; then
    echo "  [FAIL] $*"
  fi
  FINDINGS+=("fail	$*")
  ISSUES=$((ISSUES+1))
}
warn() {
  if [[ $JSON_MODE -eq 0 ]]; then
    echo "  [WARN] $*"
  fi
  FINDINGS+=("warn	$*")
}
ok() {
  if [[ $JSON_MODE -eq 0 ]]; then
    echo "  [ OK ] $*"
  fi
  FINDINGS+=("ok	$*")
}
say() {
  if [[ $JSON_MODE -eq 0 ]]; then
    echo "$@"
  fi
}

if [[ -f "$TARGET" && "$TARGET" == *.zip ]]; then
  say "== Checking zip structure: $TARGET =="
  ZIP_ROOT_ENTRIES="$(unzip -l "$TARGET" | awk 'NR>3 {print $4}' | grep -v '^$' | grep -v '/.*/' || true)"
  if unzip -l "$TARGET" | awk 'NR>3{print $4}' | grep -q '^module.prop$'; then
    ok "module.prop found at zip root"
  else
    NESTED="$(unzip -l "$TARGET" | grep -c 'module.prop' || true)"
    if [[ "$NESTED" -gt 0 ]]; then
      note "module.prop exists but is NOT at zip root — this is the #1 install failure. Rezip with 'cd module_dir && zip -r out.zip .', not 'zip -r out.zip module_dir'."
    else
      note "module.prop not found anywhere in the zip"
    fi
  fi
  WORKDIR="$(mktemp -d)"
  unzip -o -q "$TARGET" -d "$WORKDIR"
  MODULE_DIR="$WORKDIR"
else
  MODULE_DIR="$TARGET"
  [[ -d "$MODULE_DIR" ]] || { echo "Not a directory or zip: $TARGET" >&2; exit 1; }
  say "== Checking module directory: $MODULE_DIR =="
fi

# module.prop required fields
PROP="$MODULE_DIR/module.prop"
if [[ -f "$PROP" ]]; then
  for field in id name version versionCode author description; do
    if grep -q "^${field}=" "$PROP"; then
      VAL="$(grep "^${field}=" "$PROP" | head -1 | cut -d= -f2-)"
      [[ -z "$VAL" ]] && note "module.prop: '$field' is present but empty" || ok "module.prop: $field=$VAL"
    else
      note "module.prop: missing required field '$field'"
    fi
  done
  MOD_ID="$(grep '^id=' "$PROP" | cut -d= -f2- || true)"
  if [[ -n "$MOD_ID" ]] && [[ ! "$MOD_ID" =~ ^[a-zA-Z][a-zA-Z0-9_.-]*$ ]]; then
    note "module.prop: id '$MOD_ID' contains characters outside [a-zA-Z0-9_.-] or doesn't start with a letter — Magisk requires a restricted id format"
  fi
else
  note "No module.prop found"
fi

# META-INF boilerplate
if [[ -f "$MODULE_DIR/META-INF/com/google/android/update-binary" ]]; then
  ok "META-INF/com/google/android/update-binary present"
else
  note "Missing META-INF/com/google/android/update-binary"
fi
if [[ -f "$MODULE_DIR/META-INF/com/google/android/updater-script" ]]; then
  ok "META-INF/com/google/android/updater-script present"
else
  note "Missing META-INF/com/google/android/updater-script"
fi

# CRLF line ending check across all shell scripts
say ""
say "== Checking line endings =="
while IFS= read -r -d '' f; do
  if file "$f" | grep -qi 'CRLF'; then
    note "CRLF line endings in $(basename "$f") — convert with: sed -i 's/\\r$//' '$f' (or dos2unix)"
  else
    ok "LF line endings: $(basename "$f")"
  fi
done < <(find "$MODULE_DIR" -maxdepth 1 -type f \( -name "*.sh" -o -name "customize.sh" \) -print0 2>/dev/null)

# Shebang sanity check
say ""
say "== Checking shebangs =="
for script in customize.sh post-fs-data.sh service.sh uninstall.sh; do
  f="$MODULE_DIR/$script"
  [[ -f "$f" ]] || continue
  FIRST_LINE="$(head -1 "$f")"
  if [[ "$FIRST_LINE" == "#!"* ]]; then
    ok "$script has a shebang: $FIRST_LINE"
  else
    warn "$script has no shebang line — Magisk assumes /system/bin/sh but explicit is safer"
  fi
done

# Shell-syntax check — on-device lifecycle scripts run under Android's
# /system/bin/sh (a minimal shell, not bash), so this checks with `sh -n`
# (dash locally) rather than bash -n, closer to the real runtime even
# though it can't catch every toybox/mksh-vs-dash quirk.
say ""
say "== Checking shell syntax =="
for script in customize.sh post-fs-data.sh service.sh uninstall.sh; do
  f="$MODULE_DIR/$script"
  [[ -f "$f" ]] || continue
  if sh -n "$f" 2>/tmp/lint_module_syntax_err; then
    ok "$script parses cleanly under sh -n"
  else
    note "$script has a shell syntax error: $(tr '\n' ' ' < /tmp/lint_module_syntax_err)"
  fi
  rm -f /tmp/lint_module_syntax_err
done

# Native Zygisk lib presence — only applies to modules that ship a zygisk/
# directory at all (most modules don't); when one exists, at least one
# arch's .so needs to actually be there or the module silently no-ops on
# every device that loads it.
if [[ -d "$MODULE_DIR/zygisk" ]]; then
  say ""
  say "== Checking Zygisk native libraries =="
  ZYGISK_LIBS_FOUND=0
  for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    if [[ -f "$MODULE_DIR/zygisk/${abi}.so" ]]; then
      ok "zygisk/${abi}.so present"
      ZYGISK_LIBS_FOUND=$((ZYGISK_LIBS_FOUND+1))
    fi
  done
  [[ $ZYGISK_LIBS_FOUND -eq 0 ]] && note "zygisk/ directory exists but contains no <abi>.so (expected e.g. zygisk/arm64-v8a.so) — the module will silently do nothing on every device"
fi

# Executable bit on update-binary (matters when zipping from a filesystem that lost it)
UB="$MODULE_DIR/META-INF/com/google/android/update-binary"
if [[ -f "$UB" && ! -x "$UB" ]]; then
  warn "update-binary is not executable in this extracted copy — verify the zip preserves the exec bit (zip -X can strip it on some platforms)"
fi

if [[ $JSON_MODE -eq 1 ]]; then
  printf '%s\n' "${FINDINGS[@]}" | python3 -c "
import json, sys

findings = []
for line in sys.stdin:
    line = line.rstrip('\n')
    if not line:
        continue
    level, message = line.split('\t', 1)
    findings.append({'level': level, 'message': message})

issues = sum(1 for f in findings if f['level'] == 'fail')
print(json.dumps({'pass': issues == 0, 'issue_count': issues, 'findings': findings}, indent=2))
"
else
  say ""
  if [[ $ISSUES -eq 0 ]]; then
    say "PASS — no blocking issues found."
  else
    say "FAIL — $ISSUES blocking issue(s) found. Fix before pushing to a device."
  fi
fi

if [[ $ISSUES -eq 0 ]]; then
  exit 0
else
  exit 1
fi

# Victorious Framework
