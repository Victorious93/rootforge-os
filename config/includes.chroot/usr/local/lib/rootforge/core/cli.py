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
from rootforge.core import backup as backup_mod
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

    backup_parser = subparsers.add_parser(
        "backup", help="SHA-256-verified partition backup/restore."
    )
    backup_sub = backup_parser.add_subparsers(dest="backup_command", required=True)

    create_parser = backup_sub.add_parser(
        "create", help="Back up a device's partitions and write a SHA-256 manifest."
    )
    create_parser.add_argument("codename", help="Device codename (used for the backup path).")
    create_parser.add_argument("--serial", default=None, help="Target a specific device.")

    list_parser = backup_sub.add_parser("list", help="List existing backups.")
    list_parser.add_argument(
        "codename", nargs="?", default=None, help="Limit to one device (default: all)."
    )

    verify_parser = backup_sub.add_parser(
        "verify", help="Re-hash a backup's images and compare against its manifest."
    )
    verify_parser.add_argument("codename")
    verify_parser.add_argument("timestamp")

    restore_parser = backup_sub.add_parser(
        "restore", help="Verify (if possible) and restore a backup via fastboot flash."
    )
    restore_parser.add_argument("codename")
    restore_parser.add_argument("timestamp")
    restore_parser.add_argument("--serial", default=None, help="Target a specific device.")

    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "doctor":
        return run_doctor()

    if args.command == "backup":
        if args.backup_command == "create":
            return backup_mod.cmd_create(args.codename, args.serial)
        if args.backup_command == "list":
            return backup_mod.cmd_list(args.codename)
        if args.backup_command == "verify":
            return backup_mod.cmd_verify(args.codename, args.timestamp)
        if args.backup_command == "restore":
            return backup_mod.cmd_restore(args.codename, args.timestamp, args.serial)

    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
