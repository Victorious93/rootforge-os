"""Device enumeration for the `rootforge` CLI.

One place that answers "what is plugged in right now", so the answer stops
being re-derived (differently, and in one case wrongly) by each shell script
that needs it. `adb devices` prints a header line and a trailing blank line
around its table; parsing it by excluding the header alone matches that
blank line and reports a device that isn't there. Parse the state column.

Nothing here writes to a device — enumeration only.
"""
from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass, field, asdict
from typing import Dict, List, Optional

# adb reports states other than "device" for hardware that is attached but
# not usable yet. Surfacing them explicitly is the point: "unauthorized" in
# particular means the USB-debugging prompt is still waiting on-screen, which
# is the single most common reason a RootForge script "can't see" a phone.
ADB_UNUSABLE_STATES = {
    "unauthorized": "USB debugging not yet authorized — accept the prompt on the device",
    "offline": "device is offline — replug it, or run `adb kill-server && adb start-server`",
    "no permissions": "no udev permission for this device — see /etc/udev/rules.d/51-android.rules",
    "recovery": "device is in recovery, not booted Android",
    "sideload": "device is in sideload mode",
    "unknown": "adb reports an unknown state",
}

DEFAULT_TIMEOUT = 10


@dataclass
class Device:
    serial: str
    mode: str  # "adb" or "fastboot"
    state: str  # adb state column, or "fastboot"
    usable: bool
    note: str = ""
    properties: Dict[str, str] = field(default_factory=dict)

    def as_dict(self) -> dict:
        return asdict(self)


def _run(argv: List[str], timeout: int = DEFAULT_TIMEOUT) -> Optional[str]:
    """Run a command, returning stdout, or None if it can't run at all.

    A missing binary and a device that hangs are both normal here (this is
    the code that finds out whether tooling works), so neither is allowed to
    raise into the caller.
    """
    if shutil.which(argv[0]) is None:
        return None
    try:
        proc = subprocess.run(
            argv, capture_output=True, text=True, timeout=timeout, check=False
        )
    except (subprocess.TimeoutExpired, OSError):
        return None
    return proc.stdout


def adb_devices() -> List[Device]:
    out = _run(["adb", "devices"])
    if out is None:
        return []
    devices = []
    for line in out.splitlines():
        line = line.strip()
        # Skip the header, blank separator lines, and adb server chatter
        # ("* daemon started successfully").
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, state = parts[0], " ".join(parts[1:])
        usable = state == "device"
        devices.append(
            Device(
                serial=serial,
                mode="adb",
                state=state,
                usable=usable,
                note="" if usable else ADB_UNUSABLE_STATES.get(state, f"state: {state}"),
            )
        )
    return devices


def fastboot_devices() -> List[Device]:
    out = _run(["fastboot", "devices"])
    if out is None:
        return []
    devices = []
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        serial = line.split()[0]
        devices.append(Device(serial=serial, mode="fastboot", state="fastboot", usable=True))
    return devices


# getvar keys worth reporting: enough to tell A/B from single-slot and locked
# from unlocked, which is what decides whether the flash scripts can proceed.
FASTBOOT_VARS = ("product", "current-slot", "unlocked", "secure", "is-userspace")


def fastboot_properties(serial: str) -> Dict[str, str]:
    props: Dict[str, str] = {}
    for var in FASTBOOT_VARS:
        out = _run(["fastboot", "-s", serial, "getvar", var], timeout=5)
        if out is None:
            continue
        # fastboot writes getvar results to stderr on some versions and
        # stdout on others; _run only captures stdout, so an empty result
        # here is normal rather than an error.
        for line in out.splitlines():
            if line.startswith(f"{var}:"):
                props[var] = line.split(":", 1)[1].strip()
                break
    return props


ADB_PROPS = {
    "codename": "ro.product.device",
    "model": "ro.product.model",
    "android": "ro.build.version.release",
    "build": "ro.build.id",
}


def adb_properties(serial: str) -> Dict[str, str]:
    props: Dict[str, str] = {}
    for label, prop in ADB_PROPS.items():
        out = _run(["adb", "-s", serial, "shell", "getprop", prop], timeout=5)
        if out is None:
            continue
        # Android's shell terminates lines with CRLF; an untrimmed value
        # compares unequal to everything and prints with a stray carriage
        # return.
        value = out.replace("\r", "").strip()
        if value:
            props[label] = value
    return props


def list_devices(detailed: bool = False) -> List[Device]:
    devices = adb_devices() + fastboot_devices()
    if detailed:
        for device in devices:
            if device.mode == "fastboot":
                device.properties = fastboot_properties(device.serial)
            elif device.usable:
                device.properties = adb_properties(device.serial)
    return devices
