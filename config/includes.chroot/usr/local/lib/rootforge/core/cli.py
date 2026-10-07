"""RootForge's unified command line interface.

Each command group owns its argument validation and dispatch. This module
only joins those groups and handles the device/configuration entry points.
"""
from __future__ import annotations

import argparse
import json
import sys
from typing import Optional, Sequence

from rootforge.core import __version__, avd, boot, bridge, device, devices, flashing, module, ota
from rootforge.core.device import profile_device
from rootforge.core.devices import list_devices
from rootforge.core.doctor import run_doctor


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="rootforge", description="RootForge OS unified CLI.", allow_abbrev=False
    )
    parser.add_argument("--version", action="version", version=f"rootforge {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)

    doctor_parser = sub.add_parser("doctor", help="Check the environment for common problems.", allow_abbrev=False)
    doctor_parser.add_argument("--json", action="store_true")
    doctor_parser.add_argument("--quiet", action="store_true")
    doctor_parser.add_argument("--strict", action="store_true")

    devices_parser = sub.add_parser("devices", help="List connected adb and fastboot devices.", allow_abbrev=False)
    devices_parser.add_argument("--json", action="store_true")
    devices_parser.add_argument("-l", "--detailed", action="store_true")

    device_parser = sub.add_parser("device", help="Inspect a connected device.", allow_abbrev=False)
    device_sub = device_parser.add_subparsers(dest="device_command", required=True)
    info = device_sub.add_parser("info", help="Profile a device and its capabilities.", allow_abbrev=False)
    info.add_argument("serial", nargs="?", default=None)
    info.add_argument("--json", action="store_true")
    show = device_sub.add_parser("show", help="Alias for device info.", allow_abbrev=False)
    show.add_argument("serial", nargs="?", default=None)
    show.add_argument("--serial", dest="serial_option", default=None)
    show.add_argument("--json", action="store_true")

    config_parser = sub.add_parser("config", help="Inspect layered RootForge configuration.", allow_abbrev=False)
    config_sub = config_parser.add_subparsers(dest="config_command", required=True)
    show_config = config_sub.add_parser("show", help="Print merged configuration.", allow_abbrev=False)
    show_config.add_argument("--codename", default=None)

    flashing.add_parser(sub)
    module.add_parser(sub)
    boot.add_parser(sub)
    ota.add_parser(sub)
    avd.add_parser(sub)
    bridge.add_parser(sub)
    return parser


def _select_device(serial: Optional[str]):
    found = list_devices()
    if serial:
        for item in found:
            if item.serial == serial:
                return item.serial, item.mode
        attached = ", ".join(item.serial for item in found) or "(none)"
        raise LookupError(f"no attached device with serial '{serial}'. Attached: {attached}")
    usable = [item for item in found if item.usable]
    if not usable:
        raise LookupError("no usable device attached. Run `rootforge devices` to see what's connected.")
    if len(usable) > 1:
        raise LookupError("multiple usable devices attached — pass a serial: " + ", ".join(d.serial for d in usable))
    return usable[0].serial, usable[0].mode


def cmd_device_info(args: argparse.Namespace) -> int:
    serial = getattr(args, "serial_option", None) or args.serial
    try:
        resolved, mode = _select_device(serial)
    except LookupError as exc:
        print(f"rootforge: error: {exc}", file=sys.stderr)
        return 1
    profile = profile_device(resolved, mode)
    if args.json:
        print(json.dumps(profile.as_dict(), indent=2))
    else:
        for key, value in profile.as_dict().items():
            if key not in ("raw", "refusal_message"):
                print(f"{key}: {value if value is not None else 'unknown'}")
        if profile.refusal_message():
            print(profile.refusal_message())
    return 0 if profile.supported else 1


_device_info = cmd_device_info


def _devices(args: argparse.Namespace) -> int:
    found = devices.list_devices(detailed=args.detailed)
    if args.json:
        print(json.dumps([item.as_dict() for item in found], indent=2))
    elif not found:
        print("No devices connected.")
    else:
        for item in found:
            print(f"{'  ' if item.usable else '! '}{item.serial}  {item.mode}  {item.state}")
            if args.detailed:
                for key, value in item.properties.items():
                    print(f"    {key}: {value}")
    return 0 if any(item.usable for item in found) else 1


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == "doctor":
        return run_doctor(as_json=args.json, quiet=args.quiet, strict=args.strict)
    if args.command == "devices":
        return _devices(args)
    if args.command == "device":
        return _device_info(args)
    if args.command == "config":
        try:
            from rootforge.core.config import cmd_show
        except ImportError as exc:
            print("rootforge config requires python3-yaml; install the package and retry.", file=sys.stderr)
            return 1
        return cmd_show(args.codename)
    if args.command in ("flash", "backup"):
        return flashing.dispatch(args)
    if args.command == "module":
        return module.dispatch(args)
    if args.command == "boot":
        return boot.dispatch(args)
    if args.command == "ota":
        return ota.dispatch(args)
    if args.command == "avd":
        return avd.dispatch(args)
    if args.command == "bridge":
        return bridge.dispatch(args)
    parser.error(f"unknown command: {args.command}")


if __name__ == "__main__":
    sys.exit(main())
