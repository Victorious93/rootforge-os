"""`rootforge flash` and `rootforge backup` — the destructive command groups.

P2 of docs/IMPLEMENTATION_PLAN.md. Wraps flash_patched_boot.sh,
backup_partitions.sh and restore_partitions.sh.

These are ported before the lower-stakes groups on purpose: they take the most
arguments, and a mis-parsed one here costs a device rather than a retry. Two
of the bugs this repository has already hit lived exactly here —
`flash_patched_boot.sh boot.img` passing the image path as a *serial*, and a
codename or timestamp containing `..` escaping the backups tree (restore then
flashed whatever `.img` files it found in the arbitrary directory).

The scripts now guard both themselves, for anyone invoking them directly.
Validating again here is not redundant: it means the error names the argument
and the rule, and it happens before the script runs at all.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import List

from rootforge.core.runner import exec_script

PARTITIONS = ("boot", "init_boot")

# Used as a single directory name under $ROOTFORGE_HOME/devices/. Anything
# with a separator or a parent reference escapes that tree.
PATH_COMPONENT_RE = re.compile(r"^[A-Za-z0-9._-]+$")

# adb/fastboot serials are alphanumeric with a little punctuation; network
# targets look like 192.168.1.5:5555.
SERIAL_RE = re.compile(r"^[A-Za-z0-9._:-]+$")


def path_component(value: str) -> str:
    """argparse type for a value that becomes one directory name."""
    if value in (".", "..") or not PATH_COMPONENT_RE.match(value):
        raise argparse.ArgumentTypeError(
            f"'{value}' is not usable as a directory name — it must contain only "
            f"[A-Za-z0-9._-] and cannot be '.' or '..'. This becomes a directory "
            f"under $ROOTFORGE_HOME/devices/, and a value containing '/' or '..' "
            f"would escape that tree."
        )
    return value


def device_serial(value: str) -> str:
    if not SERIAL_RE.match(value):
        raise argparse.ArgumentTypeError(
            f"'{value}' does not look like a device serial (expected [A-Za-z0-9._:-], "
            f"e.g. ABC123 or 192.168.1.5:5555)."
        )
    return value


def existing_image(value: str) -> str:
    """An image that must exist and have content before anything is flashed."""
    path = Path(value)
    if not path.is_file():
        raise argparse.ArgumentTypeError(f"image not found: {value}")
    if path.stat().st_size == 0:
        raise argparse.ArgumentTypeError(f"image is empty: {value}")
    return str(path)


def add_parser(subparsers) -> None:
    # --- flash ---
    flash = subparsers.add_parser(
        "flash",
        help="Write a patched boot image to a connected device.",
        allow_abbrev=False,
    )
    flash_actions = flash.add_subparsers(dest="flash_command", required=True)

    boot = flash_actions.add_parser(
        "boot", help="Flash a patched boot/init_boot image via fastboot.",
        allow_abbrev=False,
    )
    boot.add_argument("image", type=existing_image, help="Patched .img to write")
    boot.add_argument(
        "--partition", choices=PARTITIONS, default="boot",
        help="Partition to write (default: boot)",
    )
    boot.add_argument(
        "--both-slots", action="store_true",
        help="Mirror the write to the inactive slot as well, for OTA safety",
    )
    boot.add_argument("--serial", type=device_serial, help="Target this device serial")

    # --- backup ---
    backup = subparsers.add_parser(
        "backup",
        help="Back up and restore device partitions.",
        allow_abbrev=False,
    )
    backup_actions = backup.add_subparsers(dest="backup_command", required=True)

    create = backup_actions.add_parser("create", help="Back up partitions from a device.")
    create.add_argument("codename", type=path_component)
    create.add_argument("--serial", type=device_serial)

    listing = backup_actions.add_parser("list", help="List backups held for a device.")
    listing.add_argument("codename", type=path_component)

    restore = backup_actions.add_parser(
        "restore", help="Flash a stored backup back to a device.",
        allow_abbrev=False,
    )
    restore.add_argument("codename", type=path_component)
    restore.add_argument(
        "timestamp", type=path_component,
        help="Which backup to restore, as shown by 'backup list'",
    )
    restore.add_argument("--serial", type=device_serial)


def dispatch(args: argparse.Namespace) -> int:
    if args.command == "flash":
        if args.flash_command == "boot":
            # Positional order matters to the script; the list form is what
            # stops a path with spaces re-splitting on the way through.
            script_args: List[str] = [args.image, args.partition]
            if args.both_slots:
                script_args.append("--both-slots")
            if args.serial:
                script_args.append(args.serial)
            return exec_script("flash_patched_boot.sh", script_args)
        raise AssertionError(f"no branch for flash command {args.flash_command!r}")

    if args.command == "backup":
        if args.backup_command == "create":
            script_args = [args.codename]
            if args.serial:
                script_args.append(args.serial)
            return exec_script("backup_partitions.sh", script_args)

        if args.backup_command == "list":
            # restore_partitions.sh lists when given no timestamp.
            return exec_script("restore_partitions.sh", [args.codename])

        if args.backup_command == "restore":
            script_args = [args.codename, args.timestamp]
            if args.serial:
                script_args.append(args.serial)
            return exec_script("restore_partitions.sh", script_args)

        raise AssertionError(f"no branch for backup command {args.backup_command!r}")

    raise AssertionError(f"flashing.dispatch called for command {args.command!r}")
