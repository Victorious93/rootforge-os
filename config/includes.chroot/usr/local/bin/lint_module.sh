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
# Usage: ./lint_module.sh <module_dir_or_zip>

set -euo pipefail

TARGET="${1:?Usage: lint_module.sh <module_dir_or_zip>}"

WORKDIR=""
cleanup() { [[ -n "$WORKDIR" ]] && rm -rf "$WORKDIR"; }
trap cleanup EXIT

ISSUES=0
note() { echo "  [FAIL] $*"; ISSUES=$((ISSUES+1)); }
warn() { echo "  [WARN] $*"; }
ok()   { echo "  [ OK ] $*"; }

if [[ -f "$TARGET" && "$TARGET" == *.zip ]]; then
  echo "== Checking zip structure: $TARGET =="
  # (An unused ZIP_ROOT_ENTRIES listing used to be computed here; the check
  # below is what actually decides zip-root correctness.)
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
  echo "== Checking module directory: $MODULE_DIR =="
fi

# module.prop required fields
PROP="$MODULE_DIR/module.prop"
if [[ -f "$PROP" ]]; then
  for field in id name version versionCode author description; do
    if grep -q "^${field}=" "$PROP"; then
      # `grep ... | head -1` lets head close the pipe first, and a duplicated
      # field in module.prop then kills grep with SIGPIPE — which pipefail
      # turns into a mid-lint abort. -m1 stops grep itself instead.
      VAL="$(grep -m1 "^${field}=" "$PROP" | cut -d= -f2-)"
      [[ -z "$VAL" ]] && note "module.prop: '$field' is present but empty" || ok "module.prop: $field=$VAL"
    else
      note "module.prop: missing required field '$field'"
    fi
  done
  MOD_ID="$(grep -m1 '^id=' "$PROP" | cut -d= -f2- || true)"
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
echo ""
echo "== Checking line endings =="
while IFS= read -r -d '' f; do
  # `file` only reports CRLF for content it classifies as text; a script with
  # any binary-looking byte slips through. Test for a CR before a newline
  # directly, which is the condition that actually breaks the shebang parse.
  if LC_ALL=C grep -qU $'\r$' "$f"; then
    note "CRLF line endings in $(basename "$f") — convert with: sed -i 's/\\r$//' '$f' (or dos2unix)"
  else
    ok "LF line endings: $(basename "$f")"
  fi
# Modules routinely keep scripts in subdirectories (common/, system/bin/),
# and a CRLF shebang breaks there exactly as it does at the top level, so
# don't stop at -maxdepth 1.
done < <(find "$MODULE_DIR" -type f -name "*.sh" -print0 2>/dev/null)

# Shebang sanity check
echo ""
echo "== Checking shebangs =="
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

# Executable bit on update-binary (matters when zipping from a filesystem that lost it)
UB="$MODULE_DIR/META-INF/com/google/android/update-binary"
if [[ -f "$UB" && ! -x "$UB" ]]; then
  warn "update-binary is not executable in this extracted copy — verify the zip preserves the exec bit (zip -X can strip it on some platforms)"
fi

echo ""
if [[ $ISSUES -eq 0 ]]; then
  echo "PASS — no blocking issues found."
  exit 0
else
  echo "FAIL — $ISSUES blocking issue(s) found. Fix before pushing to a device."
  exit 1
fi

# Victorious Framework
