"""Device capability profiling shared by the RootForge CLI and shell tools."""
from __future__ import annotations

import json
import re
import subprocess
from dataclasses import asdict, dataclass, field
from typing import Dict, List, Optional

VENDOR_PATTERNS = {
    "samsung": re.compile(r"\bsamsung\b|\bsm-[a-z0-9]+", re.I),
    "xiaomi": re.compile(r"\bxiaomi\b|\bredmi\b|\bpoco\b", re.I),
}


@dataclass
class DeviceProfile:
    serial: str
    mode: str
    codename: Optional[str] = None
    model: Optional[str] = None
    vendor: Optional[str] = None
    slot_mode: str = "unknown"
    current_slot: Optional[str] = None
    bootloader_unlocked: Optional[bool] = None
    root_method: Optional[str] = None
    raw: Dict[str, str] = field(default_factory=dict)

    @property
    def supported(self) -> bool:
        return self.vendor not in ("samsung", "xiaomi")

    def refusal_message(self) -> Optional[str]:
        if self.vendor == "samsung":
            return ("DETECTED DEVICE\nVendor: Samsung\n"
                    "RootForge cannot safely continue with the automatic fastboot workflow.\n"
                    "Samsung devices use Download Mode and unlocking trips Knox. Use the appropriate Samsung workflow.")
        if self.vendor == "xiaomi":
            return ("DETECTED DEVICE\nVendor: Xiaomi\n"
                    "RootForge cannot safely continue with the automatic fastboot workflow.\n"
                    "Xiaomi devices require the official Mi Unlock process and an approved wait period.")
        return None

    def as_dict(self) -> Dict[str, object]:
        return asdict(self) | {"supported": self.supported, "refusal_message": self.refusal_message()}


def _run(argv: List[str], timeout: int = 10) -> Optional[str]:
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return result.stdout if result.returncode == 0 else None


def _clean(value: Optional[str]) -> Optional[str]:
    if value is None:
        return None
    value = value.strip().strip('"')
    return value or None


def _vendor(*values: Optional[str]) -> Optional[str]:
    text = " ".join(v for v in values if v)
    for name, pattern in VENDOR_PATTERNS.items():
        if pattern.search(text):
            return name
    return None


def _detect_root_method(serial: str) -> Optional[str]:
    magisk = _run(["adb", "-s", serial, "shell", "su", "-c", "magisk -v"])
    if magisk and magisk.strip():
        return "magisk"
    kernelsu = _run(["adb", "-s", serial, "shell", "su", "-c", "ksud -V"])
    if kernelsu and kernelsu.strip():
        return "kernelsu"
    which_su = _run(["adb", "-s", serial, "shell", "which", "su"])
    if which_su is None:
        return None
    return "none" if not which_su.strip() else None


def _parse_fastboot(output: str) -> Dict[str, str]:
    data: Dict[str, str] = {}
    for line in output.splitlines():
        line = re.sub(r"^\s*\(bootloader\)\s*", "", line.strip(), flags=re.I)
        if ":" in line:
            key, value = line.split(":", 1)
            data[key.strip().lower().replace("_", "-")] = value.strip()
    return data


def profile_fastboot(serial: str) -> DeviceProfile:
    output = _run(["fastboot", "-s", serial, "getvar", "all"])
    if output is None:
        return DeviceProfile(serial=serial, mode="fastboot")
    raw = _parse_fastboot(output)
    product = _clean(raw.get("product")) or _clean(raw.get("sku"))
    slot = _clean(raw.get("current-slot"))
    unlocked_raw = _clean(raw.get("unlocked")) or _clean(raw.get("secure"))
    unlocked = None
    if unlocked_raw:
        if unlocked_raw.lower() in ("yes", "true", "1", "unlocked"):
            unlocked = True
        elif unlocked_raw.lower() in ("no", "false", "0", "locked"):
            unlocked = False
    return DeviceProfile(serial=serial, mode="fastboot", codename=product,
                         vendor=_vendor(output), slot_mode="ab" if slot else "single",
                         current_slot=slot.lower().lstrip("_") if slot else None,
                         bootloader_unlocked=unlocked, raw=raw)


def profile_adb(serial: str) -> DeviceProfile:
    props = {
        "codename": "ro.product.device",
        "model": "ro.product.model",
        "manufacturer": "ro.product.manufacturer",
        "brand": "ro.product.brand",
        "slot_suffix": "ro.boot.slot_suffix",
        "flash_locked": "ro.boot.flash.locked",
    }
    raw: Dict[str, str] = {}
    answers: Dict[str, Optional[str]] = {}
    for key, prop in props.items():
        value = _run(["adb", "-s", serial, "shell", "getprop", prop])
        if value is None:
            return DeviceProfile(serial=serial, mode="adb")
        answers[key] = value
        clean = _clean(value)
        if clean is not None:
            raw[prop] = clean
    slot_answer = answers["slot_suffix"]
    suffix = _clean(slot_answer)
    lock_value = _clean(answers["flash_locked"])
    unlocked = None if lock_value not in ("0", "1") else lock_value == "0"
    manufacturer = _clean(answers["manufacturer"])
    brand = _clean(answers["brand"])
    model = _clean(answers["model"])
    return DeviceProfile(
        serial=serial, mode="adb", codename=_clean(answers["codename"]), model=model,
        vendor=_vendor(manufacturer, brand, model),
        slot_mode="ab" if suffix else "single",
        current_slot=suffix.lstrip("_") if suffix else None,
        bootloader_unlocked=unlocked, root_method=_detect_root_method(serial), raw=raw,
    )


def profile_device(serial: str, mode: str) -> DeviceProfile:
    if mode == "adb":
        return profile_adb(serial)
    if mode == "fastboot":
        return profile_fastboot(serial)
    raise ValueError(f"unsupported device mode: {mode}")


def cmd_show(serial: Optional[str] = None) -> int:
    from rootforge.core.cli import _select_device
    try:
        resolved, mode = _select_device(serial)
    except LookupError as exc:
        print(f"rootforge: error: {exc}")
        return 1
    profile = profile_device(resolved, mode)
    print(json.dumps(profile.as_dict(), indent=2))
    message = profile.refusal_message()
    if message:
        print(message)
        return 1
    return 0
