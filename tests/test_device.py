"""Unit tests for rootforge.core.device (singular — capability profiling).

Mirrors tests/test_devices.py's style: feed canned `getvar all`/`getprop`
text straight to the module by monkeypatching its `_run` reference, rather
than the tests/stubs/adb|fastboot shell stubs (those back the shell-script
tests, not this Python module).
"""
import argparse
import unittest
from unittest import mock

from rootforge.core import cli, device
from rootforge.core.cli import build_parser
from rootforge.core.devices import Device as EnumeratedDevice


def parse(argv):
    return build_parser().parse_args(argv)


def _run_map(responses):
    """A `_run` replacement that answers by exact argv match.

    Any call not present in `responses` returns None (as `_run` does for a
    device that can't be reached), so a test only needs to spell out the
    calls it cares about.
    """

    def _fake(argv, timeout=10):
        return responses.get(tuple(argv))

    return _fake


class TestFastbootProfiling(unittest.TestCase):
    def profile(self, serial, getvar_all_output):
        responses = {("fastboot", "-s", serial, "getvar", "all"): getvar_all_output}
        with mock.patch.object(device, "_run", _run_map(responses)):
            return device.profile_fastboot(serial)

    def test_ab_device_unlocked(self):
        blob = (
            "(bootloader) product: cheetah\n"
            "(bootloader) current-slot: a\n"
            "(bootloader) unlocked: yes\n"
        )
        profile = self.profile("SER1", blob)
        self.assertEqual(profile.mode, "fastboot")
        self.assertEqual(profile.codename, "cheetah")
        self.assertEqual(profile.slot_mode, "ab")
        self.assertEqual(profile.current_slot, "a")
        self.assertTrue(profile.bootloader_unlocked)
        self.assertIsNone(profile.vendor)
        self.assertTrue(profile.supported)
        self.assertIsNone(profile.refusal_message())

    def test_single_slot_device_locked(self):
        blob = "product: gts4lvwifi\nunlocked: no\n"
        profile = self.profile("SER2", blob)
        self.assertEqual(profile.slot_mode, "single")
        self.assertIsNone(profile.current_slot)
        self.assertFalse(profile.bootloader_unlocked)

    def test_samsung_vendor_is_refused(self):
        blob = "product: gts4lvwifi\nunlocked: no\n(bootloader) samsung device\n"
        profile = self.profile("SER3", blob)
        self.assertEqual(profile.vendor, "samsung")
        self.assertFalse(profile.supported)
        message = profile.refusal_message()
        self.assertIn("DETECTED DEVICE", message)
        self.assertIn("Samsung", message)
        self.assertIn("cannot safely continue", message)
        self.assertIn("Knox", message)

    def test_xiaomi_and_redmi_both_match_xiaomi_vendor(self):
        for token in ("xiaomi", "Redmi"):
            blob = f"product: whatever\n{token} bootloader\n"
            profile = self.profile("SER4", blob)
            self.assertEqual(profile.vendor, "xiaomi")
            self.assertFalse(profile.supported)
            self.assertIn("Mi Unlock", profile.refusal_message())

    def test_unreachable_fastboot_returns_unknown_profile_not_a_guess(self):
        with mock.patch.object(device, "_run", return_value=None):
            profile = device.profile_fastboot("SER5")
        self.assertIsNone(profile.codename)
        self.assertEqual(profile.slot_mode, "unknown")
        self.assertIsNone(profile.bootloader_unlocked)
        self.assertIsNone(profile.vendor)
        self.assertTrue(profile.supported)  # unknown vendor is not the same as a refused one


class TestAdbProfiling(unittest.TestCase):
    def profile(self, serial, props, root_responses=None):
        responses = {}
        for prop, value in props.items():
            responses[("adb", "-s", serial, "shell", "getprop", prop)] = value
        if root_responses:
            responses.update(root_responses)
        with mock.patch.object(device, "_run", _run_map(responses)):
            return device.profile_adb(serial)

    def test_full_ab_profile(self):
        props = {
            "ro.product.device": "cheetah\r\n",
            "ro.product.model": "Pixel 7 Pro\r\n",
            "ro.product.manufacturer": "Google\r\n",
            "ro.product.brand": "google\r\n",
            "ro.boot.slot_suffix": "_a\r\n",
            "ro.boot.flash.locked": "0\r\n",
        }
        profile = self.profile("SER1", props)
        self.assertEqual(profile.codename, "cheetah")
        self.assertEqual(profile.model, "Pixel 7 Pro")
        self.assertIsNone(profile.vendor)  # Google isn't in VENDOR_PATTERNS
        self.assertEqual(profile.slot_mode, "ab")
        self.assertEqual(profile.current_slot, "a")
        self.assertTrue(profile.bootloader_unlocked)

    def test_explicitly_empty_slot_suffix_means_single_not_unknown(self):
        """A confirmed-empty getprop answer must not collapse to 'unknown'.

        adb getprop on an unset property still exits 0 with an empty line —
        that is a real answer ("not an A/B device"), distinct from adb being
        unreachable, which is the only case that should leave slot_mode
        "unknown".
        """
        props = {
            "ro.product.device": "gts4lvwifi",
            "ro.product.model": "",
            "ro.product.manufacturer": "",
            "ro.product.brand": "",
            "ro.boot.slot_suffix": "",
            "ro.boot.flash.locked": "",
        }
        profile = self.profile("SER2", props)
        self.assertEqual(profile.slot_mode, "single")
        self.assertIsNone(profile.current_slot)
        self.assertIsNone(profile.bootloader_unlocked)  # empty flash.locked stays unknown

    def test_unreachable_adb_leaves_slot_mode_unknown(self):
        with mock.patch.object(device, "_run", return_value=None):
            profile = device.profile_adb("SER3")
        self.assertEqual(profile.slot_mode, "unknown")
        self.assertIsNone(profile.codename)

    def test_manufacturer_vendor_match(self):
        props = {
            "ro.product.device": "gts4lvwifi",
            "ro.product.model": "",
            "ro.product.manufacturer": "samsung",
            "ro.product.brand": "samsung",
            "ro.boot.slot_suffix": "",
            "ro.boot.flash.locked": "",
        }
        profile = self.profile("SER4", props)
        self.assertEqual(profile.vendor, "samsung")
        self.assertFalse(profile.supported)


class TestRootMethodDetection(unittest.TestCase):
    def detect(self, serial, magisk=None, kernelsu=None, which_su=None):
        responses = {
            ("adb", "-s", serial, "shell", "su", "-c", "magisk -v"): magisk,
            ("adb", "-s", serial, "shell", "su", "-c", "ksud -V"): kernelsu,
            ("adb", "-s", serial, "shell", "which", "su"): which_su,
        }
        with mock.patch.object(device, "_run", _run_map(responses)):
            return device._detect_root_method(serial)

    def test_magisk_detected(self):
        self.assertEqual(self.detect("S1", magisk="26.4:MAGISK\n"), "magisk")

    def test_kernelsu_detected_when_magisk_absent(self):
        self.assertEqual(
            self.detect("S2", magisk="", kernelsu="v1.0.0\n"), "kernelsu"
        )

    def test_none_when_no_su_binary(self):
        self.assertEqual(self.detect("S3", magisk="", kernelsu="", which_su=""), "none")

    def test_inconclusive_when_su_exists_but_no_manager_responds(self):
        """su is present (maybe APatch or something unusual) — must not guess."""
        self.assertIsNone(
            self.detect("S4", magisk="", kernelsu="", which_su="/system/bin/su\n")
        )

    def test_none_when_adb_entirely_unreachable(self):
        with mock.patch.object(device, "_run", return_value=None):
            self.assertIsNone(device._detect_root_method("S5"))


class TestDeviceProfile(unittest.TestCase):
    def test_as_dict_is_json_friendly(self):
        profile = device.DeviceProfile(serial="A", mode="fastboot", vendor="samsung")
        data = profile.as_dict()
        self.assertEqual(data["serial"], "A")
        self.assertEqual(data["vendor"], "samsung")
        self.assertEqual(data["raw"], {})

    def test_supported_true_when_vendor_unknown_or_unmatched(self):
        self.assertTrue(device.DeviceProfile(serial="A", mode="adb").supported)
        self.assertTrue(
            device.DeviceProfile(serial="A", mode="adb", vendor="google").supported
        )

    def test_refusal_message_none_for_supported_device(self):
        profile = device.DeviceProfile(serial="A", mode="adb")
        self.assertIsNone(profile.refusal_message())


class TestProfileDeviceDispatch(unittest.TestCase):
    def test_dispatches_to_adb(self):
        with mock.patch.object(device, "profile_adb") as fake:
            device.profile_device("SER", "adb")
            fake.assert_called_once_with("SER")

    def test_dispatches_to_fastboot(self):
        with mock.patch.object(device, "profile_fastboot") as fake:
            device.profile_device("SER", "fastboot")
            fake.assert_called_once_with("SER")

    def test_invalid_mode_raises_rather_than_guessing(self):
        with self.assertRaises(ValueError):
            device.profile_device("SER", "bluetooth")


class TestDeviceCliParsing(unittest.TestCase):
    """`rootforge device info` argument parsing, per cli.py's build_parser()."""

    def test_info_with_no_serial_defaults_to_none(self):
        args = parse(["device", "info"])
        self.assertEqual(args.command, "device")
        self.assertEqual(args.device_command, "info")
        self.assertIsNone(args.serial)
        self.assertFalse(args.json)

    def test_info_parses_explicit_serial_and_json(self):
        args = parse(["device", "info", "ABC123", "--json"])
        self.assertEqual(args.serial, "ABC123")
        self.assertTrue(args.json)

    def test_device_with_no_verb_is_an_error(self):
        """required=True on the device_command subparsers, same as `module`."""
        with self.assertRaises(SystemExit):
            parse(["device"])


class TestSelectDevice(unittest.TestCase):
    """cli._select_device — resolving an optional serial to (serial, mode)."""

    def test_explicit_serial_matches_regardless_of_usability(self):
        found = [EnumeratedDevice(serial="A", mode="adb", state="device", usable=True)]
        with mock.patch.object(cli, "list_devices", return_value=found):
            self.assertEqual(cli._select_device("A"), ("A", "adb"))

    def test_explicit_serial_not_attached_names_what_is(self):
        found = [EnumeratedDevice(serial="A", mode="adb", state="device", usable=True)]
        with mock.patch.object(cli, "list_devices", return_value=found):
            with self.assertRaisesRegex(LookupError, r"'B'.*Attached: A"):
                cli._select_device("B")

    def test_no_serial_and_nothing_attached_raises(self):
        with mock.patch.object(cli, "list_devices", return_value=[]):
            with self.assertRaises(LookupError):
                cli._select_device(None)

    def test_no_serial_with_one_usable_device_auto_selects(self):
        found = [EnumeratedDevice(serial="A", mode="fastboot", state="fastboot", usable=True)]
        with mock.patch.object(cli, "list_devices", return_value=found):
            self.assertEqual(cli._select_device(None), ("A", "fastboot"))

    def test_no_serial_with_multiple_usable_devices_refuses_to_guess(self):
        found = [
            EnumeratedDevice(serial="A", mode="adb", state="device", usable=True),
            EnumeratedDevice(serial="B", mode="fastboot", state="fastboot", usable=True),
        ]
        with mock.patch.object(cli, "list_devices", return_value=found):
            with self.assertRaisesRegex(LookupError, "multiple usable devices"):
                cli._select_device(None)

    def test_unusable_device_is_not_auto_selected(self):
        found = [EnumeratedDevice(serial="A", mode="adb", state="unauthorized", usable=False)]
        with mock.patch.object(cli, "list_devices", return_value=found):
            with self.assertRaises(LookupError):
                cli._select_device(None)


class TestCmdDeviceInfo(unittest.TestCase):
    """cli.cmd_device_info — exit codes for supported vs. refused devices."""

    def test_unsupported_vendor_returns_nonzero(self):
        args = argparse.Namespace(serial="A", json=False)
        profile = device.DeviceProfile(serial="A", mode="fastboot", vendor="samsung")
        with mock.patch.object(cli, "_select_device", return_value=("A", "fastboot")):
            with mock.patch.object(cli, "profile_device", return_value=profile):
                self.assertEqual(cli.cmd_device_info(args), 1)

    def test_supported_device_returns_zero(self):
        args = argparse.Namespace(serial="A", json=True)
        profile = device.DeviceProfile(serial="A", mode="adb")
        with mock.patch.object(cli, "_select_device", return_value=("A", "adb")):
            with mock.patch.object(cli, "profile_device", return_value=profile):
                self.assertEqual(cli.cmd_device_info(args), 0)

    def test_selection_failure_is_reported_and_returns_one(self):
        args = argparse.Namespace(serial=None, json=False)
        with mock.patch.object(
            cli, "_select_device", side_effect=LookupError("no usable device attached.")
        ):
            self.assertEqual(cli.cmd_device_info(args), 1)


if __name__ == "__main__":
    unittest.main()
