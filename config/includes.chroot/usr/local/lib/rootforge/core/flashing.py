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
import sys
from pathlib import Path
from typing import List

from rootforge.core import backup
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


PARTITION_NAME_RE = re.compile(r"^[a-z0-9_]+$")


def partition_list(value: str) -> str:
    """Comma-separated partition names, each a plain lowercase name."""
    parts = value.split(",")
    if not value or any(not PARTITION_NAME_RE.match(p) for p in parts) or len(set(parts)) != len(parts):
        raise argparse.ArgumentTypeError(
            f"'{value}' is not a list of distinct partition names "
            f"(lowercase letters, digits and underscores, comma-separated)"
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
        help="Write the same image to both slots (requires --slots-same-build)",
    )
    boot.add_argument(
        "--slots-same-build", action="store_true",
        help="Assert that both slots hold the same build; RootForge cannot verify this",
    )
    boot.add_argument(
        "--no-boot-check", action="store_true",
        help="Do not wait for the device to finish booting (exit status will be 4)",
    )
    boot.add_argument("--serial", type=device_serial, help="Target this device serial")

    # --- backup ---
    backup = subparsers.add_parser(
        "backup",
        help="Back up and restore device partitions.",
        allow_abbrev=False,
    )
    backup_actions = backup.add_subparsers(dest="backup_command", required=True)

    create = backup_actions.add_parser(
        "create", help="Back up partitions from a device.", allow_abbrev=False,
    )
    create.add_argument("codename", type=path_component)
    create.add_argument("--serial", type=device_serial)
    create.add_argument(
        "--partitions", type=partition_list,
        help="Comma-separated partitions to capture (default: backup.partitions from config)",
    )

    listing = backup_actions.add_parser("list", help="List backups held for a device.")
    listing.add_argument("codename", type=path_component)

    verify = backup_actions.add_parser(
        "verify", help="Check a stored backup against its manifest.",
        allow_abbrev=False,
    )
    verify.add_argument("codename", type=path_component)
    verify.add_argument(
        "timestamp", type=path_component,
        help="Which backup to check, as shown by 'backup list'",
    )
    verify.add_argument("--partitions", type=partition_list, help="Only check these partitions")
    verify.add_argument("--json", action="store_true", help="Machine-readable result")

    legacy = backup_actions.add_parser(
        "import-legacy",
        help="Record an older SHA256SUMS-only backup as 'legacy-imported' (never 'captured').",
        allow_abbrev=False,
    )
    legacy.add_argument("codename", type=path_component)
    legacy.add_argument("timestamp", type=path_component)

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
    restore.add_argument(
        "--partitions", type=partition_list,
        help="Restore only these partitions (default: every image in the manifest)",
    )
    restore.add_argument(
        "--accept-legacy-import", action="store_true",
        help="Allow a 'legacy-imported' backup, whose device and slot are unknown",
    )


def dispatch(args: argparse.Namespace) -> int:
    if args.command == "flash":
        if args.flash_command == "boot":
            # Positional order matters to the script; the list form is what
            # stops a path with spaces re-splitting on the way through.
            if args.both_slots and not args.slots_same_build:
                print(
                    "rootforge: error: --both-slots writes the same image to both slots, which is "
                    "only correct if both hold the same build. Add --slots-same-build to confirm.",
                    file=sys.stderr,
                )
                return 2
            script_args: List[str] = [args.image, args.partition]
            if args.both_slots:
                script_args += ["--both-slots", "--slots-same-build"]
            if args.no_boot_check:
                script_args.append("--no-boot-check")
            if args.serial:
                script_args.append(args.serial)
            return exec_script("flash_patched_boot.sh", script_args)
        raise AssertionError(f"no branch for flash command {args.flash_command!r}")

    if args.command == "backup":
        if args.backup_command == "create":
            script_args = [args.codename]
            if args.partitions:
                script_args += ["--partitions", args.partitions]
            if args.serial:
                script_args.append(args.serial)
            return exec_script("backup_partitions.sh", script_args)

        if args.backup_command == "list":
            # restore_partitions.sh lists when given no timestamp.
            return exec_script("restore_partitions.sh", [args.codename])

        if args.backup_command == "verify":
            selected = args.partitions.split(",") if args.partitions else None
            return backup.cmd_verify(args.codename, args.timestamp, args.json, selected)

        if args.backup_command == "import-legacy":
            return backup.cmd_import_legacy(args.codename, args.timestamp)

        if args.backup_command == "restore":
            script_args = [args.codename, args.timestamp]
            if args.partitions:
                script_args += ["--partitions", args.partitions]
            if args.accept_legacy_import:
                script_args.append("--accept-legacy-import")
            if args.serial:
                script_args.append(args.serial)
            return exec_script("restore_partitions.sh", script_args)

        raise AssertionError(f"no branch for backup command {args.backup_command!r}")

    raise AssertionError(f"flashing.dispatch called for command {args.command!r}")
