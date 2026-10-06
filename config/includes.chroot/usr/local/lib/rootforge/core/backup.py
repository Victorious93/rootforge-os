"""rootforge.core.backup — verify a partition backup against its SHA256SUMS.

backup_partitions.sh writes `SHA256SUMS` (sha256sum format, bare file names)
next to the images, and restore_partitions.sh checks it before flashing.
This module lets an operator run the same check on demand, without a device
attached, and reports per-image results instead of sha256sum's single exit
code. Creating and restoring backups stays in the scripts.
"""
from __future__ import annotations

import hashlib
import os
import re
from pathlib import Path
from typing import Dict, List, Tuple

SUMS_NAME = "SHA256SUMS"
_LINE_RE = re.compile(r"^([0-9a-fA-F]{64}) [ *](.+)$")


def backup_dir(codename: str, timestamp: str) -> Path:
    home = Path(os.environ.get("ROOTFORGE_HOME", str(Path.home() / "rootforge")))
    return home / "devices" / codename / "backups" / timestamp


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_sums(text: str) -> Tuple[Dict[str, str], List[str]]:
    """Return ({name: digest}, [malformed lines]).

    A name containing a path separator is treated as malformed: the sums file
    sits inside the backup directory, and a name like ../x would make verify
    hash (and vouch for) a file outside it.
    """
    entries: Dict[str, str] = {}
    bad: List[str] = []
    for line in text.splitlines():
        if not line.strip():
            continue
        match = _LINE_RE.match(line)
        if not match or "/" in match.group(2) or match.group(2) in (".", ".."):
            bad.append(line)
            continue
        entries[match.group(2)] = match.group(1).lower()
    return entries, bad


def verify_backup(directory: Path) -> int:
    """Print one status line per image; return 0 only if every image matches."""
    sums_path = directory / SUMS_NAME
    if not directory.is_dir():
        print(f"No such backup: {directory}")
        return 1
    if not sums_path.is_file():
        print(f"No {SUMS_NAME} in {directory} — integrity cannot be verified.")
        print("The backup predates checksums, or was not made by backup_partitions.sh.")
        return 1

    entries, bad = parse_sums(sums_path.read_text())
    failures = len(bad)
    for line in bad:
        print(f"[MALFORMED] {line}")
    if not entries and not bad:
        print(f"{SUMS_NAME} is empty — nothing was verified.")
        return 1

    for name, expected in sorted(entries.items()):
        image = directory / name
        if not image.is_file():
            print(f"[MISSING]   {name}")
            failures += 1
        elif sha256_file(image) == expected:
            print(f"[OK]        {name}")
        else:
            print(f"[MISMATCH]  {name}")
            failures += 1

    print()
    if failures:
        print(f"{failures} problem(s) found; do not restore this backup.")
        return 1
    print(f"All {len(entries)} image(s) verified OK.")
    return 0


def cmd_verify(codename: str, timestamp: str) -> int:
    return verify_backup(backup_dir(codename, timestamp))
