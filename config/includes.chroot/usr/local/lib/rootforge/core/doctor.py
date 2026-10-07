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
from typing import Callable, List, Optional

from rootforge.core.log import Logger

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


def optional_check(func: Callable[[], "CheckResult"]) -> Callable[[], "CheckResult"]:
    """Declare a check as informational: its failure is a warning, not an error.

    Severity is a property of the check, declared here, rather than something
    only a *successful* run can report. A check that raises while probing must
    keep its declared severity: a crashed required check is a failure.
    """
    func.optional = True  # type: ignore[attr-defined]
    return func


def _is_optional(check: Callable[[], "CheckResult"]) -> bool:
    return bool(getattr(check, "optional", False))


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


@optional_check
def check_claude_code() -> CheckResult:
    return _check_tool(
        "claude", "not installed — run setup_ai_tools.sh to install Claude Code", required=False
    )


@optional_check
def check_ollama_binary() -> CheckResult:
    return _check_tool(
        "ollama", "not installed — run setup_ai_tools.sh to install Ollama", required=False
    )


@optional_check
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


@optional_check
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


def check_jq() -> CheckResult:
    # flash_patched_boot.sh, backup_partitions.sh and restore_partitions.sh read
    # the device/backup checks as JSON and refuse to write without jq.
    return _check_tool("jq", "not found — apt install jq (the flash/backup/restore scripts need it)")


@optional_check
def check_pyyaml() -> CheckResult:
    try:
        import yaml  # noqa: F401
    except ImportError:
        return CheckResult(
            "pyyaml",
            False,
            "not importable — apt install python3-yaml (`rootforge config` and configured backup partitions need it)",
            required=False,
        )
    return CheckResult("pyyaml", True, "importable", required=False)


def check_sha256sum() -> CheckResult:
    # backup_partitions.sh writes and restore_partitions.sh verifies
    # SHA256SUMS; without this the restore integrity gate cannot run.
    return _check_tool("sha256sum", "not found — apt install coreutils")


@optional_check
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


@optional_check
def check_docker() -> CheckResult:
    return _check_tool(
        "docker", "not installed — needed only by build_matrix.sh", required=False
    )


@optional_check
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
    check_jq,
    check_pyyaml,
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
    results: List[CheckResult] = []
    for check in CHECKS:
        try:
            results.append(check())
        except Exception as exc:  # a broken diagnostic must not hide the rest
            name = check.__name__.removeprefix("check_").replace("_", "-")
            results.append(
                CheckResult(
                    name, False, f"check raised {type(exc).__name__}: {exc}",
                    required=not _is_optional(check),
                )
            )
    return results


def _open_logger() -> Optional[Logger]:
    # doctor is what tells you the logs directory is unusable, so failing to
    # open the audit log must not stop it reporting that.
    try:
        return Logger("doctor", echo=False)
    except OSError:
        return None


def run_doctor(as_json: bool = False, quiet: bool = False, strict: bool = False) -> int:
    logger = _open_logger()
    if logger:
        logger.info("doctor started")
    results = run_checks()
    required_failures = sum(1 for r in results if r.status == "fail")
    warnings = sum(1 for r in results if r.status == "warn")
    if logger:
        for result in results:
            log_event = logger.info if result.ok else (logger.error if result.required else logger.warn)
            log_event("check", check=result.name, ok=result.ok, required=result.required, detail=result.detail)
        logger.info(
            "doctor finished", required_failures=required_failures, warnings=warnings,
            log_path=str(logger.path),
        )

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
    elif not quiet:
        print("RootForge doctor")
        print("=================")
        for result in results:
            status = "OK  " if result.ok else ("FAIL" if result.required else "WARN")
            print(f"[{status}] {result.name:<20} {result.detail}")
    return 1 if required_failures or (strict and warnings) else 0
