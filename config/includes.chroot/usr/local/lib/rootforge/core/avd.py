"""`rootforge avd` — create, boot and list emulator images.

P2 item 13 of docs/IMPLEMENTATION_PLAN.md. Wraps setup_rooted_avd.sh.

The choice validation here is not decoration. `--mode`, `--abi` and `--tag`
are each a small closed set the script checks by hand, and `--name` is a
path component the script checked in `create` but not in `boot` — so
`boot --name '../../escaped'` read its mode from a .conf outside the profile
directory. That is exactly the drift argparse removes: one declaration,
enforced identically wherever the argument appears.
"""
from __future__ import annotations

import argparse
import re
from typing import List

from rootforge.core.runner import exec_script

MODES = ("rooted", "unrooted")
ABIS = ("x86_64", "x86", "arm64-v8a", "armeabi-v7a")
TAGS = ("google_apis", "google_apis_playstore", "default", "google_tv",
        "android-wear", "android-tv")

# Becomes a profile filename, an AVD directory name and a work directory name.
NAME_RE = re.compile(r"^[A-Za-z0-9._-]+$")


def avd_name(value: str) -> str:
    if value in (".", "..") or not NAME_RE.match(value):
        raise argparse.ArgumentTypeError(
            f"'{value}' is not usable as an AVD name — [A-Za-z0-9._-] only, and "
            f"not '.' or '..'. It becomes a profile filename under "
            f"$ROOTFORGE_HOME/avd-profiles/ and an AVD directory name."
        )
    return value


def api_level(value: str) -> str:
    if not re.match(r"^[0-9]{1,3}$", value):
        raise argparse.ArgumentTypeError(
            f"'{value}' is not an API level. Expected a number like 33 or 34 — "
            f"it is interpolated into an sdkmanager package spec."
        )
    return value


def add_parser(subparsers) -> None:
    avd = subparsers.add_parser(
        "avd",
        help="Create, boot and list emulator images (rooted or unrooted).",
        allow_abbrev=False,
    )
    actions = avd.add_subparsers(dest="avd_command", required=True)

    create = actions.add_parser(
        "create", help="Create an AVD, optionally with a Magisk-patched ramdisk.",
        allow_abbrev=False,
    )
    create.add_argument("--name", required=True, type=avd_name)
    create.add_argument("--mode", required=True, choices=MODES,
                        help="rooted patches the ramdisk with Magisk; unrooted does not")
    create.add_argument("--api", type=api_level, default="34")
    create.add_argument("--device", default="pixel_6",
                        help="avdmanager device profile (default: pixel_6)")
    create.add_argument("--abi", choices=ABIS, default="x86_64")
    create.add_argument("--tag", choices=TAGS, default="google_apis")
    create.add_argument("--force", action="store_true",
                        help="Recreate the AVD even if it already exists")

    boot = actions.add_parser(
        "boot", help="Boot an AVD, using its saved profile if there is one.",
        allow_abbrev=False,
    )
    boot.add_argument("--name", required=True, type=avd_name)
    boot.add_argument("--snapshot",
                      help="Snapshot to load (rooted AVDs default to rootforge-rooted)")

    actions.add_parser("list", help="List known AVDs and RootForge profiles.",
                       allow_abbrev=False)


def dispatch(args: argparse.Namespace) -> int:
    if args.avd_command == "create":
        # A rooted AVD cannot be built from a Play system image: Play images
        # are signed and locked in ways that resist both the writable-system
        # trick and a ramdisk swap. The script refuses it too; refusing here
        # means the error arrives before sdkmanager downloads a system image
        # that was never going to work.
        if args.mode == "rooted" and args.tag == "google_apis_playstore":
            print(
                "rooted mode cannot use --tag google_apis_playstore: Play system "
                "images are signed and locked in ways that resist both the "
                "writable-system trick and a ramdisk swap. Use google_apis, "
                "default, or google_tv.",
                flush=True,
            )
            return 1

        script_args: List[str] = [
            "create",
            "--name", args.name,
            "--mode", args.mode,
            "--api", args.api,
            "--device", args.device,
            "--abi", args.abi,
            "--tag", args.tag,
        ]
        if args.force:
            script_args.append("--force")
        return exec_script("setup_rooted_avd.sh", script_args)

    if args.avd_command == "boot":
        script_args = ["boot", "--name", args.name]
        if args.snapshot:
            script_args += ["--snapshot", args.snapshot]
        return exec_script("setup_rooted_avd.sh", script_args)

    if args.avd_command == "list":
        return exec_script("setup_rooted_avd.sh", ["list"])

    raise AssertionError(f"no branch for avd command {args.avd_command!r}")
