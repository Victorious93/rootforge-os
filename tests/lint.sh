#!/usr/bin/env bash
# RootForge OS — lint sweep
# Victorious Framework | Origin Source Labs
#
# The single definition of "what lint means here", called by both
# `make lint` and the shellcheck job in .github/workflows/lint.yml so the
# two can't drift apart.
#
# Scripts are selected by shebang rather than by a '*.sh' filename glob: the
# `rootforge` and `brain` entrypoints have no extension and so were never
# checked at all when selection was by name.
#
# Usage: tests/lint.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

command -v shellcheck >/dev/null 2>&1 || {
  echo "shellcheck not installed — apt install shellcheck" >&2
  exit 1
}

FAIL=0

# Directories that hold shell we own. config/hooks is handled separately
# below because live-build hooks are POSIX sh with a .hook.chroot suffix.
SCAN_DIRS=(config/includes.chroot termux tests)

echo "==> shellcheck: bash"
while IFS= read -r -d '' f; do
  head -1 "$f" | grep -qE '^#!.*\bbash\b' || continue
  # -x follows `source`d files, so common.sh's helpers are resolved rather
  # than reported as undefined in every script that sources it.
  shellcheck -S error -x "$f" || { echo "   ^ $f" >&2; FAIL=1; }
done < <(find "${SCAN_DIRS[@]}" -type f -print0)
shellcheck -S error auto/build || { echo "   ^ auto/build" >&2; FAIL=1; }

echo "==> shellcheck: POSIX sh"
while IFS= read -r -d '' f; do
  head -1 "$f" | grep -qE '^#!/bin/sh' || continue
  shellcheck -S error -s sh "$f" || { echo "   ^ $f" >&2; FAIL=1; }
done < <(find config/includes.chroot termux -type f -print0)

echo "==> shellcheck: live-build hooks"
while IFS= read -r -d '' f; do
  shellcheck -S error -s sh "$f" || { echo "   ^ $f" >&2; FAIL=1; }
done < <(find config/hooks -name '*.hook.chroot' -print0)
shellcheck -S error -s sh auto/config || { echo "   ^ auto/config" >&2; FAIL=1; }

echo "==> python: byte-compile"
if python3 -m compileall -q config/includes.chroot/usr/local/lib; then
  :
else
  FAIL=1
fi
# compileall leaves __pycache__ behind in the tree that gets copied into the
# squashfs verbatim; don't ship it.
find config/includes.chroot -name __pycache__ -type d -exec rm -rf {} + 2>/dev/null || true

if [ "$FAIL" -eq 0 ]; then
  echo "==> lint clean"
else
  echo "==> lint FAILED" >&2
fi
exit "$FAIL"
