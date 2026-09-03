"""`rootforge boot` — patch a stock boot image with a KernelSU GKI kernel.

Part of P2 item 11 of docs/IMPLEMENTATION_PLAN.md. Wraps
kernelsu_patch_boot.sh.

Deliberately *part* of item 11. That item also lists unpack, repack and
verify as a unified front end over magiskboot, avbtool and mkbootimg. Those
do not exist as scripts yet, so building them here would be new
functionality rather than a port — and new boot-image handling cannot be
verified without real boot images and a device to flash them to. Neither is
available here. `patch` and `flash-last` are the parts that wrap proven code.

The validation below is the rule the script now enforces, in the same place
argparse can report it. Both matter: the tag rule in particular exists
because an unvalidated tag redirected a GitHub API query to an arbitrary
repository, whose asset then became the kernel of a flashed boot image.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import List

from rootforge.core.runner import exec_script

# A GitHub release tag. curl resolves ../ segments in a URL path before
# sending the request, so a tag containing a slash moves the API query to
# another repository entirely — verified against api.github.com.
TAG_RE = re.compile(r"^[A-Za-z0-9._-]+$")

# Becomes part of the output filename.
CODENAME_RE = re.compile(r"^[A-Za-z0-9._-]+$")

# The Android versions KernelSU publishes GKI kernels for. Not a closed set —
# a new one appears each year — so this is a shape check, not a whitelist.
ANDROID_VERSION_RE = re.compile(r"^[0-9]{1,2}$")


def release_tag(value: str) -> str:
    if not TAG_RE.match(value):
        raise argparse.ArgumentTypeError(
            f"'{value}' is not a release tag. Expected something like v0.9.5 or "
            f"'latest' — [A-Za-z0-9._-] only. A tag containing '/' or '..' is "
            f"resolved by curl before the request is sent, which moves the "
            f"release query to a different repository."
        )
    return value


def device_codename(value: str) -> str:
    if not CODENAME_RE.match(value):
        raise argparse.ArgumentTypeError(
            f"'{value}' is not usable as a device codename — [A-Za-z0-9._-] only. "
            f"It becomes part of the patched image's filename."
        )
    return value


def android_version(value: str) -> str:
    if not ANDROID_VERSION_RE.match(value):
        raise argparse.ArgumentTypeError(
            f"'{value}' is not an Android version. Expected a number like 12, 13 "
            f"or 14 — it is matched against KernelSU's release asset names."
        )
    return value


def existing_image(value: str) -> str:
    path = Path(value)
    if not path.is_file():
        raise argparse.ArgumentTypeError(f"boot image not found: {value}")
    if path.stat().st_size == 0:
        raise argparse.ArgumentTypeError(f"boot image is empty: {value}")
    return str(path)


def add_parser(subparsers) -> None:
    boot = subparsers.add_parser(
        "boot",
        help="Patch a stock boot image with a KernelSU GKI kernel.",
        allow_abbrev=False,
    )
    actions = boot.add_subparsers(dest="boot_command", required=True)

    patch = actions.add_parser(
        "patch", help="Build a KernelSU-patched boot image from a stock one.",
        allow_abbrev=False,
    )
    patch.add_argument("--stock-boot", required=True, type=existing_image,
                       help="Stock boot.img pulled from the device or an OTA")
    patch.add_argument("--android-version", required=True, type=android_version,
                       help="Android version of the stock image (12, 13, 14, ...)")
    patch.add_argument("--ksu-version", type=release_tag, default="latest",
                       help="KernelSU release tag (default: latest)")
    patch.add_argument("--device", type=device_codename, default="unknown",
                       help="Device codename, used in the output filename")

    flash = actions.add_parser(
        "flash-last",
        help="Flash the most recently patched image (prompts for confirmation).",
        allow_abbrev=False,
    )
    flash.add_argument("--device", type=device_codename, default="unknown",
                       help="Device codename, shown in the confirmation prompt")


def dispatch(args: argparse.Namespace) -> int:
    if args.boot_command == "patch":
        script_args: List[str] = [
            "--stock-boot", args.stock_boot,
            "--android-version", args.android_version,
            "--ksu-version", args.ksu_version,
        ]
        if args.device != "unknown":
            script_args += ["--device", args.device]
        return exec_script("kernelsu_patch_boot.sh", script_args)

    if args.boot_command == "flash-last":
        # Output is not captured anywhere in this CLI, which matters most
        # here: the script's typed-confirmation gate reads /dev/tty, and this
        # subcommand writes the boot partition.
        script_args = ["--flash"]
        if args.device != "unknown":
            script_args += ["--device", args.device]
        return exec_script("kernelsu_patch_boot.sh", script_args)

    raise AssertionError(f"no branch for boot command {args.boot_command!r}")
