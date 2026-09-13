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

from rootforge.core import (
    __version__,
    flashing as flashing_cmd,
    module as module_cmd,
    ota as ota_cmd,
    boot as boot_cmd,
    avd as avd_cmd,
)
from rootforge.core.device import profile_device
from rootforge.core.devices import list_devices
from rootforge.core.doctor import run_doctor


def build_parser() -> argparse.ArgumentParser:
    # allow_abbrev=False: argparse otherwise accepts any unambiguous prefix,
    # so `--both-slot` silently means `--both-slots`. Worse, adding a flag
    # later can change what an existing abbreviation resolves to, or make it
    # ambiguous — a silent behaviour change in scripts that already work.
    # These commands write boot partitions; four saved keystrokes is not
    # worth that. Subparsers do not inherit this, so each sets it too.
    parser = argparse.ArgumentParser(
        prog="rootforge",
        description="RootForge OS unified CLI.",
        allow_abbrev=False,
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

    device = subparsers.add_parser(
        "device",
        help="Profile one device's vendor, slot layout, bootloader state, and root method.",
        allow_abbrev=False,
    )
    # required=True so `rootforge device` with no verb names the verb rather
    # than silently doing nothing — same reasoning as `module`'s subparsers.
    device_actions = device.add_subparsers(dest="device_command", required=True)
    device_info = device_actions.add_parser(
        "info", help="Show a detailed profile for one device.", allow_abbrev=False
    )
    device_info.add_argument(
        "serial",
        nargs="?",
        help="Device serial. Auto-detected if exactly one usable device is attached.",
    )
    device_info.add_argument(
        "--json", action="store_true", help="Emit machine-readable results."
    )

    # P2 of docs/IMPLEMENTATION_PLAN.md: command groups that wrap the
    # standalone scripts. Each group owns its own parser so adding one does
    # not mean editing a growing if/elif here.
    module_cmd.add_parser(subparsers)
    flashing_cmd.add_parser(subparsers)
    ota_cmd.add_parser(subparsers)
    boot_cmd.add_parser(subparsers)
    avd_cmd.add_parser(subparsers)

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


def _select_device(serial: Optional[str]):
    """Resolve an optional serial to (serial, mode).

    Raises LookupError with a user-facing explanation for every case that
    isn't "exactly one clear answer" — no device, no match for an explicit
    serial, or more than one usable device with no serial given to
    disambiguate. This deliberately never guesses which device to profile.
    """
    devices = list_devices()
    if serial:
        for d in devices:
            if d.serial == serial:
                return d.serial, d.mode
        attached = ", ".join(d.serial for d in devices) or "(none)"
        raise LookupError(f"no attached device with serial '{serial}'. Attached: {attached}")

    usable = [d for d in devices if d.usable]
    if not usable:
        raise LookupError("no usable device attached. Run `rootforge devices` to see what's connected.")
    if len(usable) > 1:
        serials = ", ".join(d.serial for d in usable)
        raise LookupError(f"multiple usable devices attached — pass a serial: {serials}")
    return usable[0].serial, usable[0].mode


def cmd_device_info(args: argparse.Namespace) -> int:
    try:
        serial, mode = _select_device(args.serial)
    except LookupError as exc:
        print(f"rootforge: error: {exc}", file=sys.stderr)
        return 1

    profile = profile_device(serial, mode)
    if args.json:
        print(json.dumps(profile.as_dict(), indent=2))
        return 0 if profile.supported else 1

    print(f"serial:              {profile.serial}")
    print(f"mode:                {profile.mode}")
    print(f"codename:            {profile.codename or 'unknown'}")
    print(f"model:               {profile.model or 'unknown'}")
    print(f"vendor:              {profile.vendor or 'unknown'}")
    print(f"slot mode:           {profile.slot_mode}")
    if profile.slot_mode == "ab":
        print(f"current slot:        {profile.current_slot or 'unknown'}")
    unlocked = profile.bootloader_unlocked
    unlocked_str = "unknown" if unlocked is None else ("yes" if unlocked else "no")
    print(f"bootloader unlocked: {unlocked_str}")
    print(f"root method:         {profile.root_method or 'unknown/undetermined'}")

    message = profile.refusal_message()
    if message:
        print("")
        print(message)
        return 1
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "doctor":
        return run_doctor(as_json=args.json, quiet=args.quiet, strict=args.strict)
    if args.command == "devices":
        return cmd_devices(args)
    if args.command == "device":
        if args.device_command == "info":
            return cmd_device_info(args)
        raise AssertionError(f"no dispatch branch for device command {args.device_command!r}")
    if args.command == "module":
        return module_cmd.dispatch(args)
    if args.command in ("flash", "backup"):
        return flashing_cmd.dispatch(args)
    if args.command == "ota":
        return ota_cmd.dispatch(args)
    if args.command == "boot":
        return boot_cmd.dispatch(args)
    if args.command == "avd":
        return avd_cmd.dispatch(args)

    parser.print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
