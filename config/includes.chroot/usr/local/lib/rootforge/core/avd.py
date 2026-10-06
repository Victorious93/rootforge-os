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
import argparse
import re
from pathlib import Path
from typing import List, Optional

from rootforge.core.runner import exec_script

AVD_NAME_RE = re.compile(r"^[A-Za-z0-9_.-]+$")


def avd_name(value: str) -> str:
    if value in (".", "..") or not AVD_NAME_RE.fullmatch(value):
        raise argparse.ArgumentTypeError(
            f"'{value}' is not a safe AVD name; names under the avd-profiles directory cannot contain path separators."
        )
    return value


def api_level(value: str) -> str:
    if not re.fullmatch(r"[1-9][0-9]?", value):
        raise argparse.ArgumentTypeError("API level must be a positive one- or two-digit number")
    return value


def add_parser(subparsers) -> None:
    parser = subparsers.add_parser("avd", help="Create and manage Android emulators.", allow_abbrev=False)
    actions = parser.add_subparsers(dest="avd_command", required=True)
    create = actions.add_parser("create", help="Create a rooted or unrooted AVD.", allow_abbrev=False)
    create.add_argument("--name", required=True, type=avd_name)
    create.add_argument("--mode", required=True, choices=("rooted", "unrooted"))
    create.add_argument("--api", default="34", type=api_level)
    create.add_argument("--device", default="pixel_6")
    create.add_argument("--abi", choices=("x86", "x86_64", "arm64-v8a", "armeabi-v7a"), default="x86_64")
    create.add_argument("--tag", default="google_apis")
    create.add_argument("--force", action="store_true")
    actions.add_parser("list", help="List saved AVD profiles.", allow_abbrev=False)
    boot = actions.add_parser("boot", help="Start an AVD.", allow_abbrev=False)
    boot.add_argument("--name", required=True, type=avd_name)
    boot.add_argument("--snapshot", default=None)
    stop = actions.add_parser("stop", help="Stop an AVD.", allow_abbrev=False)
    stop.add_argument("--name", required=True, type=avd_name)
    return


def dispatch(args) -> int:
    command = args.avd_command
    if command == "create":
        if args.mode == "rooted" and "play" in args.tag.lower():
            print("Rooted AVDs require an unsigned Google APIs image; Play images are signed and locked.")
            return 1
        argv = ["create", "--name", args.name, "--mode", args.mode,
                "--api", args.api, "--device", args.device,
                "--abi", args.abi, "--tag", args.tag]
        if args.force:
            argv.append("--force")
        return exec_script("setup_rooted_avd.sh", argv)
    if command == "list":
        return exec_script("setup_rooted_avd.sh", ["list"])
    if command == "boot":
        argv = ["boot", "--name", args.name]
        if args.snapshot:
            argv += ["--snapshot", args.snapshot]
        return exec_script("setup_rooted_avd.sh", argv)
    if command == "stop":
        return exec_script("setup_rooted_avd.sh", ["stop", "--name", args.name])
    raise AssertionError(f"unknown AVD command: {command!r}")


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
