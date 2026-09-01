#!/usr/bin/env bash
# RootForge OS — checks on the test suite itself
# Victorious Framework | Origin Source Labs
#
# Two failures found in this suite that no amount of running it would reveal,
# because a suite that is wrong in these ways still passes:
#
#   1. A section that never invokes the script it names. The USBGuard section
#      built a policy file itself and grepped it, so it asserted things about
#      grep and would have passed whether or not harden_system.sh had the
#      guard at all. Three earlier rounds hit the same shape.
#
#   2. A script that writes to an absolute system path with no seam. The
#      harden_kernel.sh section ran the real script, which does
#      `sudo tee /etc/sysctl.d/...` and `sudo sysctl --system` — so the suite
#      modified the machine running it. Verified on a real host, not
#      theorised.
#
# Running new checks against the pre-fix tree catches tests that pass for the
# wrong reason. It does not catch tests that were already wrong. This does.
#
# Usage: tests/check-tests.sh

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

FAIL=0
SUITE="tests/run-tests.sh"

# --- 1. every section must actually invoke something -----------------------
#
# "Invoke" means run_script, a direct "$BIN_DIR/..." call, the CLI module, or
# a build script under test. A section that only greps files it wrote itself
# is testing the test.
echo "  checking that every section invokes the code it names"
python3 - "$SUITE" <<'PY'
import re
import sys

path = sys.argv[1]
lines = open(path).read().split("\n")

# A section can exercise the code under test by running a script, or — for the
# common.sh sections — by sourcing it and calling its functions directly. Both
# are real; only "the test wrote a file and then grepped it" is not.
# The launcher variables ($BUILD, $CHROOT_LAUNCHER, $DESKTOP) name a script
# the block then runs. Matched loosely on purpose: a new one that this pattern
# misses shows up as a failure with a clear message, which is the right way to
# find out about it.
INVOKES = re.compile(
    r'run_script\b|\$BIN_DIR/|rootforge\.core\.cli'
    r'|\$[A-Z_]*(BUILD|LAUNCHER|DESKTOP|CHROOT|SCRIPT)[A-Z_]*'
    r'|\brf_[a-z_]+\b'
)
ASSERTS = re.compile(r'^\s*(assert_\w+|pass|fail)\b')

# Granularity is the block, not the section. The USBGuard section that
# prompted this check DID invoke harden_system.sh — in its first block, to
# reject a typo'd flag. Its second block, the one that built a policy file
# and grepped it, invoked nothing. Checking per section would have passed it.
# Blocks are delimited by new_sandbox, which is also where isolation resets.
STATIC_OK = {
    "Termux variants — build flavours",
}

blocks = []
total_blocks = 0
section = None
current = None


def close():
    global total_blocks
    if current is None:
        return
    total_blocks += 1
    if current["asserts"] and not current["invokes"]:
        blocks.append(current)


for n, line in enumerate(lines, 1):
    m = re.match(r'^section "(.*)"\s*$', line)
    if m:
        close()
        section = m.group(1)
        current = {"section": section, "line": n, "invokes": False, "asserts": 0}
        continue
    if current is None:
        continue
    if re.match(r'^\s*new_sandbox\s*$', line):
        close()
        current = {"section": section, "line": n, "invokes": False, "asserts": 0}
        continue
    if INVOKES.search(line):
        current["invokes"] = True
    if ASSERTS.match(line):
        current["asserts"] += 1
close()

bad = [b for b in blocks if b["section"] not in STATIC_OK]
for b in bad:
    print(f"  FAIL {path}:{b['line']}: a block in section \"{b['section']}\" makes "
          f"{b['asserts']} assertion(s) but never invokes the code it names",
          file=sys.stderr)

print(f"  {total_blocks} block(s) checked.")
sys.exit(1 if bad else 0)
PY
[ $? -eq 0 ] || FAIL=1

# --- 2. no bare absolute system path as a write destination ----------------
#
# A destination outside $ROOTFORGE_HOME must come from a ${ROOTFORGE_*:-...}
# seam, so the suite can point it at a sandbox. Without that, testing the
# script means changing the machine.
echo "  checking that system write destinations have a seam"
SEAMLESS="$(grep -nE '^[[:space:]]*[A-Z_]+="/(etc|usr|var|boot|lib)/' \
  config/includes.chroot/usr/local/bin/*.sh 2>/dev/null \
  | grep -v 'ROOTFORGE_[A-Z_]*:-' || true)"
if [ -n "$SEAMLESS" ]; then
  echo "  FAIL a write destination outside \$ROOTFORGE_HOME has no ROOTFORGE_* seam:" >&2
  printf '    %s\n' "$SEAMLESS" >&2
  echo "    Give it one (see tests/README.md) so the suite can redirect it." >&2
  FAIL=1
fi

if [ "$FAIL" -eq 0 ]; then
  echo "==> test-suite checks OK"
else
  echo "==> test-suite checks FAILED" >&2
fi
exit "$FAIL"
