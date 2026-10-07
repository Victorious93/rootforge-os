"""Unit tests for rootforge.core.device (singular — capability profiling).

Mirrors tests/test_devices.py's style: feed canned `getvar all`/`getprop`
text straight to the module by monkeypatching its `_run` reference, rather
than the tests/stubs/adb|fastboot shell stubs (those back the shell-script
tests, not this Python module).
"""
import argparse
import contextlib
import io
import json
import unittest
from unittest import mock

from rootforge.core import cli, device
from rootforge.core.cli import build_parser
from rootforge.core.devices import Device as EnumeratedDevice


def parse(argv):
    return build_parser().parse_args(argv)


UNREACHABLE = device.ToolResult(None, error="tool unavailable")


def _as_result(value):
    if isinstance(value, device.ToolResult):
        return value
    if value is None:
        return UNREACHABLE
    return device.ToolResult(0, stdout=value)


def _run_map(responses):
    """A `_run` replacement that answers by exact argv match.

    A plain string is a successful stdout-only answer; a ToolResult is used
    as-is (stderr-only output, non-zero exits). Any call not present in
    `responses` behaves like a tool that cannot be reached, so a test only
    needs to spell out the calls it cares about.
    """

    def _fake(argv, timeout=10):
        return _as_result(responses.get(tuple(argv)))

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
        with mock.patch.object(device, "_run", return_value=UNREACHABLE):
            profile = device.profile_fastboot("SER5")
        self.assertIsNone(profile.codename)
        self.assertEqual(profile.slot_mode, "unknown")
        self.assertIsNone(profile.bootloader_unlocked)
        self.assertIsNone(profile.vendor)
        self.assertFalse(profile.probe_ok)
        self.assertFalse(profile.supported)  # nothing is known, so nothing is "supported"


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
        with mock.patch.object(device, "_run", return_value=UNREACHABLE):
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
    def detect(self, serial, magisk=None, kernelsu=None, which_su=UNREACHABLE):
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
        """`which su` ran and found nothing: exit 1 with no output at all."""
        not_found = device.ToolResult(1, stdout="", stderr="")
        self.assertEqual(
            self.detect("S3", magisk=not_found, kernelsu=not_found, which_su=not_found), "none"
        )

    def test_adb_error_is_not_mistaken_for_no_su(self):
        """exit 1 with an adb error on stderr means adb failed, not that su is absent."""
        offline = device.ToolResult(1, stdout="", stderr="error: device offline")
        self.assertIsNone(
            self.detect("S6", magisk=offline, kernelsu=offline, which_su=offline)
        )

    def test_inconclusive_when_su_exists_but_no_manager_responds(self):
        """su is present (maybe APatch or something unusual) — must not guess."""
        self.assertIsNone(
            self.detect("S4", magisk="", kernelsu="", which_su="/system/bin/su\n")
        )

    def test_none_when_adb_entirely_unreachable(self):
        with mock.patch.object(device, "_run", return_value=UNREACHABLE):
            self.assertIsNone(device._detect_root_method("S5"))


GETVAR_AB_LOCKED_STDERR = (
    "(bootloader) product: sunfish\n"
    "(bootloader) current-slot: a\n"
    "(bootloader) slot-count: 2\n"
    "(bootloader) unlocked: no\n"
    "(bootloader) secure: yes\n"
    "(bootloader) partition-size:boot_a: 0x4000000\n"
    "(bootloader) partition-size:boot_b: 0x4000000\n"
    "(bootloader) partition-size:init_boot_a: 0x800000\n"
    "Finished. Total time: 0.012s\n"
)


def fastboot_stderr(text, rc=0):
    """What real fastboot does: variables on stderr, nothing on stdout."""
    return device.ToolResult(rc, stdout="", stderr=text)


class TestRealFastbootOutput(unittest.TestCase):
    """Regression tests for findings reproduced against the review baseline."""

    def profile(self, result, serial="S1"):
        responses = {("fastboot", "-s", serial, "getvar", "all"): result}
        with mock.patch.object(device, "_run", _run_map(responses)):
            return device.profile_fastboot(serial)

    def test_variables_reported_on_stderr_are_parsed(self):
        profile = self.profile(fastboot_stderr(GETVAR_AB_LOCKED_STDERR))
        self.assertEqual(profile.codename, "sunfish")
        self.assertEqual(profile.slot_mode, "ab")
        self.assertEqual(profile.current_slot, "a")
        self.assertEqual(profile.slot_count, 2)
        self.assertTrue(profile.probe_ok)

    def test_stderr_only_locked_device_is_reported_locked(self):
        profile = self.profile(fastboot_stderr(GETVAR_AB_LOCKED_STDERR))
        self.assertIs(profile.bootloader_unlocked, False)

    def test_empty_probe_is_unknown_not_single_slot_and_not_supported(self):
        profile = self.profile(device.ToolResult(0, stdout="", stderr=""))
        self.assertEqual(profile.slot_mode, "unknown")
        self.assertFalse(profile.probe_ok)
        self.assertFalse(profile.supported)

    def test_nonzero_exit_is_not_a_successful_probe(self):
        profile = self.profile(fastboot_stderr(GETVAR_AB_LOCKED_STDERR, rc=1))
        self.assertFalse(profile.probe_ok)
        self.assertIn(
            "the device probe failed or returned no data, so its state is unknown",
            profile.write_blockers("boot"),
        )

    def test_secure_is_not_mistaken_for_unlocked(self):
        """`secure: yes` is about secure boot, not the lock state."""
        profile = self.profile(fastboot_stderr("(bootloader) product: x\n(bootloader) secure: yes\n"))
        self.assertIsNone(profile.bootloader_unlocked)
        self.assertEqual(profile.raw["secure"], "yes")

    def test_variable_names_with_arguments_and_colon_values_parse(self):
        raw = device._parse_fastboot(
            "(bootloader) has-slot:boot: yes\n"
            "(bootloader) partition-size:boot_a: 0x4000000\n"
            "(bootloader) version-bootloader: abc:1.2\n"
            "Finished. Total time: 0.001s\n"
            "< waiting for any device >\n"
        )
        self.assertEqual(raw["has-slot:boot"], "yes")
        self.assertEqual(raw["partition-size:boot_a"], "0x4000000")
        self.assertEqual(raw["version-bootloader"], "abc:1.2")
        self.assertNotIn("finished. total time", raw)

    def test_single_slot_requires_a_real_probe(self):
        profile = self.profile(fastboot_stderr("(bootloader) product: oldphone\n(bootloader) unlocked: yes\n"))
        self.assertEqual(profile.slot_mode, "single")

    def test_slot_count_one_means_single(self):
        profile = self.profile(fastboot_stderr("(bootloader) product: p\n(bootloader) slot-count: 1\n"))
        self.assertEqual(profile.slot_mode, "single")


class TestWriteBlockers(unittest.TestCase):
    def profile(self, **overrides):
        base = dict(
            serial="S1", mode="fastboot", codename="sunfish", slot_mode="ab",
            current_slot="a", slot_count=2, bootloader_unlocked=True, probe_ok=True,
            raw={"partition-size:boot_a": "0x4000000", "partition-size:boot_b": "0x4000000"},
        )
        base.update(overrides)
        return device.DeviceProfile(**base)

    def test_healthy_unlocked_ab_device_is_allowed(self):
        self.assertEqual(self.profile().write_blockers("boot", image_size=1024), [])

    def test_locked_bootloader_blocks(self):
        reasons = self.profile(bootloader_unlocked=False).write_blockers("boot")
        self.assertTrue(any("locked" in r for r in reasons))

    def test_unknown_lock_state_blocks_rather_than_assuming_unlocked(self):
        reasons = self.profile(bootloader_unlocked=None).write_blockers("boot")
        self.assertTrue(any("lock state" in r for r in reasons))

    def test_unknown_slot_layout_blocks(self):
        reasons = self.profile(slot_mode="unknown", current_slot=None).write_blockers("boot")
        self.assertTrue(any("slot layout" in r for r in reasons))

    def test_adb_mode_blocks_writes(self):
        reasons = self.profile(mode="adb").write_blockers("boot")
        self.assertTrue(any("fastboot mode" in r for r in reasons))

    def test_failed_probe_blocks(self):
        reasons = device.DeviceProfile(serial="S1", mode="fastboot").write_blockers("boot")
        self.assertTrue(any("probe failed" in r for r in reasons))
        self.assertTrue(any("identity" in r for r in reasons))

    def test_refused_vendor_blocks(self):
        reasons = self.profile(vendor="samsung").write_blockers("boot")
        self.assertTrue(any("samsung" in r for r in reasons))

    def test_unknown_vendor_is_not_a_reason_to_block_or_to_allow(self):
        self.assertEqual(self.profile(vendor=None).write_blockers("boot"), [])
        self.assertTrue(self.profile(vendor=None, bootloader_unlocked=None).write_blockers("boot"))

    def test_missing_partition_blocks_when_the_device_lists_partitions(self):
        reasons = self.profile().write_blockers("init_boot")
        self.assertTrue(any("init_boot_a" in r and "not listed" in r for r in reasons))

    def test_unlisted_partitions_give_no_evidence_either_way(self):
        self.assertEqual(self.profile(raw={}).write_blockers("init_boot"), [])

    def test_image_larger_than_partition_blocks(self):
        reasons = self.profile().write_blockers("boot", image_size=0x4000001)
        self.assertTrue(any("only" in r and "boot_a" in r for r in reasons))

    def test_image_exactly_partition_size_is_allowed(self):
        self.assertEqual(self.profile().write_blockers("boot", image_size=0x4000000), [])

    def test_both_slots_needs_a_confirmed_ab_device(self):
        reasons = self.profile(slot_mode="single", current_slot=None, slot_count=1).write_blockers(
            "boot", both_slots=True
        )
        self.assertTrue(any("--both-slots" in r for r in reasons))

    def test_both_slots_checks_the_other_slot_partition_too(self):
        profile = self.profile(raw={"partition-size:boot_a": "0x4000000"})
        reasons = profile.write_blockers("boot", both_slots=True)
        self.assertTrue(any("boot_b" in r and "not listed" in r for r in reasons))

    def test_slot_names_follow_the_current_slot(self):
        profile = self.profile(current_slot="b")
        self.assertEqual(profile.slot_names("boot"), ["boot_b"])
        self.assertEqual(profile.slot_names("boot", both_slots=True), ["boot_b", "boot_a"])


class TestDeviceCheckCommand(unittest.TestCase):
    def run_check(self, profile=None, lookup_error=None, extra=()):
        args = parse(["device", "check", "--partition", "boot", "--json", *extra])
        select = mock.patch.object(
            cli, "_select_device",
            side_effect=lookup_error, return_value=("S1", "fastboot"),
        )
        prof = mock.patch.object(cli, "profile_device", return_value=profile)
        out = io.StringIO()
        with select, prof, contextlib.redirect_stdout(out):
            rc = cli.cmd_device_check(args)
        return rc, json.loads(out.getvalue())

    def good_profile(self):
        return device.DeviceProfile(
            serial="S1", mode="fastboot", codename="sunfish", slot_mode="ab",
            current_slot="a", bootloader_unlocked=True, probe_ok=True,
        )

    def test_allowed_exits_zero(self):
        rc, payload = self.run_check(self.good_profile())
        self.assertEqual(rc, 0)
        self.assertTrue(payload["allowed"])
        self.assertEqual(payload["blockers"], [])

    def test_blocked_exits_three_and_lists_reasons(self):
        profile = self.good_profile()
        profile.bootloader_unlocked = False
        rc, payload = self.run_check(profile)
        self.assertEqual(rc, cli.EXIT_BLOCKED)
        self.assertFalse(payload["allowed"])
        self.assertTrue(payload["blockers"])

    def test_ambiguous_or_missing_device_is_blocked_not_an_error_path_to_a_write(self):
        rc, payload = self.run_check(lookup_error=LookupError("multiple usable devices attached"))
        self.assertEqual(rc, cli.EXIT_BLOCKED)
        self.assertIn("multiple usable devices", payload["blockers"][0])
        self.assertIsNone(payload["profile"])

    def test_unreadable_image_is_blocked(self):
        rc, payload = self.run_check(self.good_profile(), extra=("--image", "/nonexistent/x.img"))
        self.assertEqual(rc, cli.EXIT_BLOCKED)
        self.assertIn("cannot read image", payload["blockers"][0])

    def test_partition_name_is_validated_at_parse_time(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            parse(["device", "check", "--partition", "../boot"])


class TestCompatibilityFindings(unittest.TestCase):
    def profile(self, **overrides):
        base = dict(serial="S1", mode="fastboot", codename="sunfish", slot_mode="ab",
                    current_slot="a", bootloader_unlocked=True, probe_ok=True,
                    raw={"version-bootloader": "b1"})
        base.update(overrides)
        return device.DeviceProfile(**base)

    def test_matching_device_has_no_findings(self):
        self.assertEqual(
            device.compatibility_findings(self.profile(), "sunfish", "a", "b1"), ([], [])
        )

    def test_other_product_blocks(self):
        blockers, _ = device.compatibility_findings(self.profile(), "cheetah")
        self.assertTrue(any("'cheetah'" in b and "'sunfish'" in b for b in blockers))

    def test_wrong_slot_blocks(self):
        blockers, _ = device.compatibility_findings(self.profile(current_slot="b"), expect_slot="a")
        self.assertTrue(any("wrong slot" in b for b in blockers))

    def test_unknown_device_slot_blocks_a_slot_specific_backup(self):
        blockers, _ = device.compatibility_findings(
            self.profile(slot_mode="unknown", current_slot=None), expect_slot="a"
        )
        self.assertTrue(blockers)

    def test_bootloader_version_mismatch_blocks_only_when_both_known(self):
        blockers, warnings = device.compatibility_findings(self.profile(), expect_bootloader_version="b2")
        self.assertTrue(blockers)
        blockers, warnings = device.compatibility_findings(
            self.profile(raw={}), expect_bootloader_version="b2"
        )
        self.assertEqual(blockers, [])
        self.assertTrue(any("unverified" in w for w in warnings))

    def test_no_expectations_means_no_findings(self):
        self.assertEqual(device.compatibility_findings(self.profile()), ([], []))


class TestDeviceCheckMultiPartition(unittest.TestCase):
    def run_check(self, profile, argv):
        args = parse(["device", "check", "--json", *argv])
        with mock.patch.object(cli, "_select_device", return_value=("S1", "fastboot")), \
                mock.patch.object(cli, "profile_device", return_value=profile), \
                contextlib.redirect_stdout(io.StringIO()) as out:
            rc = cli.cmd_device_check(args)
        return rc, json.loads(out.getvalue())

    def profile(self):
        return device.DeviceProfile(
            serial="S1", mode="fastboot", codename="sunfish", slot_mode="ab", current_slot="a",
            bootloader_unlocked=True, probe_ok=True,
            raw={"partition-size:boot_a": "0x100", "partition-size:vbmeta_a": "0x100"},
        )

    def test_every_partition_is_checked_and_reasons_are_deduplicated(self):
        rc, payload = self.run_check(
            self.profile(), ["--partition", "boot", "--partition", "dtbo", "--partition", "init_boot"]
        )
        self.assertEqual(rc, cli.EXIT_BLOCKED)
        text = " ".join(payload["blockers"])
        self.assertIn("dtbo_a", text)
        self.assertIn("init_boot_a", text)
        self.assertEqual(len(payload["blockers"]), len(set(payload["blockers"])))

    def test_images_must_pair_with_partitions(self):
        rc, payload = self.run_check(
            self.profile(), ["--partition", "boot", "--partition", "vbmeta", "--image", "/x"]
        )
        self.assertEqual(rc, cli.EXIT_BLOCKED)
        self.assertIn("once per --partition", payload["blockers"][0])

    def test_expectations_from_the_backup_are_enforced(self):
        rc, payload = self.run_check(
            self.profile(), ["--partition", "boot", "--expect-product", "cheetah", "--expect-slot", "a"]
        )
        self.assertEqual(rc, cli.EXIT_BLOCKED)
        self.assertTrue(any("cheetah" in b for b in payload["blockers"]))

    def test_matching_multi_partition_restore_is_allowed(self):
        rc, payload = self.run_check(
            self.profile(),
            ["--partition", "boot", "--partition", "vbmeta", "--expect-product", "sunfish",
             "--expect-slot", "a"],
        )
        self.assertEqual((rc, payload["blockers"]), (0, []))


class TestDeviceProfile(unittest.TestCase):
    def test_as_dict_is_json_friendly(self):
        profile = device.DeviceProfile(serial="A", mode="fastboot", vendor="samsung")
        data = profile.as_dict()
        self.assertEqual(data["serial"], "A")
        self.assertEqual(data["vendor"], "samsung")
        self.assertEqual(data["raw"], {})

    def test_supported_needs_a_successful_probe_and_a_non_refused_vendor(self):
        self.assertTrue(device.DeviceProfile(serial="A", mode="adb", probe_ok=True).supported)
        self.assertTrue(
            device.DeviceProfile(serial="A", mode="adb", vendor="google", probe_ok=True).supported
        )
        self.assertFalse(device.DeviceProfile(serial="A", mode="adb").supported)
        self.assertFalse(
            device.DeviceProfile(serial="A", mode="adb", vendor="samsung", probe_ok=True).supported
        )

    def test_refusal_message_none_for_supported_device(self):
        profile = device.DeviceProfile(serial="A", mode="adb", probe_ok=True)
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
        profile = device.DeviceProfile(serial="A", mode="adb", probe_ok=True)
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
