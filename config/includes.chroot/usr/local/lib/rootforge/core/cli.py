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
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "doctor":
        return run_doctor()

    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
