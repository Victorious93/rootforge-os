"""rootforge.core.ota — OTA/payload inspection and extraction.

`ota inspect` identifies an OTA package without extracting it. `ota extract`
wraps `extract_ota.sh` (which itself wraps payload-dumper-go, self-installing
it if missing) rather than reimplementing OTA parsing; this module's
value-add is structured logging and a SHA-256 for each requested partition
image that was produced. `ota inspect-image` exposes the separate read-only
loop-mount tool for an extracted partition image.

Every public command goes through `dispatch()` to `cmd_inspect` /
`cmd_extract`, so the hashes and events are recorded on the real CLI path and
not only when these functions are called directly.
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
import time
import zipfile
from pathlib import Path
from typing import Optional

from rootforge.core.log import Logger
from rootforge.core.runner import exec_script

DEFAULT_PARTITIONS = "boot,init_boot,vendor_boot,dtbo,vbmeta"
PARTITION_RE = re.compile(r"^[A-Za-z0-9_-]+$")


def partition_list(value: str) -> str:
    if not value.strip():
        raise argparse.ArgumentTypeError("partition list cannot be empty")
    if value.rstrip().endswith(","):
        raise argparse.ArgumentTypeError("partition list has a trailing comma")
    parts = [part.strip() for part in value.split(",")]
    if any(not part or not PARTITION_RE.fullmatch(part) for part in parts):
        raise argparse.ArgumentTypeError("partition names may contain only letters, digits, underscores, and hyphens")
    return ",".join(parts)


def existing_file(value: str) -> str:
    path = Path(value)
    if not path.is_file():
        raise argparse.ArgumentTypeError(f"input file not found: {value}")
    if path.stat().st_size == 0:
        raise argparse.ArgumentTypeError(f"input file is empty: {value}")
    return str(path)


def add_parser(subparsers) -> None:
    parser = subparsers.add_parser("ota", help="Inspect and extract Android OTA images.", allow_abbrev=False)
    actions = parser.add_subparsers(dest="ota_command", required=True)
    inspect = actions.add_parser(
        "inspect", help="Identify an OTA zip or payload.bin without extracting it.", allow_abbrev=False,
    )
    inspect.add_argument("input", type=existing_file)
    image = actions.add_parser(
        "inspect-image", help="Inspect an extracted partition image (read-only loop mount).",
        allow_abbrev=False,
    )
    image.add_argument("image", type=existing_file)
    image.add_argument("--mount-point", default=None)
    extract = actions.add_parser("extract", help="Extract partitions from an OTA package.", allow_abbrev=False)
    extract.add_argument("input", type=existing_file)
    extract.add_argument("output_dir", nargs="?", default=None)
    extract.add_argument("-o", "--output", "--output-dir", dest="output_option", default=None)
    extract.add_argument("--partitions", type=partition_list, default=DEFAULT_PARTITIONS)


def dispatch(args: argparse.Namespace) -> int:
    if args.ota_command == "inspect":
        return cmd_inspect(args.input)
    if args.ota_command == "inspect-image":
        argv = [args.image]
        if args.mount_point:
            argv.append(args.mount_point)
        return exec_script("inspect_partition_image.sh", argv)
    if args.ota_command == "extract":
        if args.output_option and args.output_dir and args.output_option != args.output_dir:
            print(
                f"rootforge: error: two different output directories were given "
                f"({args.output_dir!r} and {args.output_option!r}); use one.",
                file=sys.stderr,
            )
            return 2
        return cmd_extract(args.input, args.output_option or args.output_dir, args.partitions)
    raise AssertionError(f"unknown OTA command: {args.ota_command!r}")


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cmd_inspect(input_path: str) -> int:
    path = Path(input_path)
    if not path.is_file():
        print(f"Not a file: {input_path}")
        return 1

    print(f"Input:   {path}")
    print(f"Size:    {path.stat().st_size} bytes")
    print(f"SHA-256: {_sha256_file(path)}")

    if zipfile.is_zipfile(path):
        print("Type:    zip (OTA package)")
        with zipfile.ZipFile(path) as zf:
            names = zf.namelist()
            has_payload = "payload.bin" in names
            print(f"payload.bin at zip root: {'yes' if has_payload else 'no'}")
            if has_payload:
                info = zf.getinfo("payload.bin")
                print(f"payload.bin size: {info.file_size} bytes")
                print("Run `rootforge ota extract` to pull partition images out.")
            else:
                print("No payload.bin at zip root — likely a pre-A/B (full image) zip,")
                print("not a payload-based OTA. Top-level entries:")
                for name in sorted({n.split("/")[0] for n in names})[:20]:
                    print(f"  {name}")
    else:
        print("Type:    raw payload.bin (or unrecognized) — pass directly to `rootforge ota extract`.")

    return 0


def cmd_extract(input_path: str, output_dir: Optional[str] = None,
                partitions: Optional[str] = None) -> int:
    input_file = Path(input_path)
    if not input_file.is_file():
        print(f"Not a file: {input_path}")
        return 1
    # Decide the output directory here (the script would otherwise pick its
    # own default), so this function knows exactly where to look afterwards.
    output = output_dir or f"./ota_extracted_{time.strftime('%Y%m%d_%H%M%S')}"
    requested = (partitions or DEFAULT_PARTITIONS).split(",")

    logger = Logger("ota-extract", echo=False)
    logger.info(
        "extract started",
        input=str(input_file),
        input_sha256=_sha256_file(input_file),
        output_dir=output,
        partitions=requested,
    )

    returncode = exec_script("extract_ota.sh", [str(input_file), output, "--partitions", ",".join(requested)])
    if returncode != 0:
        logger.error("extract failed", returncode=returncode)
        return returncode

    # Hash only the partitions that were asked for. Globbing every *.img would
    # also report (and appear to vouch for) stale files left in a reused
    # output directory.
    extracted = {}
    absent = []
    out_path = Path(output)
    for partition in requested:
        image = out_path / f"{partition}.img"
        if image.is_file() and image.stat().st_size > 0:
            extracted[image.name] = _sha256_file(image)
            print(f"  {image.name}  SHA-256: {extracted[image.name]}")
        else:
            absent.append(partition)
    for partition in absent:
        print(f"  {partition}.img  not produced (the package may not contain this partition)")

    logger.info("extract finished", extracted=extracted, not_produced=absent, log_path=str(logger.path))
    return 0
