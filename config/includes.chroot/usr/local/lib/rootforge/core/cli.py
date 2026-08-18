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
from rootforge.core import boot as boot_mod
from rootforge.core import module as module_mod
from rootforge.core import ota as ota_mod
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

    module_parser = subparsers.add_parser(
        "module", help="Scaffold, lint, and build Magisk/KernelSU/APatch/Zygisk/Xposed modules."
    )
    module_sub = module_parser.add_subparsers(dest="module_command", required=True)

    module_create_parser = module_sub.add_parser(
        "create", help="Scaffold a new module."
    )
    module_create_parser.add_argument("module_id")
    module_create_parser.add_argument("display_name")
    module_create_parser.add_argument(
        "--target",
        default="magisk",
        choices=list(module_mod.VALID_TARGETS),
        help="Module framework/type (default: magisk).",
    )

    module_lint_parser = module_sub.add_parser(
        "lint", help="Lint a module directory or built zip."
    )
    module_lint_parser.add_argument("path")
    module_lint_parser.add_argument(
        "--json", action="store_true", help="Emit machine-readable findings for CI."
    )

    module_build_parser = module_sub.add_parser(
        "build", help="Zip a module, optionally push + install it on a connected device."
    )
    module_build_parser.add_argument("module_id")
    module_build_parser.add_argument(
        "--install", action="store_true", help="Push and install after building."
    )
    module_build_parser.add_argument(
        "--framework",
        default="magisk",
        choices=["magisk", "kernelsu"],
        help="Install-time framework CLI to use with --install (default: magisk).",
    )

    boot_parser = subparsers.add_parser(
        "boot", help="Unpack/patch/repack/verify boot images via magiskboot/avbtool."
    )
    boot_sub = boot_parser.add_subparsers(dest="boot_command", required=True)

    boot_inspect_parser = boot_sub.add_parser(
        "inspect", help="Unpack a boot image into a temp dir and list its components."
    )
    boot_inspect_parser.add_argument("image")

    boot_unpack_parser = boot_sub.add_parser(
        "unpack", help="Unpack a boot image into a working directory."
    )
    boot_unpack_parser.add_argument("image")
    boot_unpack_parser.add_argument("out_dir")

    boot_repack_parser = boot_sub.add_parser(
        "repack", help="Repack a previously-unpacked working directory into new-boot.img."
    )
    boot_repack_parser.add_argument("work_dir")

    boot_patch_parser = boot_sub.add_parser(
        "patch", help="Run magiskboot cpio commands against a ramdisk in a working directory."
    )
    boot_patch_parser.add_argument("work_dir")
    boot_patch_parser.add_argument("ramdisk")
    boot_patch_parser.add_argument(
        "cpio_commands",
        nargs=argparse.REMAINDER,
        help="magiskboot cpio commands, e.g. -- 'add 0750 init magiskinit'",
    )

    boot_verify_parser = boot_sub.add_parser(
        "verify", help="Verify a boot/vbmeta image's AVB signature via avbtool."
    )
    boot_verify_parser.add_argument("image")

    ota_parser = subparsers.add_parser(
        "ota", help="Inspect/extract Android OTA zips and raw payload.bin files."
    )
    ota_sub = ota_parser.add_subparsers(dest="ota_command", required=True)

    ota_inspect_parser = ota_sub.add_parser(
        "inspect", help="Identify an OTA input without extracting partitions."
    )
    ota_inspect_parser.add_argument("input")

    ota_extract_parser = ota_sub.add_parser(
        "extract", help="Extract partition images from an OTA zip or payload.bin."
    )
    ota_extract_parser.add_argument("input")
    ota_extract_parser.add_argument("output_dir")
    ota_extract_parser.add_argument(
        "--partitions",
        default=None,
        help="Comma-separated partition names (default: boot,init_boot,vendor_boot,dtbo,vbmeta).",
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

    if args.command == "module":
        if args.module_command == "create":
            return module_mod.cmd_create(args.module_id, args.display_name, args.target)
        if args.module_command == "lint":
            return module_mod.cmd_lint(args.path, args.json)
        if args.module_command == "build":
            return module_mod.cmd_build(args.module_id, args.install, args.framework)

    if args.command == "boot":
        if args.boot_command == "inspect":
            return boot_mod.cmd_inspect(args.image)
        if args.boot_command == "unpack":
            return boot_mod.cmd_unpack(args.image, args.out_dir)
        if args.boot_command == "repack":
            return boot_mod.cmd_repack(args.work_dir)
        if args.boot_command == "patch":
            return boot_mod.cmd_patch(args.work_dir, args.ramdisk, args.cpio_commands)
        if args.boot_command == "verify":
            return boot_mod.cmd_verify(args.image)

    if args.command == "ota":
        if args.ota_command == "inspect":
            return ota_mod.cmd_inspect(args.input)
        if args.ota_command == "extract":
            return ota_mod.cmd_extract(args.input, args.output_dir, args.partitions)

    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
