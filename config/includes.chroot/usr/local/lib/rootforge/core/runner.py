"""Locating and invoking the standalone usr/local/bin scripts.

P2 of docs/IMPLEMENTATION_PLAN.md wraps the existing scripts behind the
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
# layout under config/includes.chroot, so one relative fallback covers
# running straight out of a working tree.
INSTALLED_BIN = Path("/usr/local/bin")


class ScriptNotFound(RuntimeError):
    """Raised when a wrapped script isn't where it should be."""


def find_script(name: str) -> Path:
    """Locate a wrapped script, preferring the installed location.

    Falls back to a path relative to this file so `rootforge` works from a
    git checkout, and then to PATH so an operator who put the scripts
    somewhere else is not stuck.
    """
    installed = INSTALLED_BIN / name
    if installed.is_file():
        return installed

    # .../usr/local/lib/rootforge/core/runner.py -> .../usr/local/bin/<name>
    checkout = Path(__file__).resolve().parents[3] / "bin" / name
    if checkout.is_file():
        return checkout

    on_path = shutil.which(name)
    if on_path:
        return Path(on_path)

    raise ScriptNotFound(
        f"{name} not found in {INSTALLED_BIN}, alongside this package, or on PATH.\n"
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
