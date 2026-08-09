"""rootforge — unified CLI entrypoint for RootForge OS.

Currently a thin dispatcher: `doctor` is the only real subcommand. Later
phases (see docs/IMPLEMENTATION_PLAN.md) add device/module/boot/backup/
ota/avd subcommands here, wrapping the existing usr/local/bin/*.sh scripts
rather than reimplementing them.
"""
from __future__ import annotations

import argparse
import sys
from typing import Optional, Sequence

from rootforge.core import __version__
from rootforge.core.device import cmd_show as device_cmd_show
from rootforge.core.doctor import run_doctor


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="rootforge", description="RootForge OS unified CLI."
    )
    parser.add_argument(
        "--version", action="version", version=f"rootforge {__version__}"
    )
    subparsers = parser.add_subparsers(dest="command")
    subparsers.add_parser("doctor", help="Check the environment for common problems.")

    device_parser = subparsers.add_parser(
        "device", help="Inspect the currently connected device."
    )
    device_sub = device_parser.add_subparsers(dest="device_command", required=True)
    show_parser = device_sub.add_parser(
        "show", help="Detect and print the connected device's state."
    )
    show_parser.add_argument(
        "--serial", default=None, help="Target a specific device (adb -s / fastboot -s)."
    )

    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "doctor":
        return run_doctor()

    if args.command == "device" and args.device_command == "show":
        return device_cmd_show(args.serial)

    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
