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
from rootforge.core import avd as avd_mod
from rootforge.core import backup as backup_mod
from rootforge.core.config import cmd_show as config_cmd_show
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

    config_parser = subparsers.add_parser(
        "config", help="Inspect the layered RootForge configuration."
    )
    config_sub = config_parser.add_subparsers(dest="config_command", required=True)
    show_config_parser = config_sub.add_parser(
        "show", help="Print the effective merged config and which files set it."
    )
    show_config_parser.add_argument(
        "--codename",
        default=None,
        help="Also apply devices/<codename>/rootforge.yaml's overrides.",
    )

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

    avd_parser = subparsers.add_parser(
        "avd", help="Create, list, start, stop, and snapshot Android emulator AVDs."
    )
    avd_sub = avd_parser.add_subparsers(dest="avd_command", required=True)

    avd_create_parser = avd_sub.add_parser("create", help="Create a rooted or unrooted AVD.")
    avd_create_parser.add_argument("name")
    avd_create_parser.add_argument("--mode", required=True, choices=["rooted", "unrooted"])
    avd_create_parser.add_argument("--api", default="34")
    avd_create_parser.add_argument("--device", default="pixel_6")
    avd_create_parser.add_argument("--abi", default="x86_64")
    avd_create_parser.add_argument("--tag", default="google_apis")
    avd_create_parser.add_argument("--force", action="store_true")

    avd_sub.add_parser("list", help="List known AVDs and RootForge's saved profiles.")

    avd_start_parser = avd_sub.add_parser("start", help="Boot an AVD.")
    avd_start_parser.add_argument("name")
    avd_start_parser.add_argument(
        "--snapshot", default=None, help="Boot from a specific snapshot."
    )

    avd_stop_parser = avd_sub.add_parser(
        "stop", help="Stop a running AVD (adb emu kill)."
    )
    avd_stop_parser.add_argument("name")

    avd_snapshot_parser = avd_sub.add_parser(
        "snapshot", help="Save/load/list/delete a running AVD's snapshots."
    )
    avd_snapshot_parser.add_argument("name")
    avd_snapshot_parser.add_argument("action", choices=["save", "load", "list", "delete"])
    avd_snapshot_parser.add_argument(
        "snapshot_name", nargs="?", default=None, help="Required for save/load/delete."
    )

    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "doctor":
        return run_doctor()

    if args.command == "device" and args.device_command == "show":
        return device_cmd_show(args.serial)

    if args.command == "config" and args.config_command == "show":
        return config_cmd_show(args.codename)

    if args.command == "backup":
        if args.backup_command == "create":
            return backup_mod.cmd_create(args.codename, args.serial)
        if args.backup_command == "list":
            return backup_mod.cmd_list(args.codename)
        if args.backup_command == "verify":
            return backup_mod.cmd_verify(args.codename, args.timestamp)
        if args.backup_command == "restore":
            return backup_mod.cmd_restore(args.codename, args.timestamp, args.serial)

    if args.command == "avd":
        if args.avd_command == "create":
            return avd_mod.cmd_create(
                args.name, args.mode, args.api, args.device, args.abi, args.tag, args.force
            )
        if args.avd_command == "list":
            return avd_mod.cmd_list()
        if args.avd_command == "start":
            return avd_mod.cmd_start(args.name, args.snapshot)
        if args.avd_command == "stop":
            return avd_mod.cmd_stop(args.name)
        if args.avd_command == "snapshot":
            return avd_mod.cmd_snapshot(args.name, args.action, args.snapshot_name)

    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
