"""Device capability profiling shared by the RootForge CLI and shell tools.

Two separate questions are answered here and must not be conflated:

* "What do we know about this device?" -> DeviceProfile. Anything the tools
  did not report stays None/"unknown"; nothing is guessed.
* "May this specific write proceed?" -> DeviceProfile.write_blockers(). A
  write needs positive evidence (fastboot mode, a probe that returned data,
  a known identity, an unlocked bootloader, a known slot topology). Missing
  evidence blocks the write with an actionable reason. Brand alone is never
  evidence that a write is safe; the Samsung/Xiaomi refusals are kept
  because their unlock/flash flows are out-of-band for fastboot, not
  because other brands are vouched for.

Tool output notes (verified against AOSP fastboot.cpp): `fastboot getvar all`
prints "(bootloader) name: value" informational lines on *stderr*, and
variable names may carry an argument ("has-slot:boot", "partition-size:boot_a").
Both stdout and stderr are therefore captured and parsed.
"""
from __future__ import annotations

import json
import re
import subprocess
from dataclasses import asdict, dataclass, field
from typing import Dict, List, Optional, Sequence

VENDOR_PATTERNS = {
    "samsung": re.compile(r"\bsamsung\b|\bsm-[a-z0-9]+", re.I),
    "xiaomi": re.compile(r"\bxiaomi\b|\bredmi\b|\bpoco\b", re.I),
}

_VAR_KEY_RE = re.compile(r"^[a-z0-9][a-z0-9._-]*(?::[a-z0-9._-]+)*$")


@dataclass(frozen=True)
class ToolResult:
    """Outcome of one external tool invocation.

    returncode is None when the tool could not be run at all (not installed,
    timed out); `error` then says why. stdout and stderr are kept apart
    because fastboot reports data on stderr and binary streams must never be
    merged with it.
    """

    returncode: Optional[int]
    stdout: str = ""
    stderr: str = ""
    error: Optional[str] = None

    @property
    def ok(self) -> bool:
        return self.returncode == 0

    @property
    def text(self) -> str:
        return f"{self.stdout}\n{self.stderr}"


@dataclass
class DeviceProfile:
    serial: str
    mode: str
    codename: Optional[str] = None
    model: Optional[str] = None
    vendor: Optional[str] = None
    slot_mode: str = "unknown"
    current_slot: Optional[str] = None
    slot_count: Optional[int] = None
    bootloader_unlocked: Optional[bool] = None
    root_method: Optional[str] = None
    probe_ok: bool = False
    raw: Dict[str, str] = field(default_factory=dict)

    @property
    def refused(self) -> bool:
        return self.vendor in ("samsung", "xiaomi")

    @property
    def supported(self) -> bool:
        """True only when the probe returned data and the vendor is not refused.

        An unreachable or empty probe is not "supported": nothing is known.
        This is still not permission to write; see write_blockers().
        """
        return self.probe_ok and not self.refused

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

    def slot_names(self, partition: str, both_slots: bool = False) -> List[str]:
        """Partition names a write of `partition` would touch."""
        if self.slot_mode == "ab" and self.current_slot:
            slots = [self.current_slot]
            if both_slots:
                slots.append("b" if self.current_slot == "a" else "a")
            return [f"{partition}_{slot}" for slot in slots]
        return [partition]

    def write_blockers(
        self,
        partition: str,
        image_size: Optional[int] = None,
        both_slots: bool = False,
    ) -> List[str]:
        """Reasons a fastboot write of `partition` must not proceed. Empty = allowed.

        Partition existence and size are checked only when the device lists
        its partitions (`partition-size:*` variables). A bootloader that does
        not list them gives no evidence either way, and fastboot itself
        refuses a flash to a partition that does not exist.
        """
        reasons: List[str] = []
        if self.mode != "fastboot":
            reasons.append(
                f"device is in {self.mode} mode; partition writes require fastboot mode"
            )
        if not self.probe_ok:
            reasons.append("the device probe failed or returned no data, so its state is unknown")
        if self.refused:
            reasons.append(
                f"{self.vendor} devices use an out-of-band unlock/flash workflow that RootForge does not automate"
            )
        if not self.codename:
            reasons.append("the device did not report its product name, so its identity is unknown")
        if self.bootloader_unlocked is False:
            reasons.append("the bootloader reports locked; unlock it before flashing")
        elif self.bootloader_unlocked is None:
            reasons.append(
                "the device does not report its bootloader lock state (no 'unlocked' variable)"
            )
        if self.slot_mode == "unknown":
            reasons.append("the slot layout (A/B or single) could not be determined")
        if both_slots and self.slot_mode != "ab":
            reasons.append("--both-slots was requested but the device is not a confirmed A/B device")

        listed = any(key.startswith("partition-size:") for key in self.raw)
        if listed:
            for name in self.slot_names(partition, both_slots):
                size_raw = self.raw.get(f"partition-size:{name}")
                if size_raw is None:
                    reasons.append(f"partition '{name}' is not listed by the device")
                    continue
                try:
                    size = int(size_raw, 0)
                except ValueError:
                    continue
                if image_size is not None and size > 0 and image_size > size:
                    reasons.append(
                        f"image is {image_size} bytes but partition '{name}' is only {size} bytes"
                    )
        return reasons

    def as_dict(self) -> Dict[str, object]:
        return asdict(self) | {
            "supported": self.supported,
            "refusal_message": self.refusal_message(),
        }


def compatibility_findings(
    profile: DeviceProfile,
    expect_product: Optional[str] = None,
    expect_slot: Optional[str] = None,
    expect_bootloader_version: Optional[str] = None,
) -> "tuple[List[str], List[str]]":
    """Compare a device with what a backup was captured from: (blockers, warnings).

    Product and slot are blocking: an image from another device, or from the
    other slot of an A/B device, must not be written on trust. The bootloader
    version blocks only when both sides report one and they differ; if either
    side does not report it there is no evidence either way, so the operator
    is warned rather than blocked. Expectations are only those the manifest
    actually recorded (None means "the backup does not say").
    """
    blockers: List[str] = []
    warnings: List[str] = []
    if expect_product is not None and profile.codename and profile.codename != expect_product:
        blockers.append(
            f"the backup is for '{expect_product}' but the connected device is '{profile.codename}'"
        )
    if expect_slot is not None:
        if profile.slot_mode != "ab" or not profile.current_slot:
            blockers.append(
                f"the backup was captured from slot '{expect_slot}' but the device's active slot is unknown"
            )
        elif profile.current_slot != expect_slot:
            blockers.append(
                f"the backup was captured from slot '{expect_slot}' but the device's active slot is "
                f"'{profile.current_slot}'; restoring would put it in the wrong slot"
            )
    if expect_bootloader_version is not None:
        actual = profile.raw.get("version-bootloader")
        if actual is None:
            warnings.append("the device does not report its bootloader version; firmware match is unverified")
        elif actual != expect_bootloader_version:
            blockers.append(
                f"the backup was captured with bootloader '{expect_bootloader_version}' "
                f"but the device runs '{actual}'"
            )
    return blockers, warnings


def _run(argv: Sequence[str], timeout: int = 10) -> ToolResult:
    try:
        completed = subprocess.run(
            list(argv), capture_output=True, text=True, timeout=timeout
        )
    except FileNotFoundError:
        return ToolResult(None, error=f"{argv[0]} is not installed")
    except subprocess.TimeoutExpired:
        return ToolResult(None, error=f"{argv[0]} timed out after {timeout}s")
    except OSError as exc:
        return ToolResult(None, error=str(exc))
    return ToolResult(completed.returncode, completed.stdout, completed.stderr)


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
    if magisk.ok and magisk.stdout.strip():
        return "magisk"
    kernelsu = _run(["adb", "-s", serial, "shell", "su", "-c", "ksud -V"])
    if kernelsu.ok and kernelsu.stdout.strip():
        return "kernelsu"
    which_su = _run(["adb", "-s", serial, "shell", "which", "su"])
    # Only "which ran, exited 1 and printed nothing" means there is no su. An
    # adb failure (offline, unauthorized) also exits non-zero but writes an
    # error, and says nothing about whether su exists.
    if which_su.returncode == 1 and not which_su.stdout.strip() and not which_su.stderr.strip():
        return "none"
    return None


def _parse_fastboot(text: str) -> Dict[str, str]:
    """Parse `fastboot getvar` output into {name: value}.

    Names may contain a single ":argument" part, and values may themselves
    contain colons, so split on the first ": " rather than the first ":".
    Lines that are not variable reports ("Finished. Total time: ...") do not
    match the name grammar and are ignored.
    """
    data: Dict[str, str] = {}
    for line in text.splitlines():
        line = re.sub(r"^\s*\(bootloader\)\s*", "", line.strip(), flags=re.I)
        key, sep, value = line.partition(": ")
        if not sep:
            if not line.endswith(":"):
                continue
            key, value = line[:-1], ""
        key = key.strip().lower()
        if _VAR_KEY_RE.match(key):
            data[key] = value.strip()
    return data


def _to_int(value: Optional[str]) -> Optional[int]:
    try:
        return int(value, 0) if value is not None else None
    except ValueError:
        return None


def _tristate(value: Optional[str]) -> Optional[bool]:
    value = (_clean(value) or "").lower()
    if value in ("yes", "true", "1"):
        return True
    if value in ("no", "false", "0"):
        return False
    return None


def profile_fastboot(serial: str) -> DeviceProfile:
    result = _run(["fastboot", "-s", serial, "getvar", "all"])
    raw = _parse_fastboot(result.text)
    probe_ok = result.ok and bool(raw)
    if not raw:
        return DeviceProfile(serial=serial, mode="fastboot", probe_ok=False)

    product = _clean(raw.get("product"))
    current = _clean(raw.get("current-slot"))
    current = current.lower().lstrip("_") if current else None
    slot_count = _to_int(raw.get("slot-count"))

    if current or (slot_count is not None and slot_count >= 2):
        slot_mode = "ab"
    elif slot_count is not None:
        slot_mode = "single"
    elif probe_ok and product:
        # AOSP fastboot treats a missing has-slot answer as "not slotted".
        slot_mode = "single"
    else:
        slot_mode = "unknown"

    return DeviceProfile(
        serial=serial,
        mode="fastboot",
        codename=product,
        vendor=_vendor(result.text),
        slot_mode=slot_mode,
        current_slot=current,
        slot_count=slot_count,
        # Lock state comes from `unlocked` only. `secure` is a different
        # question (is secure boot enforced) and stays in `raw`.
        bootloader_unlocked=_tristate(raw.get("unlocked")),
        probe_ok=probe_ok,
        raw=raw,
    )


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
        result = _run(["adb", "-s", serial, "shell", "getprop", prop])
        if not result.ok:
            return DeviceProfile(serial=serial, mode="adb", probe_ok=False)
        answers[key] = result.stdout
        clean = _clean(result.stdout)
        if clean is not None:
            raw[prop] = clean
    suffix = _clean(answers["slot_suffix"])
    lock_value = _clean(answers["flash_locked"])
    unlocked = None if lock_value not in ("0", "1") else lock_value == "0"
    manufacturer = _clean(answers["manufacturer"])
    brand = _clean(answers["brand"])
    model = _clean(answers["model"])
    return DeviceProfile(
        serial=serial,
        mode="adb",
        codename=_clean(answers["codename"]),
        model=model,
        vendor=_vendor(manufacturer, brand, model),
        slot_mode="ab" if suffix else "single",
        current_slot=suffix.lstrip("_") if suffix else None,
        bootloader_unlocked=unlocked,
        root_method=_detect_root_method(serial),
        probe_ok=True,
        raw=raw,
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
