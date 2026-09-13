"""Single-device identity & capability profiling for the `rootforge` CLI.

`rootforge.core.devices` (plural) answers "what's plugged in" — enumeration
only. This module answers "what is this one device" — vendor, slot layout,
bootloader state, and detected root method — via one round-trip per
transport, so callers that currently re-derive this independently
(flash_patched_boot.sh's own `getvar current-slot`, unlock_bootloader.sh's
own `getvar all` + grep, backup_partitions.sh's mode-only probe) have one
tested place to get it. See docs/IMPLEMENTATION_PLAN.md P1 item 5 and
docs/ARCHITECTURE_AUDIT.md §3.2.

This module does not yet retrofit those three scripts — it lands as a new,
additive module first so the detection logic can be reviewed and tested on
its own before anything that flashes a boot partition or unlocks a
bootloader is asked to depend on it.

Named `DeviceProfile`, not `Device`, to avoid colliding with
`rootforge.core.devices.Device` (a different, enumeration-only dataclass) —
two same-named classes in sibling modules is a bug waiting to happen.

Detection is best-effort and never guesses: any field can come back
None/"unknown" rather than a wrong value, because a caller may gate a
boot-partition write or bootloader unlock on this. No verbatim spec for the
"cannot safely continue" refusal message exists in this repository — both
docs/IMPLEMENTATION_PLAN.md and docs/ARCHITECTURE_AUDIT.md cite an external
"governing directive" that is not checked into this repo (confirmed by
grepping for it — no such file exists). `refusal_message()` below
reconstructs the message from docs/ARCHITECTURE_AUDIT.md §3.2's own example
(`DETECTED DEVICE / Vendor: Samsung / Automatic fastboot workflow
unavailable`) and unlock_bootloader.sh's existing Samsung/Xiaomi refusal
text, not invented wording.
"""
from __future__ import annotations

import re
from dataclasses import asdict, dataclass, field
from typing import Dict, Optional

from rootforge.core.devices import _run  # reuse the no-raise subprocess helper

# Same vendors unlock_bootloader.sh already refuses today (Knox / Mi Unlock
# permit flows can't be automated safely) — centralized so a future retrofit
# of that script, or any new caller, shares one list instead of re-deriving
# the same grep independently.
VENDOR_PATTERNS: Dict[str, str] = {
    "samsung": r"samsung",
    "xiaomi": r"xiaomi|redmi",
}

UNSUPPORTED_VENDORS: Dict[str, str] = {
    "samsung": (
        "Samsung devices unlock via Settings > OEM Unlocking + Download "
        "Mode/Odin, not fastboot; automating this risks tripping Knox "
        "permanently with no rollback."
    ),
    "xiaomi": (
        "Xiaomi/Redmi devices require a Mi Unlock permit tied to a Mi "
        "account with a vendor-enforced waiting period."
    ),
}


@dataclass
class DeviceProfile:
    serial: str
    mode: str  # "adb" or "fastboot" — transport this profile was built from
    codename: Optional[str] = None
    vendor: Optional[str] = None  # lowercase key into UNSUPPORTED_VENDORS, an unrecognized brand string, or None
    model: Optional[str] = None
    slot_mode: str = "unknown"  # "single", "ab", or "unknown"
    current_slot: Optional[str] = None  # meaningful only when slot_mode == "ab"
    bootloader_unlocked: Optional[bool] = None  # None: unknown / not queryable in this mode
    root_method: Optional[str] = None  # "magisk", "kernelsu", "none", or None if undetermined
    raw: Dict[str, str] = field(default_factory=dict)  # unparsed getvar/getprop values, for debugging

    def as_dict(self) -> dict:
        return asdict(self)

    @property
    def supported(self) -> bool:
        return self.vendor not in UNSUPPORTED_VENDORS

    def refusal_message(self) -> Optional[str]:
        """The hard-stop text for an unsupported vendor, or None if supported."""
        if self.supported:
            return None
        return (
            "DETECTED DEVICE\n"
            f"  Vendor: {self.vendor.capitalize()}\n"
            "  Automatic fastboot workflow unavailable — RootForge cannot safely continue.\n"
            f"  {UNSUPPORTED_VENDORS[self.vendor]}"
        )


def _match_vendor(text: str) -> Optional[str]:
    for vendor, pattern in VENDOR_PATTERNS.items():
        if re.search(pattern, text, re.IGNORECASE):
            return vendor
    return None


def _extract_getvar(blob: str, key: str) -> Optional[str]:
    """Pull one `key: value` field out of a `fastboot getvar all` blob.

    Some fastboot builds prefix every line with `(bootloader) `; searching
    for the key anywhere in the line (rather than requiring it at the start)
    matches both forms, same as unlock_bootloader.sh's own
    `grep -oP '(?<=key: ).*'` approach.
    """
    match = re.search(rf"{re.escape(key)}:\s*(.*)", blob)
    if match is None:
        return None
    value = match.group(1).replace("\r", "").strip()
    return value or None


def profile_fastboot(serial: str) -> DeviceProfile:
    """Profile a device in fastboot/bootloader mode with one `getvar all` call."""
    profile = DeviceProfile(serial=serial, mode="fastboot")
    out = _run(["fastboot", "-s", serial, "getvar", "all"], timeout=10)
    if out is None:
        return profile

    profile.raw = {"getvar_all": out}
    profile.codename = _extract_getvar(out, "product")

    current_slot = _extract_getvar(out, "current-slot")
    profile.current_slot = current_slot
    profile.slot_mode = "ab" if current_slot else "single"

    unlocked = _extract_getvar(out, "unlocked")
    if unlocked is not None:
        profile.bootloader_unlocked = unlocked.lower() == "yes"

    profile.vendor = _match_vendor(out)
    return profile


# getprop keys queried in adb mode, one call each — mirrors devices.py's
# adb_properties() pattern rather than a single bulk command, because unlike
# `fastboot getvar all` there is no equivalent single `getprop` call that
# returns every property at once in a form worth parsing generically.
ADB_PROFILE_PROPS = {
    "codename": "ro.product.device",
    "model": "ro.product.model",
    "manufacturer": "ro.product.manufacturer",
    "brand": "ro.product.brand",
    "slot_suffix": "ro.boot.slot_suffix",
    "flash_locked": "ro.boot.flash.locked",
}


def _getprop_raw(serial: str, prop: str) -> Optional[str]:
    """Read one getprop value, keeping "answered with nothing" distinct from
    "couldn't ask at all".

    An unset Android property still makes `getprop` exit 0 with an empty
    line — that is a real, confirmed answer (e.g. `ro.boot.slot_suffix`
    empty means "not an A/B device"), not the same as adb being unreachable.
    Returns "" for the former, None only when `_run` itself returned None.
    """
    out = _run(["adb", "-s", serial, "shell", "getprop", prop], timeout=5)
    if out is None:
        return None
    return out.replace("\r", "").strip()


def _detect_root_method(serial: str) -> Optional[str]:
    """'magisk' / 'kernelsu' / 'none' (no su binary) / None (inconclusive).

    Deliberately shallow — full auditing (denylist status, verified boot
    state, su-binary path enumeration) is check_root_detection.sh's job;
    this exists only to fill one DeviceProfile field without guessing.
    """
    magisk = _run(["adb", "-s", serial, "shell", "su", "-c", "magisk -v"], timeout=5)
    if magisk is not None and magisk.strip():
        return "magisk"

    kernelsu = _run(["adb", "-s", serial, "shell", "su", "-c", "ksud -V"], timeout=5)
    if kernelsu is not None and kernelsu.strip():
        return "kernelsu"

    which_su = _run(["adb", "-s", serial, "shell", "which", "su"], timeout=5)
    if which_su is None:
        return None  # adb unreachable — can't conclude anything, not even "none"
    if not which_su.strip():
        return "none"
    return None  # su exists but neither manager responded — inconclusive, not a guess


def profile_adb(serial: str) -> DeviceProfile:
    """Profile a booted device reachable over adb."""
    profile = DeviceProfile(serial=serial, mode="adb")

    raw: Dict[str, str] = {}
    for label, prop in ADB_PROFILE_PROPS.items():
        value = _getprop_raw(serial, prop)
        if value is not None:  # None means unreachable; "" is a real, confirmed answer
            raw[label] = value
    profile.raw = raw

    profile.codename = raw.get("codename") or None
    profile.model = raw.get("model") or None

    vendor_candidate = raw.get("manufacturer") or raw.get("brand")
    if vendor_candidate:
        profile.vendor = _match_vendor(vendor_candidate)

    slot_suffix = raw.get("slot_suffix")
    if slot_suffix:
        profile.slot_mode = "ab"
        profile.current_slot = slot_suffix.lstrip("_") or None
    elif slot_suffix is not None:
        # Property was readable and explicitly empty — a real signal that
        # this is a single-partition device, not "we don't know".
        profile.slot_mode = "single"

    flash_locked = raw.get("flash_locked")
    if flash_locked == "0":
        profile.bootloader_unlocked = True
    elif flash_locked == "1":
        profile.bootloader_unlocked = False
    # Absent on many devices — ro.boot.flash.locked is not a universal
    # property, so leave bootloader_unlocked at its default (None/unknown)
    # rather than assuming locked.

    profile.root_method = _detect_root_method(serial)
    return profile


def profile_device(serial: str, mode: str) -> DeviceProfile:
    """Profile one device by serial, dispatching on transport.

    `mode` must be "adb" or "fastboot" — anything else is a programmer
    error (the caller already knows which transport it found the device
    on), not a detection outcome, so it raises rather than returning an
    empty/unknown profile.
    """
    if mode == "adb":
        return profile_adb(serial)
    if mode == "fastboot":
        return profile_fastboot(serial)
    raise ValueError(f"unknown device mode {mode!r} — expected 'adb' or 'fastboot'")
