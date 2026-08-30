"""Unit tests for `rootforge flash` and `rootforge backup`.

These groups were ported before the lower-stakes ones because a mis-parsed
argument here costs a device rather than a retry. The tests pin the specific
bugs their scripts actually had, not just generic parsing.
"""
import argparse
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from rootforge.core import flashing
from rootforge.core.cli import build_parser


def parse(argv):
    return build_parser().parse_args(argv)


class TestPathComponentValidation(unittest.TestCase):
    """Codename and timestamp become directory names under devices/.

    Unvalidated, `backup_partitions.sh '../../escaped'` wrote outside that
    tree, and `restore_partitions.sh dev '../../../../evil'` read every .img
    from an arbitrary directory and flashed them — the SHA256SUMS gate does
    not catch it, because an arbitrary directory has none, so integrity
    checking degrades to a warning and the flash proceeds.
    """

    def test_accepts_ordinary_names(self):
        for ok in ("bluejay", "test-dev", "dev_1", "20240101_000000", "a.b"):
            self.assertEqual(flashing.path_component(ok), ok)

    def test_rejects_parent_references(self):
        for escape in ("..", "../x", "../../escaped", "a/../../b"):
            with self.assertRaises(argparse.ArgumentTypeError):
                flashing.path_component(escape)

    def test_rejects_separators(self):
        for escape in ("a/b", "/abs", "a/"):
            with self.assertRaises(argparse.ArgumentTypeError):
                flashing.path_component(escape)

    def test_rejects_dot_and_empty(self):
        for bad in (".", ""):
            with self.assertRaises(argparse.ArgumentTypeError):
                flashing.path_component(bad)

    def test_error_explains_the_consequence(self):
        with self.assertRaises(argparse.ArgumentTypeError) as ctx:
            flashing.path_component("../x")
        self.assertIn("escape that tree", str(ctx.exception))


class TestSerialValidation(unittest.TestCase):
    def test_accepts_usb_and_network_serials(self):
        for ok in ("ABC123", "192.168.1.5:5555", "emulator-5554", "a_b.c-d"):
            self.assertEqual(flashing.device_serial(ok), ok)

    def test_rejects_a_path(self):
        """The round-1 bug: `flash_patched_boot.sh boot.img` set SERIAL to the
        image path, producing `fastboot -s /path/to/boot.img`."""
        with self.assertRaises(argparse.ArgumentTypeError):
            flashing.device_serial("/tmp/boot.img")

    def test_rejects_shell_metacharacters(self):
        for bad in ("a b", "a;b", "a$b", "a|b"):
            with self.assertRaises(argparse.ArgumentTypeError):
                flashing.device_serial(bad)


class TestImageValidation(unittest.TestCase):
    def test_missing_image_is_rejected(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            flashing.existing_image("/nonexistent/boot.img")

    def test_empty_image_is_rejected(self):
        """An empty file is a failed download, not an image to flash."""
        with tempfile.NamedTemporaryFile() as tmp:
            with self.assertRaises(argparse.ArgumentTypeError):
                flashing.existing_image(tmp.name)

    def test_a_real_image_passes(self):
        with tempfile.NamedTemporaryFile() as tmp:
            tmp.write(b"ANDROID!")
            tmp.flush()
            self.assertEqual(flashing.existing_image(tmp.name), tmp.name)


class TestNoAbbreviation(unittest.TestCase):
    """argparse accepts unambiguous prefixes by default.

    `--both-slot` silently meaning `--both-slots` is bad enough on a command
    that writes a boot partition; worse, adding a flag later can change what
    an existing abbreviation resolves to. Every parser sets allow_abbrev=False.
    """

    def setUp(self):
        self.tmp = tempfile.NamedTemporaryFile(delete=False)
        self.tmp.write(b"ANDROID!")
        self.tmp.close()
        self.img = self.tmp.name

    def tearDown(self):
        Path(self.img).unlink(missing_ok=True)

    def test_abbreviated_flag_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["flash", "boot", self.img, "--both-slot"])

    def test_abbreviated_option_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["flash", "boot", self.img, "--partitio", "boot"])

    def test_the_full_flag_still_works(self):
        args = parse(["flash", "boot", self.img, "--both-slots"])
        self.assertTrue(args.both_slots)


class TestDispatch(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.NamedTemporaryFile(delete=False)
        self.tmp.write(b"ANDROID!")
        self.tmp.close()
        self.img = self.tmp.name

    def tearDown(self):
        Path(self.img).unlink(missing_ok=True)

    def call(self, argv):
        with mock.patch.object(flashing, "exec_script", return_value=0) as ex:
            flashing.dispatch(parse(argv))
        return ex.call_args[0]

    def test_flash_passes_image_then_partition(self):
        name, args = self.call(["flash", "boot", self.img])
        self.assertEqual(name, "flash_patched_boot.sh")
        self.assertEqual(args[:2], [self.img, "boot"])

    def test_flash_serial_is_positional_after_flags(self):
        _, args = self.call(
            ["flash", "boot", self.img, "--both-slots", "--serial", "ABC123"]
        )
        self.assertIn("--both-slots", args)
        self.assertEqual(args[-1], "ABC123")

    def test_backup_list_omits_the_timestamp(self):
        """restore_partitions.sh lists when given no timestamp."""
        name, args = self.call(["backup", "list", "bluejay"])
        self.assertEqual(name, "restore_partitions.sh")
        self.assertEqual(args, ["bluejay"])

    def test_backup_restore_passes_codename_then_timestamp(self):
        name, args = self.call(["backup", "restore", "bluejay", "20240101_000000"])
        self.assertEqual(name, "restore_partitions.sh")
        self.assertEqual(args, ["bluejay", "20240101_000000"])

    def test_backup_create_uses_the_backup_script(self):
        name, args = self.call(["backup", "create", "bluejay"])
        self.assertEqual(name, "backup_partitions.sh")
        self.assertEqual(args, ["bluejay"])

    def test_exit_code_passes_through(self):
        with mock.patch.object(flashing, "exec_script", return_value=1):
            self.assertEqual(flashing.dispatch(parse(["backup", "list", "d"])), 1)


class TestRejectedBeforeAnythingRuns(unittest.TestCase):
    """Validation must happen before the script is invoked at all."""

    def assert_no_script_run(self, argv):
        with mock.patch.object(flashing, "exec_script") as ex:
            with self.assertRaises(SystemExit):
                flashing.dispatch(parse(argv))
            ex.assert_not_called()

    def test_traversing_codename_runs_nothing(self):
        self.assert_no_script_run(["backup", "create", "../../escaped"])

    def test_traversing_timestamp_runs_nothing(self):
        self.assert_no_script_run(["backup", "restore", "dev", "../../../../evil"])


if __name__ == "__main__":
    unittest.main()
