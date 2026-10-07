"""rootforge.core.backup — the backup/restore integrity contract.

backup_partitions.sh captures images and writes `manifest.json` (version 1);
restore_partitions.sh restores only what this module has verified. Keeping
the rules here, in one place, is what makes them enforceable from both the
`rootforge backup` commands and the standalone scripts.

manifest.json (version 1)::

    {"manifest_version": 1, "trust": "captured" | "legacy-imported",
     "codename": "...", "timestamp": "...", "serial": "...",
     "created_at": "ISO-8601", "complete": true|false,
     "requested_partitions": ["boot", ...], "missing_partitions": [...],
     "device": {"product": ..., "slot_mode": ..., "current_slot": ...,
                "bootloader_unlocked": ..., "version_bootloader": ...},
     "entries": [{"partition": "boot", "file": "boot.img", "sha256": "...",
                  "size_bytes": 123, "method": "fastboot-fetch|adb-dd|unknown",
                  "slot": "a" | null}]}

Unknown device facts are null, never guessed. A directory is valid only if
every listed image exists as a regular, non-empty file inside it with the
recorded size and SHA-256, no listed name escapes the directory, no entry is
a symlink, and there is no `*.img` the manifest does not list. A backup with
only the older `SHA256SUMS` file is "legacy": it can be verified for
corruption but is never restorable until `backup import-legacy` records it
as `legacy-imported` — an explicit, labelled step, not a silent upgrade.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

MANIFEST_NAME = "manifest.json"
SUMS_NAME = "SHA256SUMS"
MANIFEST_VERSION = 1
TRUST_CAPTURED = "captured"
TRUST_LEGACY_IMPORTED = "legacy-imported"

_PARTITION_RE = re.compile(r"^[a-z0-9_]+$")
_SHA_RE = re.compile(r"^[0-9a-f]{64}$")
_SUMS_LINE_RE = re.compile(r"^([0-9a-fA-F]{64}) [ *](.+)$")


def rootforge_home() -> Path:
    return Path(os.environ.get("ROOTFORGE_HOME", str(Path.home() / "rootforge")))


def backup_dir(codename: str, timestamp: str) -> Path:
    return rootforge_home() / "devices" / codename / "backups" / timestamp


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


@dataclass
class VerifyResult:
    ok: bool = False
    kind: str = "none"              # "manifest" | "legacy-sums" | "none"
    trust: Optional[str] = None
    complete: Optional[bool] = None
    codename: Optional[str] = None
    timestamp: Optional[str] = None
    device: Dict[str, object] = field(default_factory=dict)
    problems: List[str] = field(default_factory=list)
    # One row per image examined: {"name", "status", ...}. Status is one of
    # OK, MISSING, MISMATCH, SIZE, EMPTY, SYMLINK, UNLISTED, MALFORMED.
    images: List[Dict[str, object]] = field(default_factory=list)
    # Verified entries only, ready to flash: partition, file, path, sha256,
    # size_bytes, method, slot. Empty unless every selected image passed.
    entries: List[Dict[str, object]] = field(default_factory=list)

    def as_dict(self) -> Dict[str, object]:
        return {
            "ok": self.ok, "kind": self.kind, "trust": self.trust,
            "complete": self.complete, "codename": self.codename,
            "timestamp": self.timestamp, "device": self.device,
            "problems": self.problems, "images": self.images, "entries": self.entries,
        }


def parse_sums(text: str) -> Tuple[Dict[str, str], List[str]]:
    """Parse sha256sum output into ({name: digest}, [malformed lines]).

    A name containing a path separator is malformed: the sums file sits
    inside the backup directory, and a name like ../x would make verify hash
    (and vouch for) a file outside it.
    """
    entries: Dict[str, str] = {}
    bad: List[str] = []
    for line in text.splitlines():
        if not line.strip():
            continue
        match = _SUMS_LINE_RE.match(line)
        if not match or "/" in match.group(2) or match.group(2) in (".", ".."):
            bad.append(line)
            continue
        entries[match.group(2)] = match.group(1).lower()
    return entries, bad


def _check_image(directory: Path, name: str, expected_sha: str,
                 expected_size: Optional[int]) -> Tuple[str, Optional[Path]]:
    """Return (status, path) for one listed image. status 'OK' means safe to flash."""
    path = directory / name
    if path.is_symlink():
        return "SYMLINK", None
    if not path.is_file():
        return "MISSING", None
    if path.resolve().parent != directory.resolve():
        return "SYMLINK", None
    size = path.stat().st_size
    if size == 0:
        return "EMPTY", None
    if expected_size is not None and size != expected_size:
        return "SIZE", None
    if sha256_file(path) != expected_sha:
        return "MISMATCH", None
    return "OK", path


def _unlisted_images(directory: Path, listed: Sequence[str]) -> List[str]:
    return sorted(p.name for p in directory.iterdir()
                  if p.name.lower().endswith(".img") and p.name not in listed)


def _validate_manifest(manifest: object) -> Tuple[List[Dict[str, object]], List[str]]:
    """Return (entries, problems). Problems make the whole manifest unusable."""
    problems: List[str] = []
    if not isinstance(manifest, dict):
        return [], ["manifest.json is not a JSON object"]
    if manifest.get("manifest_version") != MANIFEST_VERSION:
        problems.append(
            f"unsupported manifest_version {manifest.get('manifest_version')!r} "
            f"(this RootForge reads version {MANIFEST_VERSION})"
        )
    if manifest.get("trust") not in (TRUST_CAPTURED, TRUST_LEGACY_IMPORTED):
        problems.append(f"unknown trust value {manifest.get('trust')!r}")
    raw_entries = manifest.get("entries")
    if not isinstance(raw_entries, list) or not raw_entries:
        problems.append("manifest lists no images")
        return [], problems
    seen = set()
    entries: List[Dict[str, object]] = []
    for index, entry in enumerate(raw_entries):
        label = f"entry {index}"
        if not isinstance(entry, dict):
            problems.append(f"{label} is not an object")
            continue
        partition = entry.get("partition")
        if not isinstance(partition, str) or not _PARTITION_RE.match(partition):
            problems.append(f"{label}: invalid partition name {partition!r}")
            continue
        label = f"entry '{partition}'"
        if partition in seen:
            problems.append(f"{label}: listed more than once")
            continue
        seen.add(partition)
        if entry.get("file") != f"{partition}.img":
            problems.append(f"{label}: file must be exactly '{partition}.img', got {entry.get('file')!r}")
            continue
        sha = entry.get("sha256")
        if not isinstance(sha, str) or not _SHA_RE.match(sha):
            problems.append(f"{label}: invalid sha256")
            continue
        size = entry.get("size_bytes")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            problems.append(f"{label}: invalid size_bytes")
            continue
        entries.append(entry)
    return entries, problems


def check_backup(directory: Path, partitions: Optional[Sequence[str]] = None) -> VerifyResult:
    """Verify a backup directory. Never prints and never writes."""
    result = VerifyResult()
    if not directory.is_dir():
        result.problems.append(f"no such backup: {directory}")
        return result
    manifest_path = directory / MANIFEST_NAME
    sums_path = directory / SUMS_NAME

    if manifest_path.is_file():
        result.kind = "manifest"
        try:
            manifest = json.loads(manifest_path.read_text())
        except (OSError, ValueError) as exc:
            result.problems.append(f"{MANIFEST_NAME} could not be read: {exc}")
            return result
        entries, problems = _validate_manifest(manifest)
        result.problems += problems
        if problems:
            return result
        result.trust = manifest.get("trust")
        result.complete = manifest.get("complete")
        result.codename = manifest.get("codename")
        result.timestamp = manifest.get("timestamp")
        device = manifest.get("device")
        result.device = device if isinstance(device, dict) else {}

        by_partition = {str(e["partition"]): e for e in entries}
        selected = list(by_partition)
        if partitions is not None:
            selected = list(dict.fromkeys(partitions))
            unknown = [p for p in selected if p not in by_partition]
            for name in unknown:
                result.problems.append(f"'{name}' is not in this backup's manifest")
            selected = [p for p in selected if p in by_partition]
        verified: List[Dict[str, object]] = []
        for partition in selected:
            entry = by_partition[partition]
            status, path = _check_image(
                directory, str(entry["file"]), str(entry["sha256"]), int(entry["size_bytes"])
            )
            result.images.append({"name": entry["file"], "status": status})
            if status != "OK":
                result.problems.append(f"{entry['file']}: {status}")
            else:
                verified.append({
                    "partition": partition, "file": entry["file"], "path": str(path),
                    "sha256": entry["sha256"], "size_bytes": entry["size_bytes"],
                    "method": entry.get("method"), "slot": entry.get("slot"),
                })
        for name in _unlisted_images(directory, [str(e["file"]) for e in entries]):
            result.images.append({"name": name, "status": "UNLISTED"})
            result.problems.append(f"{name}: UNLISTED (not in the manifest; refusing to treat the backup as intact)")
        result.ok = not result.problems
        result.entries = verified if result.ok else []
        return result

    if sums_path.is_file():
        result.kind = "legacy-sums"
        result.trust = "legacy"
        try:
            sums, bad = parse_sums(sums_path.read_text())
        except OSError as exc:
            result.problems.append(f"{SUMS_NAME} could not be read: {exc}")
            return result
        for line in bad:
            result.images.append({"name": line, "status": "MALFORMED"})
            result.problems.append(f"malformed checksum line: {line}")
        if not sums and not bad:
            result.problems.append(f"{SUMS_NAME} is empty — nothing was verified")
            return result
        for name, expected in sorted(sums.items()):
            status, _ = _check_image(directory, name, expected, None)
            result.images.append({"name": name, "status": status})
            if status != "OK":
                result.problems.append(f"{name}: {status}")
        for name in _unlisted_images(directory, list(sums)):
            result.images.append({"name": name, "status": "UNLISTED"})
            result.problems.append(f"{name}: UNLISTED (not covered by {SUMS_NAME})")
        result.ok = not result.problems
        return result

    result.problems.append(
        f"no {MANIFEST_NAME} or {SUMS_NAME} in {directory} — integrity cannot be verified"
    )
    return result


def import_legacy(directory: Path, codename: str) -> Tuple[bool, str]:
    """Record a verified legacy (SHA256SUMS-only) backup as `legacy-imported`.

    The result is deliberately labelled: it proves the images still match the
    checksums taken at capture time, but nothing about which device or slot
    they came from, so restore demands an explicit opt-in for these.
    """
    if (directory / MANIFEST_NAME).exists():
        return False, f"{MANIFEST_NAME} already exists in {directory}; nothing to import"
    result = check_backup(directory)
    if result.kind != "legacy-sums":
        return False, f"{directory} is not a legacy SHA256SUMS backup"
    if not result.ok:
        return False, "the legacy backup does not verify, so it cannot be imported: " + "; ".join(result.problems)
    sums, _ = parse_sums((directory / SUMS_NAME).read_text())
    entries = []
    for name, digest in sorted(sums.items()):
        if not name.endswith(".img"):
            return False, f"cannot import: '{name}' is not an .img file"
        partition = name[: -len(".img")]
        if not _PARTITION_RE.match(partition):
            return False, f"cannot import: '{name}' is not a valid partition image name"
        entries.append({
            "partition": partition, "file": name, "sha256": digest,
            "size_bytes": (directory / name).stat().st_size,
            "method": "unknown", "slot": None,
        })
    manifest = {
        "manifest_version": MANIFEST_VERSION, "trust": TRUST_LEGACY_IMPORTED,
        "codename": codename, "timestamp": directory.name, "serial": None,
        "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "complete": None, "requested_partitions": None, "missing_partitions": None,
        "device": {}, "entries": entries,
    }
    tmp = directory / f".{MANIFEST_NAME}.tmp"
    tmp.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    os.replace(tmp, directory / MANIFEST_NAME)
    return True, (
        f"Imported {len(entries)} image(s) as '{TRUST_LEGACY_IMPORTED}'. Device identity and slot "
        "are unknown; restore will require --accept-legacy-import."
    )


def _render(result: VerifyResult) -> None:
    for problem in result.problems:
        if not any(problem.startswith(str(img["name"])) for img in result.images):
            print(problem)
    for img in sorted(result.images, key=lambda i: str(i["name"])):
        status = str(img["status"])
        print(f"[{status}]".ljust(12) + str(img["name"]))
    print()
    if not result.ok:
        print(f"{len(result.problems)} problem(s) found; do not restore this backup.")
        return
    count = len(result.images)
    print(f"All {count} image(s) verified OK.")
    if result.kind == "legacy-sums":
        print(f"Legacy backup (checksums only): it records no device identity. To make it "
              f"restorable, run `rootforge backup import-legacy`.")
    elif result.complete is False:
        print("Note: this backup is INCOMPLETE — some requested partitions were not captured.")
    if result.trust == TRUST_LEGACY_IMPORTED:
        print("Trust: legacy-imported (device and slot unknown).")


def cmd_verify(codename: str, timestamp: str, as_json: bool = False,
               partitions: Optional[Sequence[str]] = None) -> int:
    result = check_backup(backup_dir(codename, timestamp), partitions)
    if as_json:
        print(json.dumps(result.as_dict(), indent=2))
    else:
        _render(result)
    return 0 if result.ok else 1


def cmd_import_legacy(codename: str, timestamp: str) -> int:
    ok, message = import_legacy(backup_dir(codename, timestamp), codename)
    print(message)
    return 0 if ok else 1
