"""rootforge.core.device — device detection and vendor-safety gating.

Queries a connected device once (via fastboot or adb) and returns a
`Device` describing what was found. Vendors whose unlock/flash workflow
this repo refuses to automate (Samsung, Xiaomi) raise
`UnsupportedVendorError` instead of guessing — the same refusal reasoning
and wording `unlock_bootloader.sh` already uses, kept in one place so the
CLI and that script never disagree about which vendors are unsupported or
why.
"""
from __future__ import annotations

import re
import subprocess
from dataclasses import dataclass
from typing import List, Optional


class UnsupportedVendorError(Exception):
    """A detected device's vendor requires an out-of-band workflow this repo does not automate."""

    def __init__(self, vendor: str, instructions: str):
        self.vendor = vendor
        self.instructions = instructions
        super().__init__(f"{vendor}: {instructions}")


# Mirrors unlock_bootloader.sh's IS_SAMSUNG/IS_XIAOMI refusal messages.
VENDOR_REFUSALS = {
    "samsung": (
        "Samsung devices unlock OEM bootloader in Settings > Developer "
        "Options > OEM Unlocking, then flash via Download Mode with "
        "Odin/Heimdall — not fastboot. Automating this risks tripping Knox "
        "permanently with no rollback. See devices/<codename>/hardware-notes.md."
    ),
    "xiaomi": (
        "Xiaomi/Redmi devices require a Mi Unlock permit tied to your Mi "
        "account, with a vendor-enforced waiting period (often 7+ days for "
        "new accounts). Complete that via the official Mi Unlock tool "
        "first; RootForge can proceed once fastboot reports unlocked: yes."
    ),
}


@dataclass
class Device:
    serial: Optional[str]
    mode: str  # "fastboot", "adb", or "none"
    product: Optional[str] = None
    codename: Optional[str] = None
    current_slot: Optional[str] = None
    unlocked: Optional[bool] = None
    vendor: Optional[str] = None
    root_method: Optional[str] = None  # "magisk", "kernelsu", or None


def _run(cmd: List[str]) -> str:
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return ""
    return result.stdout + result.stderr


def _fastboot(serial: Optional[str]) -> List[str]:
    cmd = ["fastboot"]
    if serial:
        cmd += ["-s", serial]
    return cmd


def _adb(serial: Optional[str]) -> List[str]:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    return cmd


def _parse_getvar(output: str, key: str) -> Optional[str]:
    # `fastboot getvar all` prefixes every line with "(bootloader) ", so the
    # key is never at column 0 — match "key:" preceded by line-start or
    # whitespace instead of anchoring to it (same substance as
    # unlock_bootloader.sh's `grep -oP '(?<=key: ).*'`).
    match = re.search(rf"(?m)(?:^|\s){re.escape(key)}:\s*(.+)$", output)
    return match.group(1).strip() if match else None


def _detect_root_method(serial: Optional[str]) -> Optional[str]:
    if "magisk" in _run(_adb(serial) + ["shell", "which", "magisk"]):
        return "magisk"
    if "ksud" in _run(_adb(serial) + ["shell", "which", "ksud"]):
        return "kernelsu"
    return None


def detect_device(serial: Optional[str] = None) -> Device:
    """Detect the currently connected device's fastboot/adb state.

    Raises UnsupportedVendorError for vendors this repo refuses to
    automate — callers should catch this and stop rather than proceed.
    """
    fb_out = _run(_fastboot(serial) + ["devices"])
    if fb_out.strip():
        getvar_out = _run(_fastboot(serial) + ["getvar", "all"])
        vendor = None
        lowered = getvar_out.lower()
        if "samsung" in lowered:
            vendor = "samsung"
        elif "xiaomi" in lowered or "redmi" in lowered:
            vendor = "xiaomi"

        product = _parse_getvar(getvar_out, "product")
        unlocked_raw = _parse_getvar(getvar_out, "unlocked")
        device = Device(
            serial=serial,
            mode="fastboot",
            product=product,
            codename=product,
            current_slot=_parse_getvar(getvar_out, "current-slot"),
            unlocked=(unlocked_raw == "yes") if unlocked_raw is not None else None,
            vendor=vendor,
        )
        if vendor in VENDOR_REFUSALS:
            raise UnsupportedVendorError(vendor, VENDOR_REFUSALS[vendor])
        return device

    adb_out = _run(_adb(serial) + ["devices"])
    connected = [
        line for line in adb_out.splitlines()[1:] if line.strip() and "device" in line
    ]
    if connected:
        codename = _run(_adb(serial) + ["shell", "getprop", "ro.product.device"]).strip()
        return Device(
            serial=serial,
            mode="adb",
            codename=codename or None,
            root_method=_detect_root_method(serial),
        )

    return Device(serial=serial, mode="none")


def cmd_show(serial: Optional[str] = None) -> int:
    try:
        device = detect_device(serial)
    except UnsupportedVendorError as exc:
        print("DETECTED DEVICE")
        print(f"  Vendor: {exc.vendor.capitalize()}")
        print("  Automatic fastboot workflow unavailable. RootForge cannot safely continue.")
        print(f"  Required external workflow: {exc.instructions}")
        print("  Do not guess.")
        return 2

    if device.mode == "none":
        print("No device detected in fastboot or adb mode.")
        print("Connect a device and either put it in bootloader mode")
        print("(adb reboot bootloader) or ensure `adb devices` sees it.")
        return 1

    print("DETECTED DEVICE")
    print(f"  Mode:         {device.mode}")
    if device.codename:
        print(f"  Codename:     {device.codename}")
    if device.current_slot:
        print(f"  Active slot:  {device.current_slot}")
    if device.unlocked is not None:
        print(f"  Unlocked:     {'yes' if device.unlocked else 'no'}")
    if device.root_method:
        print(f"  Root method:  {device.root_method}")
    if device.serial:
        print(f"  Serial:       {device.serial}")
    return 0
