"""rootforge doctor — environment sanity checks.

Each check is independent and side-effect free: it inspects the running
system (PATH, a local HTTP port, disk space) and reports what it found.
Nothing here modifies state — that's the job of the setup scripts a check
points at when something is missing (setup_ai_tools.sh, `brain init`, ...).
"""
from __future__ import annotations

import os
import shutil
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, List

OLLAMA_HOST = os.environ.get("OLLAMA_HOST", "http://127.0.0.1:11434")
SECOND_BRAIN_VAULT = Path(
    os.environ.get("ROOTFORGE_BRAIN_VAULT", str(Path.home() / "second-brain"))
)
MIN_FREE_GIB = 2.0


@dataclass
class CheckResult:
    name: str
    ok: bool
    detail: str
    required: bool = True


def _check_tool(name: str, hint: str, required: bool = True) -> CheckResult:
    path = shutil.which(name)
    if path:
        return CheckResult(name, True, path, required)
    return CheckResult(name, False, hint, required)


def check_python3() -> CheckResult:
    return _check_tool("python3", "not found — this should never happen on RootForge OS")


def check_git() -> CheckResult:
    return _check_tool("git", "not found — apt install git")


def check_adb() -> CheckResult:
    return _check_tool("adb", "not found — reinstall the adb package")


def check_fastboot() -> CheckResult:
    return _check_tool("fastboot", "not found — reinstall the fastboot package")


def check_claude_code() -> CheckResult:
    return _check_tool(
        "claude", "not installed — run setup_ai_tools.sh to install Claude Code", required=False
    )


def check_ollama_binary() -> CheckResult:
    return _check_tool(
        "ollama", "not installed — run setup_ai_tools.sh to install Ollama", required=False
    )


def check_ollama_reachable() -> CheckResult:
    if shutil.which("ollama") is None:
        return CheckResult("ollama-server", False, "skipped — ollama not installed", required=False)
    try:
        with urllib.request.urlopen(f"{OLLAMA_HOST}/api/tags", timeout=2) as resp:
            if resp.status == 200:
                return CheckResult("ollama-server", True, f"reachable at {OLLAMA_HOST}", required=False)
            detail = f"unexpected HTTP {resp.status} from {OLLAMA_HOST}"
    except Exception as exc:  # noqa: BLE001 - any failure just means "not reachable right now"
        detail = f"not reachable at {OLLAMA_HOST} ({exc.__class__.__name__}) — start with: ollama serve"
    return CheckResult("ollama-server", False, detail, required=False)


def check_second_brain_vault() -> CheckResult:
    if SECOND_BRAIN_VAULT.is_dir():
        return CheckResult("second-brain-vault", True, str(SECOND_BRAIN_VAULT), required=False)
    return CheckResult(
        "second-brain-vault",
        False,
        f"{SECOND_BRAIN_VAULT} not initialized — run: brain init",
        required=False,
    )


def check_disk_space() -> CheckResult:
    usage = shutil.disk_usage(str(Path.home()))
    free_gib = usage.free / (1024**3)
    ok = free_gib >= MIN_FREE_GIB
    detail = f"{free_gib:.1f} GiB free on {Path.home()}"
    if not ok:
        detail += f" — below {MIN_FREE_GIB:.0f} GiB, builds and model pulls may fail"
    return CheckResult("disk-space", ok, detail)


CHECKS: List[Callable[[], CheckResult]] = [
    check_python3,
    check_git,
    check_adb,
    check_fastboot,
    check_disk_space,
    check_claude_code,
    check_ollama_binary,
    check_ollama_reachable,
    check_second_brain_vault,
]


def run_doctor() -> int:
    print("RootForge doctor")
    print("=================")

    required_failures = 0
    for check in CHECKS:
        result = check()
        status = "OK  " if result.ok else ("FAIL" if result.required else "WARN")
        print(f"[{status}] {result.name:<20} {result.detail}")
        if not result.ok and result.required:
            required_failures += 1

    print()
    if required_failures:
        print(f"{required_failures} required check(s) failed.")
    else:
        print("All required checks passed.")
    return 1 if required_failures else 0
