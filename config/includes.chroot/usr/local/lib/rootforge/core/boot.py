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
import argparse
import shutil
import subprocess
import tempfile
import re
from pathlib import Path
from typing import List

from rootforge.core.log import Logger
from rootforge.core.runner import exec_script

def release_tag(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", value):
        raise argparse.ArgumentTypeError("release tag cannot contain a path or a different repository reference")
    return value


def device_codename(value: str) -> str:
    if value in (".", "..") or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", value):
        raise argparse.ArgumentTypeError("device codename must be a filename-safe component")
    return value


def android_version(value: str) -> str:
    if not re.fullmatch(r"[1-9][0-9]?", value):
        raise argparse.ArgumentTypeError("Android version must be a one- or two-digit API release number")
    return value


def existing_image(value: str) -> str:
    path = Path(value)
    if not path.is_file():
        raise argparse.ArgumentTypeError(f"stock boot image not found: {value}")
    if path.stat().st_size == 0:
        raise argparse.ArgumentTypeError(f"stock boot image is empty: {value}")
    return str(path)


def add_parser(subparsers) -> None:
    parser = subparsers.add_parser("boot", help="Patch a boot image with KernelSU or inspect boot images.", allow_abbrev=False)
    actions = parser.add_subparsers(dest="boot_command", required=True)
    patch = actions.add_parser("patch", help="Patch a stock boot image with KernelSU.", allow_abbrev=False)
    patch.add_argument("--stock-boot", required=True, type=existing_image)
    patch.add_argument("--android-version", required=True, type=android_version)
    patch.add_argument("--ksu-version", type=release_tag, default="latest")
    patch.add_argument("--device", type=device_codename, default=None)
    patch.add_argument("--serial", default=None)
    last = actions.add_parser("flash-last", help="Flash the most recently patched image.", allow_abbrev=False)
    last.add_argument("--device", type=device_codename, default=None)
    last.add_argument("--serial", default=None)
    actions.add_parser("inspect", help="Inspect a boot image.", allow_abbrev=False).add_argument("image")
    unpack = actions.add_parser("unpack", help="Unpack a boot image.", allow_abbrev=False)
    unpack.add_argument("image"); unpack.add_argument("out_dir")
    repack = actions.add_parser("repack", help="Repack a working directory.", allow_abbrev=False)
    repack.add_argument("work_dir")
    cpio = actions.add_parser(
        "cpio", help="Run magiskboot cpio commands against an unpacked ramdisk.", allow_abbrev=False,
    )
    cpio.add_argument("work_dir")
    cpio.add_argument("ramdisk")
    cpio.add_argument(
        "cpio_commands", nargs=argparse.REMAINDER,
        help="magiskboot cpio commands, e.g. -- 'add 0750 init magiskinit'",
    )
    verify = actions.add_parser("verify", help="Verify an AVB image.", allow_abbrev=False)
    verify.add_argument("image")


def dispatch(args) -> int:
    if args.boot_command == "patch":
        argv = ["--stock-boot", args.stock_boot, "--android-version", args.android_version,
                "--ksu-version", args.ksu_version]
        if args.device:
            argv += ["--device", args.device]
        if args.serial:
            argv += ["--serial", args.serial]
        return exec_script("kernelsu_patch_boot.sh", argv)
    if args.boot_command == "flash-last":
        argv = ["--flash"]
        if args.device:
            argv += ["--device", args.device]
        if args.serial:
            argv += ["--serial", args.serial]
        return exec_script("kernelsu_patch_boot.sh", argv)
    if args.boot_command == "inspect": return cmd_inspect(args.image)
    if args.boot_command == "unpack": return cmd_unpack(args.image, args.out_dir)
    if args.boot_command == "repack": return cmd_repack(args.work_dir)
    if args.boot_command == "cpio":
        commands = args.cpio_commands[1:] if args.cpio_commands[:1] == ["--"] else args.cpio_commands
        return cmd_patch(args.work_dir, args.ramdisk, commands)
    if args.boot_command == "verify": return cmd_verify(args.image)
    raise AssertionError(f"unknown boot command: {args.boot_command!r}")


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
        logger.error("repack produced no new-boot.img")
        return 1
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
