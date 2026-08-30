#!/usr/bin/env bash
# RootForge OS — chroot hook safety checks
# Victorious Framework | Origin Source Labs
#
# live-build runs config/hooks/*.hook.chroot with /bin/sh — dash on Debian —
# under `set -e`. dash has no `set -o pipefail`, so a pipeline's exit status
# is its LAST command's. That makes one specific mistake invisible and
# expensive: `curl <url> | sh` reports success when the download failed,
# because sh reading an empty stdin exits 0. The hook then carries on and the
# ISO ships without the tool, with nothing but a line in a very long build log
# to say so. An ISO build takes about an hour of CI time to find that out.
#
# The same two download rules are applied to the runtime scripts under
# usr/local/bin and termux: setup_terminal.sh carried the identical
# `curl -sS <url> | sh` bug, so checking only config/hooks/ would have
# missed it.
#
# These checks are static — they read the files, they don't run them.
#
# Usage: tests/check-hooks.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

FAIL=0
CHECKED=0

problem() {
  printf '  [FAIL] %s\n' "$1"
  printf '         %s\n' "$2"
  FAIL=1
}

for hook in config/hooks/*.hook.chroot; do
  [ -f "$hook" ] || continue
  CHECKED=$((CHECKED + 1))
  name="$(basename "$hook")"

  # 1. Every hook must abort on error; live-build does not do it for them.
  if ! grep -qE '^set -e' "$hook"; then
    problem "$name: no 'set -e'" \
            "a failing command mid-hook would be ignored and the build would continue"
  fi

  # `grep -n` prefixes each hit with "<line>:", so a comment line no longer
  # starts with '#' — strip the prefix before deciding whether to skip it.
  drop_comments() { grep -vE '^[0-9]+:[[:space:]]*#'; }

  # 2. Piping a download into an interpreter that EXECUTES ITS STDIN.
  #    `| sh`, `| bash`, or a bare `| python3` run whatever arrives, so a
  #    failed download runs nothing and reports success, and an unauthenticated
  #    HTTP error body gets executed. `| python3 -c '<script>'` is a different
  #    thing — the script is fixed and stdin is only data — so it is not
  #    flagged: if the download fails there, the script errors and `set -e`
  #    catches it.
  while IFS= read -r line; do
    problem "$name: pipes a download into an interpreter that executes stdin" \
            "${line} — download to a file, verify it is non-empty, then run it"
  done < <(grep -nE '(curl|wget)[^|]*\|[[:space:]]*(sudo[[:space:]]+)?(sh|bash)([[:space:]]|$)' "$hook" \
             | drop_comments || true)
  while IFS= read -r line; do
    case "$line" in
      *' -c'*|*' -m'*) continue ;;   # fixed script, stdin is only data
    esac
    problem "$name: pipes a download into an interpreter that executes stdin" \
            "${line} — download to a file, verify it is non-empty, then run it"
  done < <(grep -nE '(curl|wget)[^|]*\|[[:space:]]*(sudo[[:space:]]+)?python3?([[:space:]]|$)' "$hook" \
             | drop_comments || true)

  # 3. curl without -f exits 0 on an HTTP error, having written the error
  #    page to the output. Every fetch here wants -f (--fail).
  while IFS= read -r line; do
    case "$line" in
      *' -f'*|*'--fail'*) continue ;;
    esac
    problem "$name: curl without -f/--fail" \
            "${line} — without it curl exits 0 on a 404 and writes the error page"
  done < <(grep -nE '(^|[|;&(]|\$\()[[:space:]]*curl[[:space:]]' "$hook" | drop_comments || true)

  # 4. A report line that FABRICATES success. 0062 shipped
  #    "payload-dumper-go installed: ok" for a binary that was not there,
  #    because its version probe ended in `|| echo 'ok'`. A bare `|| true`
  #    only suppresses noise from a tool that exits non-zero by design (e.g.
  #    magiskboot printing usage), so it is not flagged.
  while IFS= read -r line; do
    problem "$name: an install-report line fabricates success" \
            "${line} — this prints a success string whether or not the tool exists"
  done < <(grep -nE 'installed:.*\|\|[[:space:]]*echo' "$hook" | drop_comments || true)
done

# The same two download rules apply to the runtime scripts. setup_terminal.sh
# carried the identical `curl -sS <url> | sh` bug the hooks did, so scoping
# these checks to config/hooks/ alone would have missed it.
for script in config/includes.chroot/usr/local/bin/*.sh termux/*.sh; do
  [ -f "$script" ] || continue
  CHECKED=$((CHECKED + 1))
  name="$(basename "$script")"
  drop_comments() { grep -vE '^[0-9]+:[[:space:]]*#'; }

  # Unlike the hooks (dash, no pipefail available), these scripts run under
  # bash and mostly set `pipefail`. `curl -f ... | sh` is then genuinely
  # safe from a silent skip: curl's failure becomes the pipeline's. Flag it
  # only when that protection is missing — no pipefail in the file, or no
  # -f on the curl, which is exactly the shape setup_terminal.sh had.
  HAS_PIPEFAIL=0
  grep -qE '^[[:space:]]*set .*pipefail' "$script" && HAS_PIPEFAIL=1
  while IFS= read -r line; do
    if [ "$HAS_PIPEFAIL" -eq 1 ]; then
      case "$line" in
        *' -f'*|*'--fail'*) continue ;;
      esac
    fi
    problem "$name: pipes a download into an interpreter that executes stdin" \
            "${line} — download to a file, verify it is non-empty, then run it"
  done < <(grep -nE '(curl|wget)[^|]*\|[[:space:]]*(sudo[[:space:]]+)?(sh|bash)([[:space:]]|$)' "$script" \
             | drop_comments || true)

  while IFS= read -r line; do
    case "$line" in
      # -K reads its request from a config file rather than fetching a URL,
      # so --fail is not applicable there.
      *' -K'*) continue ;;
      *' -f'*|*'--fail'*) continue ;;
    esac
    problem "$name: curl without -f/--fail" \
            "${line} — without it curl exits 0 on a 404 and writes the error page"
  done < <(grep -nE '(^|[|;&(]|\$\()[[:space:]]*curl[[:space:]]' "$script" | drop_comments || true)
done

echo "Checked $CHECKED file(s)."
if [ "$FAIL" -eq 0 ]; then
  echo "==> hooks OK"
else
  echo "==> hook checks FAILED" >&2
fi
exit "$FAIL"
