"""`rootforge ota` — pull images out of an OTA payload, and inspect them.

P2 items 12 and (partly) 11 of docs/IMPLEMENTATION_PLAN.md. Wraps
extract_ota.sh and inspect_partition_image.sh.

The argument shape here is the reason this group is worth porting. extract_ota
takes an input, an *optional* positional output directory, and a flag — and
that exact combination is what produced the bug the script now carries a
comment about: `extract_ota.sh ota.zip --partitions boot` read the flag as the
output directory and extracted into a directory literally named
"--partitions", with the partition list silently left at its default. The
script guards it now; argparse makes the shape unrepresentable.
"""
from __future__ import annotations

import argparse
from pathlib import Path
from typing import List

from rootforge.core.runner import exec_script

# What an A/B OTA payload actually carries that anyone here wants. Not a
# closed set — a payload can hold any partition — so this is the default and
# the documented spelling, not a validator.
DEFAULT_PARTITIONS = "boot,init_boot,vendor_boot,dtbo,vbmeta"


def existing_file(value: str) -> str:
    path = Path(value)
    if not path.is_file():
        raise argparse.ArgumentTypeError(f"not found: {value}")
    if path.stat().st_size == 0:
        raise argparse.ArgumentTypeError(f"file is empty: {value}")
    return str(path)


def partition_list(value: str) -> str:
    """A comma-separated partition list, checked for the shapes that silently
    do nothing rather than failing."""
    if not value.strip():
        raise argparse.ArgumentTypeError("--partitions was given an empty list")
    names = [p.strip() for p in value.split(",")]
    if any(not n for n in names):
        raise argparse.ArgumentTypeError(
            f"'{value}' has an empty entry — check for a stray or trailing comma."
        )
    bad = [n for n in names if not n.replace("_", "").replace("-", "").isalnum()]
    if bad:
        raise argparse.ArgumentTypeError(
            f"not partition names: {', '.join(bad)}. Expected a comma-separated "
            f"list like '{DEFAULT_PARTITIONS}'."
        )
    return ",".join(names)


def add_parser(subparsers) -> None:
    ota = subparsers.add_parser(
        "ota",
        help="Extract partition images from an OTA zip or payload.bin.",
        allow_abbrev=False,
    )
    actions = ota.add_subparsers(dest="ota_command", required=True)

    extract = actions.add_parser(
        "extract",
        help="Pull partition images out of an OTA zip or payload.bin.",
        allow_abbrev=False,
    )
    extract.add_argument("input", type=existing_file, help="OTA .zip or payload.bin")
    extract.add_argument(
        "--output", "-o", dest="output_dir",
        help="Where to write the images (default: ./ota_extracted_<timestamp>)",
    )
    extract.add_argument(
        "--partitions", type=partition_list, default=DEFAULT_PARTITIONS,
        help=f"Comma-separated partitions to extract (default: {DEFAULT_PARTITIONS})",
    )

    inspect = actions.add_parser(
        "inspect",
        help="Mount an extracted filesystem image read-only to browse it.",
        allow_abbrev=False,
    )
    inspect.add_argument("image", type=existing_file, help="Extracted .img to mount")
    inspect.add_argument(
        "--mount-point", dest="mount_point",
        help="Where to mount it (default: /mnt/rootforge_inspect_<image>)",
    )


def dispatch(args: argparse.Namespace) -> int:
    if args.ota_command == "extract":
        # The output directory is passed as a flag here even though the script
        # takes it positionally: making it a flag is what removes the
        # "is this argument the output directory or a misplaced option?"
        # ambiguity at the CLI edge. The script's own positional form still
        # works for anyone calling it directly.
        script_args: List[str] = [args.input]
        if args.output_dir:
            script_args.append(args.output_dir)
        script_args += ["--partitions", args.partitions]
        return exec_script("extract_ota.sh", script_args)

    if args.ota_command == "inspect":
        script_args = [args.image]
        if args.mount_point:
            script_args.append(args.mount_point)
        return exec_script("inspect_partition_image.sh", script_args)

    raise AssertionError(f"no branch for ota command {args.ota_command!r}")
