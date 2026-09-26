"""Unit tests for rootforge.core.devices.

`adb devices` output parsing is the whole point of this module: the shell
version it replaces reported a device that wasn't there. These tests feed
canned adb/fastboot output straight to the parsers.
"""
import unittest
from unittest import mock

from rootforge.core import devices


class TestAdbParsing(unittest.TestCase):
    def parse(self, stdout):
        with mock.patch.object(devices, "_run", return_value=stdout):
            return devices.adb_devices()

    def test_no_devices_attached(self):
        """The exact output that produced a phantom device.

        `adb devices` prints the header, then a blank line. Filtering only
        the header (the old `grep -v 'List of devices'`) matches that blank
        line and reports a connected device with nothing plugged in.
        """
        self.assertEqual(self.parse("List of devices attached\n\n"), [])

    def test_adb_not_installed(self):
        with mock.patch.object(devices, "_run", return_value=None):
            self.assertEqual(devices.adb_devices(), [])

    def test_single_device(self):
        found = self.parse("List of devices attached\nABC123\tdevice\n\n")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].serial, "ABC123")
        self.assertTrue(found[0].usable)
        self.assertEqual(found[0].mode, "adb")

    def test_unauthorized_device_is_reported_but_not_usable(self):
        found = self.parse("List of devices attached\nABC123\tunauthorized\n\n")
        self.assertEqual(len(found), 1)
        self.assertFalse(found[0].usable)
        self.assertIn("authoriz", found[0].note)

    def test_offline_device_is_not_usable(self):
        found = self.parse("List of devices attached\nABC123\toffline\n\n")
        self.assertFalse(found[0].usable)
        self.assertTrue(found[0].note)

    def test_daemon_chatter_is_ignored(self):
        stdout = (
            "* daemon not running; starting now at tcp:5037\n"
            "* daemon started successfully\n"
            "List of devices attached\n"
            "ABC123\tdevice\n\n"
        )
        found = self.parse(stdout)
        self.assertEqual([d.serial for d in found], ["ABC123"])

    def test_mixed_states(self):
        stdout = (
            "List of devices attached\n"
            "AAA\tdevice\n"
            "BBB\tunauthorized\n"
            "CCC\tdevice\n\n"
        )
        found = self.parse(stdout)
        self.assertEqual([d.serial for d in found], ["AAA", "BBB", "CCC"])
        self.assertEqual([d.serial for d in found if d.usable], ["AAA", "CCC"])

    def test_multiword_state_is_kept_whole(self):
        found = self.parse("List of devices attached\nAAA\tno permissions\n\n")
        self.assertEqual(found[0].state, "no permissions")
        self.assertFalse(found[0].usable)


class TestFastbootParsing(unittest.TestCase):
    def parse(self, stdout):
        with mock.patch.object(devices, "_run", return_value=stdout):
            return devices.fastboot_devices()

    def test_no_devices(self):
        self.assertEqual(self.parse(""), [])
        self.assertEqual(self.parse("\n\n"), [])

    def test_one_device(self):
        found = self.parse("FB123\tfastboot\n")
        self.assertEqual(len(found), 1)
        self.assertEqual(found[0].serial, "FB123")
        self.assertEqual(found[0].mode, "fastboot")
        self.assertTrue(found[0].usable)


class TestRun(unittest.TestCase):
    def test_missing_binary_returns_none(self):
        with mock.patch.object(devices.shutil, "which", return_value=None):
            self.assertIsNone(devices._run(["definitely-not-a-real-binary"]))

    def test_timeout_returns_none_rather_than_raising(self):
        """A wedged adb must not take the caller down with it."""
        with mock.patch.object(devices.shutil, "which", return_value="/usr/bin/adb"):
            with mock.patch.object(
                devices.subprocess,
                "run",
                side_effect=devices.subprocess.TimeoutExpired("adb", 1),
            ):
                self.assertIsNone(devices._run(["adb", "devices"]))


class TestProperties(unittest.TestCase):
    def test_adb_properties_strip_carriage_returns(self):
        """Android's shell emits CRLF; an untrimmed value compares unequal."""
        with mock.patch.object(devices, "_run", return_value="bluejay\r\n"):
            props = devices.adb_properties("ABC123")
        self.assertEqual(props["codename"], "bluejay")

    def test_device_serializes_to_json_friendly_dict(self):
        device = devices.Device(serial="A", mode="adb", state="device", usable=True)
        data = device.as_dict()
        self.assertEqual(data["serial"], "A")
        self.assertTrue(data["usable"])
        self.assertEqual(data["properties"], {})


if __name__ == "__main__":
    unittest.main()
