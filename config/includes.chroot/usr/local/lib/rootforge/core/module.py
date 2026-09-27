"""rootforge.core.module — module scaffold/lint/build wrapper.

Wraps `new_module_scaffold.sh`, `lint_module.sh`, and
`build_magisk_module.sh` as subprocesses rather than reimplementing
them — the actual file generation, zipping, and adb push/install logic
stays in those scripts.
"""
from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

VALID_TARGETS = ("magisk", "kernelsu", "apatch", "zygisk", "xposed")


def _script_path(name: str) -> Path:
    # This file lives at .../usr/local/lib/rootforge/core/module.py in both
    # a real install and a repo checkout — parents[3] is usr/local in
    # either case, so the same relative lookup finds the sibling script
    # both ways (see rootforge.core.backup for the same pattern).
    candidate = Path(__file__).resolve().parents[3] / "bin" / name
    if candidate.is_file():
        return candidate
    found = shutil.which(name)
    if found:
        return Path(found)
    raise FileNotFoundError(
        f"{name} not found next to this module ({candidate}) or on PATH — "
        "check your RootForge install."
    )


def cmd_create(module_id: str, display_name: str, target: str = "magisk") -> int:
    if target not in VALID_TARGETS:
        print(f"Unknown target '{target}' — expected one of: {', '.join(VALID_TARGETS)}")
        return 1
    try:
        script = _script_path("new_module_scaffold.sh")
    except FileNotFoundError as exc:
        print(exc)
        return 1
    result = subprocess.run([str(script), module_id, display_name, target])
    return result.returncode


def cmd_lint(path: str, json_output: bool = False) -> int:
    try:
        script = _script_path("lint_module.sh")
    except FileNotFoundError as exc:
        print(exc)
        return 1
    cmd = [str(script)]
    if json_output:
        cmd.append("--json")
    cmd.append(path)
    result = subprocess.run(cmd)
    return result.returncode


def cmd_build(module_id: str, install: bool = False, framework: str = "magisk") -> int:
    try:
        script = _script_path("build_magisk_module.sh")
    except FileNotFoundError as exc:
        print(exc)
        return 1
    cmd = [str(script), module_id]
    if install:
        cmd.append("--install")
    cmd += ["--framework", framework]
    result = subprocess.run(cmd)
    return result.returncode
