"""rootforge doctor — environment sanity checks.

Each check is independent and side-effect free: it inspects the running
system (PATH, a local HTTP port, disk space) and reports what it found.
Nothing here modifies state — that's the job of the setup scripts a check
points at when something is missing (setup_ai_tools.sh, `brain init`, ...).
"""
from __future__ import annotations

import json
import os
import shutil
import urllib.error
import urllib.request
from dataclasses import dataclass, asdict
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

    @property
    def status(self) -> str:
        if self.ok:
            return "ok"
        return "fail" if self.required else "warn"

    def as_dict(self) -> dict:
        data = asdict(self)
        data["status"] = self.status
        return data


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


def check_unzip() -> CheckResult:
    # extract_ota.sh and lint_module.sh both shell out to unzip.
    return _check_tool("unzip", "not found — apt install unzip")


def check_zip() -> CheckResult:
    # build_magisk_module.sh packages modules with zip.
    return _check_tool("zip", "not found — apt install zip")


def check_curl() -> CheckResult:
    return _check_tool("curl", "not found — apt install curl")


def check_sha256sum() -> CheckResult:
    # backup_partitions.sh writes and restore_partitions.sh verifies
    # SHA256SUMS; without this the restore integrity gate cannot run.
    return _check_tool("sha256sum", "not found — apt install coreutils")


def check_magiskboot() -> CheckResult:
    if shutil.which("magiskboot"):
        return CheckResult("magiskboot", True, str(shutil.which("magiskboot")), required=False)
    fallback = Path(os.environ.get("ROOTFORGE_HOME", str(Path.home() / "rootforge"))) / "bin" / "magiskboot"
    if fallback.is_file() and os.access(fallback, os.X_OK):
        return CheckResult("magiskboot", True, str(fallback), required=False)
    return CheckResult(
        "magiskboot",
        False,
        "not built yet — run 00_bootstrap_distro.sh (needed by kernelsu_patch_boot.sh)",
        required=False,
    )


def check_docker() -> CheckResult:
    return _check_tool(
        "docker", "not installed — needed only by build_matrix.sh", required=False
    )


def check_adb_devices() -> CheckResult:
    """Report attached devices, and say why an attached one isn't usable.

    Deliberately not a required check — a workstation with nothing plugged in
    is a perfectly healthy RootForge install.
    """
    if shutil.which("adb") is None:
        return CheckResult("adb-devices", False, "skipped — adb not installed", required=False)

    # Imported lazily so `doctor` still runs if this module is ever trimmed
    # out of a minimal install.
    from rootforge.core.devices import list_devices

    devices = list_devices()
    if not devices:
        return CheckResult("adb-devices", True, "no devices attached (not an error)", required=False)

    usable = [d for d in devices if d.usable]
    blocked = [d for d in devices if not d.usable]
    if blocked:
        detail = ", ".join(f"{d.serial} {d.state} ({d.note})" for d in blocked)
        return CheckResult(
            "adb-devices",
            False,
            f"{len(usable)} usable, {len(blocked)} blocked: {detail}",
            required=False,
        )
    summary = ", ".join(f"{d.serial} [{d.mode}]" for d in usable)
    return CheckResult("adb-devices", True, f"{len(usable)} usable: {summary}", required=False)


def check_rootforge_home() -> CheckResult:
    """The scripts write logs/backups here, so it needs to be writable."""
    home = Path(os.environ.get("ROOTFORGE_HOME", str(Path.home() / "rootforge")))
    if not home.exists():
        return CheckResult(
            "rootforge-home",
            True,
            f"{home} does not exist yet — it is created on first use",
            required=False,
        )
    if not home.is_dir():
        return CheckResult("rootforge-home", False, f"{home} exists but is not a directory")
    if not os.access(home, os.W_OK):
        return CheckResult(
            "rootforge-home",
            False,
            f"{home} is not writable by this user — logs and backups will fail",
        )
    return CheckResult("rootforge-home", True, str(home))


CHECKS: List[Callable[[], CheckResult]] = [
    check_python3,
    check_git,
    check_adb,
    check_fastboot,
    check_curl,
    check_unzip,
    check_zip,
    check_sha256sum,
    check_disk_space,
    check_rootforge_home,
    check_adb_devices,
    check_magiskboot,
    check_docker,
    check_claude_code,
    check_ollama_binary,
    check_ollama_reachable,
    check_second_brain_vault,
]


def run_checks() -> List[CheckResult]:
    results = []
    for check in CHECKS:
        try:
            results.append(check())
        except Exception as exc:  # noqa: BLE001 - a broken check must not hide the rest
            # A check that raises would otherwise take down the whole
            # diagnostic run, which is precisely when you need the other
            # checks' output most.
            results.append(
                CheckResult(
                    getattr(check, "__name__", "unknown").removeprefix("check_"),
                    False,
                    f"check raised {exc.__class__.__name__}: {exc}",
                    required=False,
                )
            )
    return results


def run_doctor(as_json: bool = False, quiet: bool = False, strict: bool = False) -> int:
    results = run_checks()
    required_failures = sum(1 for r in results if r.status == "fail")
    warnings = sum(1 for r in results if r.status == "warn")

    if as_json:
        print(
            json.dumps(
                {
                    "checks": [r.as_dict() for r in results],
                    "failed": required_failures,
                    "warnings": warnings,
                },
                indent=2,
            )
        )
    else:
        print("RootForge doctor")
        print("=================")
        labels = {"ok": "OK  ", "fail": "FAIL", "warn": "WARN"}
        for result in results:
            if quiet and result.ok:
                continue
            print(f"[{labels[result.status]}] {result.name:<20} {result.detail}")

        print()
        if required_failures:
            print(f"{required_failures} required check(s) failed, {warnings} warning(s).")
        elif warnings:
            print(f"All required checks passed, with {warnings} warning(s).")
        else:
            print("All checks passed.")

    if required_failures:
        return 1
    if strict and warnings:
        return 1
    return 0
