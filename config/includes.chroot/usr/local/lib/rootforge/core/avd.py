"""rootforge.core.avd — AVD lifecycle CLI: create/list/start/stop/snapshot.

create/list/start wrap setup_rooted_avd.sh's own create/list/boot
subcommands as subprocesses — the real AVD-creation and Magisk-ramdisk-
patch logic stays there. stop/snapshot are genuinely new (the underlying
script has no equivalent): implemented via the emulator's standard
`adb emu` console commands (`avd name`, `kill`, `avd snapshot ...`),
which every running AVD instance answers regardless of how it was
created. [Likely] the exact snapshot console command shape (`avd
snapshot save|load|list|delete <name>`) matches AOSP's documented
emulator console reference — not independently re-verified against a
running emulator from this environment, so if a snapshot action ever
errors unexpectedly, check that reference first.
"""
from __future__ import annotations

import shutil
import subprocess
from pathlib import Path
from typing import List, Optional


def _script_path(name: str) -> Path:
    # Same lookup as rootforge.core.backup/module/ota — usr/local in
    # either a real install or a repo checkout is parents[3] from here.
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


def cmd_create(
    name: str,
    mode: str,
    api: str = "34",
    device: str = "pixel_6",
    abi: str = "x86_64",
    tag: str = "google_apis",
    force: bool = False,
) -> int:
    try:
        script = _script_path("setup_rooted_avd.sh")
    except FileNotFoundError as exc:
        print(exc)
        return 1
    cmd = [
        str(script),
        "create",
        "--name", name,
        "--mode", mode,
        "--api", api,
        "--device", device,
        "--abi", abi,
        "--tag", tag,
    ]
    if force:
        cmd.append("--force")
    return subprocess.run(cmd).returncode


def cmd_list() -> int:
    try:
        script = _script_path("setup_rooted_avd.sh")
    except FileNotFoundError as exc:
        print(exc)
        return 1
    return subprocess.run([str(script), "list"]).returncode


def cmd_start(name: str, snapshot: Optional[str] = None) -> int:
    try:
        script = _script_path("setup_rooted_avd.sh")
    except FileNotFoundError as exc:
        print(exc)
        return 1
    cmd = [str(script), "boot", "--name", name]
    if snapshot:
        cmd += ["--snapshot", snapshot]
    return subprocess.run(cmd).returncode


def _adb(args: List[str]) -> "subprocess.CompletedProcess[str]":
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=15)


def _find_running_serial(name: str) -> Optional[str]:
    """Find the emulator-NNNN serial currently running the given AVD.

    Uses adb's standard `emu avd name` console command, which every AVD
    instance answers regardless of how it was created/rooted.
    """
    devices = _adb(["devices"])
    for line in devices.stdout.splitlines()[1:]:
        line = line.strip()
        if not line.startswith("emulator-"):
            continue
        serial = line.split()[0]
        reply = _adb(["-s", serial, "emu", "avd", "name"])
        for out_line in reply.stdout.splitlines():
            out_line = out_line.strip()
            if not out_line or out_line == "OK":
                continue
            if out_line == name:
                return serial
            break
    return None


def cmd_stop(name: str) -> int:
    if shutil.which("adb") is None:
        print("adb not found on PATH.")
        return 1
    serial = _find_running_serial(name)
    if not serial:
        print(f"No running emulator instance found for AVD '{name}' (checked `adb devices` + `emu avd name`).")
        return 1
    print(f"Stopping '{name}' ({serial})")
    result = _adb(["-s", serial, "emu", "kill"])
    if result.stdout.strip():
        print(result.stdout.strip())
    return 0


def cmd_snapshot(name: str, action: str, snapshot_name: Optional[str] = None) -> int:
    if action in ("save", "load", "delete") and not snapshot_name:
        print(f"--snapshot-name is required for '{action}'")
        return 1
    if shutil.which("adb") is None:
        print("adb not found on PATH.")
        return 1

    serial = _find_running_serial(name)
    if not serial:
        print(
            f"No running emulator instance found for AVD '{name}' — "
            f"start it first with `rootforge avd start {name}`."
        )
        return 1

    cmd = ["-s", serial, "emu", "avd", "snapshot", action]
    if snapshot_name:
        cmd.append(snapshot_name)
    result = _adb(cmd)
    if result.stdout.strip():
        print(result.stdout.strip())
    if result.stderr.strip():
        print(result.stderr.strip())
    return result.returncode
