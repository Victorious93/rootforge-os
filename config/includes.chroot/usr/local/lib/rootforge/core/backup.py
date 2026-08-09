"""rootforge.core.backup — SHA-256-verified partition backups.

Wraps `backup_partitions.sh` / `restore_partitions.sh` rather than
reimplementing them: the actual `fastboot fetch` / `adb root + dd` /
`fastboot flash` logic stays in those scripts, invoked as subprocesses
with stdio inherited (so `restore_partitions.sh`'s own typed `RESTORE`
confirmation prompt still works normally). This module's own job is the
part the audit found missing: a JSON manifest recording a SHA-256
checksum per backed-up partition image, and a `verify` command that
re-hashes and compares.
"""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from typing import List, Optional

MANIFEST_NAME = "manifest.json"


def _rootforge_home() -> Path:
    return Path(os.environ.get("ROOTFORGE_HOME", str(Path.home() / "rootforge")))


def _backups_root(codename: str) -> Path:
    return _rootforge_home() / "devices" / codename / "backups"


def _backup_dir(codename: str, timestamp: str) -> Path:
    return _backups_root(codename) / timestamp


def _script_path(name: str) -> Path:
    # This file lives at .../usr/local/lib/rootforge/core/backup.py in both
    # a real install and a repo checkout (config/includes.chroot/usr/local/
    # lib/rootforge/core/backup.py) — parents[3] is usr/local in either
    # case, so the same relative lookup finds the sibling script both ways.
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


def _write_manifest(backup_dir: Path, codename: str, timestamp: str) -> dict:
    partitions = {}
    for img in sorted(backup_dir.glob("*.img")):
        partitions[img.stem] = {
            "sha256": _sha256_file(img),
            "size_bytes": img.stat().st_size,
        }
    manifest = {
        "codename": codename,
        "timestamp": timestamp,
        "manifest_written_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "partitions": partitions,
    }
    (backup_dir / MANIFEST_NAME).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return manifest


def _newest_backup_dir(codename: str) -> Optional[Path]:
    root = _backups_root(codename)
    if not root.is_dir():
        return None
    candidates = [d for d in root.iterdir() if d.is_dir()]
    if not candidates:
        return None
    return max(candidates, key=lambda d: d.stat().st_mtime)


def cmd_create(codename: str, serial: Optional[str] = None) -> int:
    try:
        script = _script_path("backup_partitions.sh")
    except FileNotFoundError as exc:
        print(exc)
        return 1

    cmd = [str(script), codename]
    if serial:
        cmd.append(serial)
    result = subprocess.run(cmd)  # stdio inherited — script prints its own progress/log path
    if result.returncode != 0:
        return result.returncode

    backup_dir = _newest_backup_dir(codename)
    if backup_dir is None:
        print(f"backup_partitions.sh exited 0 but no backup directory was found under {_backups_root(codename)}")
        return 1

    manifest = _write_manifest(backup_dir, codename, backup_dir.name)
    count = len(manifest["partitions"])
    print(f"Wrote SHA-256 manifest for {count} partition(s): {backup_dir / MANIFEST_NAME}")
    return 0


def cmd_list(codename: Optional[str] = None) -> int:
    codenames: List[str] = (
        [codename] if codename else sorted(d.name for d in (_rootforge_home() / "devices").glob("*") if d.is_dir())
    )
    if not codenames:
        print(f"No devices with backups found under {_rootforge_home() / 'devices'}")
        return 0

    for cn in codenames:
        root = _backups_root(cn)
        if not root.is_dir():
            continue
        print(f"{cn}:")
        for backup_dir in sorted(root.iterdir()):
            if not backup_dir.is_dir():
                continue
            images = sorted(backup_dir.glob("*.img"))
            has_manifest = (backup_dir / MANIFEST_NAME).is_file()
            tag = "manifest" if has_manifest else "no manifest"
            print(f"  {backup_dir.name}  ({len(images)} image(s), {tag})")
    return 0


def cmd_verify(codename: str, timestamp: str) -> int:
    backup_dir = _backup_dir(codename, timestamp)
    manifest_path = backup_dir / MANIFEST_NAME
    if not manifest_path.is_file():
        print(f"No {MANIFEST_NAME} at {backup_dir}")
        print("This backup predates SHA-256 manifests, or wasn't created with `rootforge backup create`.")
        return 1

    manifest = json.loads(manifest_path.read_text())
    failures = 0
    for name, entry in sorted(manifest.get("partitions", {}).items()):
        img_path = backup_dir / f"{name}.img"
        if not img_path.is_file():
            print(f"[MISSING]  {name}.img")
            failures += 1
            continue
        actual = _sha256_file(img_path)
        if actual == entry["sha256"]:
            print(f"[OK]       {name}.img")
        else:
            print(f"[MISMATCH] {name}.img (expected {entry['sha256'][:12]}…, got {actual[:12]}…)")
            failures += 1

    print()
    if failures:
        print(f"{failures} partition(s) failed verification.")
    else:
        print("All partitions verified OK.")
    return 1 if failures else 0


def cmd_restore(codename: str, timestamp: str, serial: Optional[str] = None) -> int:
    backup_dir = _backup_dir(codename, timestamp)
    manifest_path = backup_dir / MANIFEST_NAME
    if manifest_path.is_file():
        print("Verifying backup integrity before restore...")
        if cmd_verify(codename, timestamp) != 0:
            print()
            print("WARNING: integrity verification failed above. Proceeding will let")
            print("restore_partitions.sh's own confirmation prompt decide whether to flash")
            print("a backup that no longer matches its recorded checksums.")
        print()
    else:
        print(f"No {MANIFEST_NAME} for this backup — integrity cannot be verified before restore.")
        print()

    try:
        script = _script_path("restore_partitions.sh")
    except FileNotFoundError as exc:
        print(exc)
        return 1

    cmd = [str(script), codename, timestamp]
    if serial:
        cmd.append(serial)
    result = subprocess.run(cmd)  # stdio inherited — this is what shows the RESTORE prompt
    return result.returncode
