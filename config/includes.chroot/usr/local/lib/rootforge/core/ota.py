"""rootforge.core.ota — OTA/payload extraction wrapper.

Wraps `extract_ota.sh` (which itself wraps payload-dumper-go, self-
installing it if missing) as a subprocess rather than reimplementing OTA
zip / payload.bin parsing. This module's value-add: structured logging and
a SHA-256 recorded per extracted partition image.
"""
from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import subprocess
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
    inspect = actions.add_parser("inspect", help="Inspect a partition image.", allow_abbrev=False)
    inspect.add_argument("input", type=existing_file)
    inspect.add_argument("--mount-point", default=None)
    extract = actions.add_parser("extract", help="Extract partitions from an OTA package.", allow_abbrev=False)
    extract.add_argument("input", type=existing_file)
    extract.add_argument("output_dir", nargs="?", default=None)
    extract.add_argument("-o", "--output", "--output-dir", dest="output_option", default=None)
    extract.add_argument("--partitions", type=partition_list, default=DEFAULT_PARTITIONS)


def dispatch(args: argparse.Namespace) -> int:
    if args.ota_command == "inspect":
        argv = [args.input]
        if args.mount_point:
            argv.append(args.mount_point)
        return exec_script("inspect_partition_image.sh", argv)
    if args.ota_command == "extract":
        output = args.output_option or args.output_dir
        argv = [args.input]
        if output:
            argv.append(output)
        argv += ["--partitions", args.partitions]
        return exec_script("extract_ota.sh", argv)
    raise AssertionError(f"unknown OTA command: {args.ota_command!r}")


def _script_path(name: str) -> Path:
    # Same lookup as rootforge.core.backup/module — usr/local in either a
    # real install or a repo checkout is parents[3] from this file.
    candidate = Path(__file__).resolve().parents[3] / "bin" / name
    if candidate.is_file():
        return candidate
    found = shutil.which(name)
    if found:
        return Path(found)
    raise FileNotFoundError(
        f"{name} not found next to this module ({candidate}) or on PATH — "
        "check your RootForge install."
    )


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


def cmd_extract(input_path: str, output_dir: str, partitions: Optional[str] = None) -> int:
    input_file = Path(input_path)
    if not input_file.is_file():
        print(f"Not a file: {input_path}")
        return 1
    try:
        script = _script_path("extract_ota.sh")
    except FileNotFoundError as exc:
        print(exc)
        return 1

    logger = Logger("ota-extract", echo=False)
    logger.info(
        "extract started",
        input=str(input_file),
        input_sha256=_sha256_file(input_file),
        output_dir=output_dir,
        partitions=partitions,
    )

    cmd = [str(script), str(input_file), output_dir]
    if partitions:
        cmd += ["--partitions", partitions]
    result = subprocess.run(cmd)
    if result.returncode != 0:
        logger.error("extract failed", returncode=result.returncode)
        return result.returncode

    extracted = {}
    out_path = Path(output_dir)
    if out_path.is_dir():
        for img in sorted(out_path.glob("*.img")):
            extracted[img.name] = _sha256_file(img)
            print(f"  {img.name}  SHA-256: {extracted[img.name]}")

    logger.info("extract finished", extracted=extracted, log_path=str(logger.path))
    return 0
