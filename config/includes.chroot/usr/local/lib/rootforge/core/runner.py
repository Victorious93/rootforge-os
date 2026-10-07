"""Locating and invoking the standalone usr/local/bin scripts.

P2 of docs/archive/IMPLEMENTATION_PLAN_P0-P3_2026-10-07.md wraps the existing scripts behind the
`rootforge` CLI rather than reimplementing them: their behavior is proven and
the shell is where the device work actually happens. What the wrapper adds is
the argument handling, and that is not cosmetic. Every sweep in this
repository's bug history re-found the same four shell-specific failures:

    unguarded "$2"          -> raw "unbound variable" under set -u
    no catch-all case arm   -> a typo'd flag runs with defaults, silently
    exit codes that lie     -> success reported after total failure
    pipefail aborts         -> the script dies before its own error message

argparse gives all four for free. So validation happens here, in Python, and
only a checked argument list reaches the shell.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import List, Optional, Sequence

# Where the scripts live once installed. The repo checkout mirrors this
# layout under config/includes.chroot, so the sibling lookup in find_script()
# covers running straight out of a working tree.
INSTALLED_BIN = Path("/usr/local/bin")


class ScriptNotFound(RuntimeError):
    """Raised when a wrapped script isn't where it should be."""


def find_script(name: str) -> Path:
    """Locate a wrapped script, preferring the one shipped with this package.

    Order: the script next to this package, then the installed location, then
    PATH. The sibling comes first so the Python code and the shell it wraps
    are always the same revision: a checkout run on a machine that also has a
    system-installed RootForge used to pick up the installed (older or newer)
    script, and the scripts source `sh/common.sh` relative to themselves, so a
    mixed pair could disagree about the CLI contract. On an installed system
    the sibling *is* /usr/local/bin, so nothing changes there.
    """
    # .../usr/local/lib/rootforge/core/runner.py -> .../usr/local/bin/<name>
    sibling = Path(__file__).resolve().parents[3] / "bin" / name
    if sibling.is_file():
        return sibling

    installed = INSTALLED_BIN / name
    if installed.is_file():
        return installed

    on_path = shutil.which(name)
    if on_path:
        return Path(on_path)

    raise ScriptNotFound(
        f"{name} not found alongside this package ({sibling.parent}), in {INSTALLED_BIN}, or on PATH.\n"
        f"       This usually means a partial install — reinstall the rootforge scripts."
    )


def run_script(
    name: str,
    args: Sequence[str],
    *,
    env: Optional[dict] = None,
    capture: bool = False,
) -> subprocess.CompletedProcess:
    """Run a wrapped script and return its result.

    The script's exit code is passed through untouched. Several of these
    scripts use a non-zero exit to report a finding rather than a failure
    (`rootforge doctor` does the same), so translating them here would throw
    away information the caller wants.
    """
    script = find_script(name)
    argv: List[str] = [str(script), *args]

    run_env = os.environ.copy()
    if env:
        run_env.update(env)

    # Not capturing by default: these scripts are interactive. They prompt for
    # typed confirmation before destructive work, and rf_confirm reads from
    # /dev/tty precisely so that gate stays visible. Swallowing their output
    # would reintroduce the hang that fix was for.
    if capture:
        return subprocess.run(
            argv, env=run_env, capture_output=True, text=True, check=False
        )
    return subprocess.run(argv, env=run_env, check=False)


def exec_script(name: str, args: Sequence[str], *, env: Optional[dict] = None) -> int:
    """Run a script and return its exit code, reporting a missing script cleanly."""
    try:
        proc = run_script(name, args, env=env)
    except ScriptNotFound as exc:
        print(f"rootforge: error: {exc}", file=sys.stderr)
        return 127
    return proc.returncode
