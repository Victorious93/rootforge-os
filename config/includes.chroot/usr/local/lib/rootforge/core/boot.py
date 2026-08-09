"""rootforge.core.boot — unified entrypoint for the boot-image toolchain.

Wraps magiskboot/avbtool as subprocesses using the exact invocation
patterns already proven elsewhere in this repo (kernelsu_patch_boot.sh's
`magiskboot unpack`/`repack`, setup_rooted_avd.sh's `magiskboot cpio`,
0085-avbtool.hook.chroot's `avbtool version`) — the actual unpack/repack/
cpio-patch/verify logic stays in those tools; this module's job is one CLI
entrypoint plus structured logging (tool version, what ran, output hash)
via rootforge.core.log.

Deliberately does NOT wrap mkbootimg/unpack_bootimg/repack_bootimg's own
flag surface here — those AOSP tools' arguments vary by boot image header
version in ways this module can't respell without guessing, so `inspect`/
`unpack`/`repack` go through magiskboot instead, whose two-command
unpack-then-repack shape is already proven in this codebase.
"""
from __future__ import annotations

import hashlib
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import List

from rootforge.core.log import Logger


def _require_tool(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise FileNotFoundError(
            f"{name} not found on PATH — it ships prebuilt on RootForge OS "
            "(0060-magiskboot.hook.chroot / 0085-avbtool.hook.chroot); "
            "install it manually if missing."
        )
    return path


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _tool_version(cmd: List[str]) -> str:
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        lines = (result.stdout + result.stderr).strip().splitlines()
        return lines[0] if lines else "unknown"
    except Exception:  # noqa: BLE001 - version capture is best-effort, never fatal
        return "unknown"


def cmd_inspect(img: str) -> int:
    img_path = Path(img)
    if not img_path.is_file():
        print(f"Not a file: {img}")
        return 1
    try:
        magiskboot = _require_tool("magiskboot")
    except FileNotFoundError as exc:
        print(exc)
        return 1

    logger = Logger("boot-inspect", echo=False)
    logger.info(
        "inspect started",
        image=str(img_path),
        image_sha256=_sha256_file(img_path),
        tool_version=_tool_version([magiskboot]),
    )

    with tempfile.TemporaryDirectory() as tmp:
        shutil.copy(img_path, Path(tmp) / "boot.img")
        result = subprocess.run(
            [magiskboot, "unpack", "boot.img"], cwd=tmp, capture_output=True, text=True
        )
        print(result.stdout, end="")
        print(result.stderr, end="")
        if result.returncode != 0:
            logger.error("inspect failed", returncode=result.returncode)
            return result.returncode

        print()
        print(f"Components extracted from {img_path.name}:")
        for component in sorted(Path(tmp).iterdir()):
            if component.name == "boot.img":
                continue
            print(f"  {component.name}  ({component.stat().st_size} bytes)")

    logger.info("inspect finished", log_path=str(logger.path))
    return 0


def cmd_unpack(img: str, out_dir: str) -> int:
    img_path = Path(img)
    out_path = Path(out_dir)
    if not img_path.is_file():
        print(f"Not a file: {img}")
        return 1
    try:
        magiskboot = _require_tool("magiskboot")
    except FileNotFoundError as exc:
        print(exc)
        return 1

    out_path.mkdir(parents=True, exist_ok=True)
    shutil.copy(img_path, out_path / "boot.img")

    logger = Logger("boot-unpack", echo=False)
    logger.info(
        "unpack started",
        image=str(img_path),
        image_sha256=_sha256_file(img_path),
        out_dir=str(out_path),
        tool_version=_tool_version([magiskboot]),
    )

    result = subprocess.run([magiskboot, "unpack", "boot.img"], cwd=str(out_path))
    if result.returncode != 0:
        logger.error("unpack failed", returncode=result.returncode)
        return result.returncode

    print(f"Unpacked into {out_path}")
    for component in sorted(out_path.iterdir()):
        print(f"  {component.name}")
    logger.info("unpack finished", log_path=str(logger.path))
    return 0


def cmd_repack(work_dir: str) -> int:
    work_path = Path(work_dir)
    if not (work_path / "boot.img").is_file():
        print(
            f"{work_path} has no boot.img — run `rootforge boot unpack` first "
            "(magiskboot repack needs the original as a template)."
        )
        return 1
    try:
        magiskboot = _require_tool("magiskboot")
    except FileNotFoundError as exc:
        print(exc)
        return 1

    logger = Logger("boot-repack", echo=False)
    logger.info(
        "repack started", work_dir=str(work_path), tool_version=_tool_version([magiskboot])
    )

    result = subprocess.run([magiskboot, "repack", "boot.img"], cwd=str(work_path))
    if result.returncode != 0:
        logger.error("repack failed", returncode=result.returncode)
        return result.returncode

    output = work_path / "new-boot.img"
    if output.is_file():
        output_hash = _sha256_file(output)
        print(f"Repacked: {output} (SHA-256: {output_hash})")
        logger.info(
            "repack finished",
            output=str(output),
            output_sha256=output_hash,
            log_path=str(logger.path),
        )
    else:
        print("magiskboot repack exited 0 but new-boot.img wasn't produced — check its output above.")
        logger.warn("repack produced no new-boot.img")
    return 0


def cmd_patch(work_dir: str, ramdisk: str, cpio_commands: List[str]) -> int:
    work_path = Path(work_dir)
    ramdisk_path = work_path / ramdisk
    if not ramdisk_path.is_file():
        print(f"{ramdisk_path} not found — run `rootforge boot unpack` first.")
        return 1
    if not cpio_commands:
        print(
            "No cpio commands given — e.g. rootforge boot patch <dir> ramdisk.cpio "
            "-- 'add 0750 init magiskinit'"
        )
        return 1
    try:
        magiskboot = _require_tool("magiskboot")
    except FileNotFoundError as exc:
        print(exc)
        return 1

    logger = Logger("boot-patch", echo=False)
    logger.info(
        "patch started",
        work_dir=str(work_path),
        ramdisk=ramdisk,
        commands=cpio_commands,
        tool_version=_tool_version([magiskboot]),
    )

    result = subprocess.run([magiskboot, "cpio", ramdisk, *cpio_commands], cwd=str(work_path))
    if result.returncode != 0:
        logger.error("patch failed", returncode=result.returncode)
        return result.returncode

    output_hash = _sha256_file(ramdisk_path)
    print(f"Patched {ramdisk_path} (SHA-256: {output_hash})")
    logger.info("patch finished", output_sha256=output_hash, log_path=str(logger.path))
    return 0


def cmd_verify(img: str) -> int:
    img_path = Path(img)
    if not img_path.is_file():
        print(f"Not a file: {img}")
        return 1
    try:
        avbtool = _require_tool("avbtool")
    except FileNotFoundError as exc:
        print(exc)
        return 1

    logger = Logger("boot-verify", echo=False)
    logger.info(
        "verify started",
        image=str(img_path),
        image_sha256=_sha256_file(img_path),
        tool_version=_tool_version([avbtool, "version"]),
    )

    result = subprocess.run([avbtool, "verify_image", "--image", str(img_path)])
    ok = result.returncode == 0
    logger.info("verify finished", ok=ok, returncode=result.returncode, log_path=str(logger.path))
    if ok:
        print("AVB verification passed.")
    else:
        print(f"AVB verification failed or image is unsigned (avbtool exit {result.returncode}).")
    return result.returncode
