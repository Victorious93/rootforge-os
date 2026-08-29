"""rootforge — unified CLI entrypoint for RootForge OS.

A thin dispatcher. Later phases (see docs/IMPLEMENTATION_PLAN.md) add
module/boot/backup/ota/avd subcommands here, wrapping the existing
usr/local/bin/*.sh scripts rather than reimplementing them.
"""
from __future__ import annotations

import argparse
import json
import sys
from typing import Optional, Sequence

from rootforge.core import __version__
from rootforge.core.devices import list_devices
from rootforge.core.doctor import run_doctor


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="rootforge", description="RootForge OS unified CLI."
    )
    parser.add_argument(
        "--version", action="version", version=f"rootforge {__version__}"
    )
    subparsers = parser.add_subparsers(dest="command")

    doctor = subparsers.add_parser(
        "doctor", help="Check the environment for common problems."
    )
    doctor.add_argument(
        "--json", action="store_true", help="Emit machine-readable results instead of a table."
    )
    doctor.add_argument(
        "--quiet", "-q", action="store_true", help="Only print checks that failed or warned."
    )
    doctor.add_argument(
        "--strict",
        action="store_true",
        help="Exit non-zero on warnings too, not just on required-check failures.",
    )

    devices = subparsers.add_parser(
        "devices", help="List connected devices (adb + fastboot)."
    )
    devices.add_argument(
        "--json", action="store_true", help="Emit machine-readable results."
    )
    devices.add_argument(
        "-l",
        "--detailed",
        action="store_true",
        help="Also query each device for codename/slot/lock state (slower).",
    )

    return parser


def _print_devices(devices, detailed: bool) -> None:
    if not devices:
        print("No devices connected.")
        print("")
        print("If a device is plugged in but not listed:")
        print("  - accept the USB-debugging prompt on the device")
        print("  - check the cable supports data, not just charging")
        print("  - run: adb kill-server && adb start-server")
        return

    width = max(len(d.serial) for d in devices)
    for device in devices:
        flag = "  " if device.usable else "! "
        line = f"{flag}{device.serial:<{width}}  {device.mode:<8} {device.state}"
        if device.note:
            line += f"  — {device.note}"
        print(line)
        if detailed and device.properties:
            for key, value in device.properties.items():
                print(f"      {key:<12} {value}")

    unusable = [d for d in devices if not d.usable]
    if unusable:
        print("")
        print(f"{len(unusable)} device(s) attached but not usable (marked !).")


def cmd_devices(args: argparse.Namespace) -> int:
    devices = list_devices(detailed=args.detailed)
    if args.json:
        print(json.dumps([d.as_dict() for d in devices], indent=2))
    else:
        _print_devices(devices, args.detailed)
    # No device connected is a legitimate state to report, not a failure of
    # this command — but it is worth an exit code a script can branch on.
    return 0 if any(d.usable for d in devices) else 1


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "doctor":
        return run_doctor(as_json=args.json, quiet=args.quiet, strict=args.strict)
    if args.command == "devices":
        return cmd_devices(args)

    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
