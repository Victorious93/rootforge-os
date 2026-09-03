"""Unit tests for the `rootforge ota` command group.

Pins the validation, the argument order handed to the wrapped script, and the
two failure modes the shell version of this parsing actually hit.
"""
import argparse
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]
                      / "config/includes.chroot/usr/local/lib"))

from rootforge.core import ota  # noqa: E402
from rootforge.core import cli  # noqa: E402


def parse(argv):
    return cli.build_parser().parse_args(argv)


class TestPartitionList(unittest.TestCase):
    def test_a_normal_list_passes_through(self):
        self.assertEqual(ota.partition_list("boot,init_boot"), "boot,init_boot")

    def test_surrounding_whitespace_is_trimmed(self):
        self.assertEqual(ota.partition_list("boot, init_boot "), "boot,init_boot")

    def test_an_empty_list_is_rejected(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            ota.partition_list("   ")

    def test_a_trailing_comma_is_rejected(self):
        # 'boot,' would reach payload-dumper-go as a request for a partition
        # named '', which is a silent no-op rather than an error.
        with self.assertRaises(argparse.ArgumentTypeError) as ctx:
            ota.partition_list("boot,")
        self.assertIn("trailing comma", str(ctx.exception))

    def test_a_path_is_not_a_partition_name(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            ota.partition_list("boot,../etc/passwd")

    def test_underscores_and_hyphens_are_real_partition_names(self):
        self.assertEqual(ota.partition_list("init_boot,vendor-boot"),
                         "init_boot,vendor-boot")


class TestFileValidation(unittest.TestCase):
    def test_a_missing_input_is_rejected(self):
        with self.assertRaises(argparse.ArgumentTypeError) as ctx:
            ota.existing_file("/nonexistent/ota.zip")
        self.assertIn("not found", str(ctx.exception))

    def test_an_empty_input_is_rejected(self):
        import tempfile
        with tempfile.NamedTemporaryFile() as fh:
            with self.assertRaises(argparse.ArgumentTypeError) as ctx:
                ota.existing_file(fh.name)
            self.assertIn("empty", str(ctx.exception))


class TestParsing(unittest.TestCase):
    def setUp(self):
        import tempfile
        self._tmp = tempfile.NamedTemporaryFile(suffix=".zip", delete=False)
        self._tmp.write(b"PK\x03\x04payload")
        self._tmp.close()
        self.zip = self._tmp.name

    def tearDown(self):
        Path(self.zip).unlink(missing_ok=True)

    def test_the_shell_bug_is_unrepresentable(self):
        # `extract_ota.sh ota.zip --partitions boot` read the flag as the
        # output directory and extracted into a directory named
        # "--partitions", leaving the partition list at its default. Here the
        # output directory is a flag, so there is nothing to confuse.
        args = parse(["ota", "extract", self.zip, "--partitions", "boot"])
        self.assertEqual(args.partitions, "boot")
        self.assertIsNone(args.output_dir)

    def test_the_default_partition_list_is_used_when_not_given(self):
        args = parse(["ota", "extract", self.zip])
        self.assertEqual(args.partitions, ota.DEFAULT_PARTITIONS)

    def test_an_abbreviated_flag_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["ota", "extract", self.zip, "--partition", "boot"])

    def test_a_missing_subcommand_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["ota"])


class TestDispatch(unittest.TestCase):
    def setUp(self):
        import tempfile
        self._tmp = tempfile.NamedTemporaryFile(suffix=".zip", delete=False)
        self._tmp.write(b"PK\x03\x04payload")
        self._tmp.close()
        self.zip = self._tmp.name

    def tearDown(self):
        Path(self.zip).unlink(missing_ok=True)

    def test_extract_passes_the_input_first(self):
        with mock.patch.object(ota, "exec_script", return_value=0) as run:
            ota.dispatch(parse(["ota", "extract", self.zip, "--partitions", "boot"]))
        run.assert_called_once_with(
            "extract_ota.sh", [self.zip, "--partitions", "boot"])

    def test_extract_places_the_output_dir_before_the_flag(self):
        # The script reads the output directory positionally, and only when it
        # does not start with '-'. Order is load-bearing.
        with mock.patch.object(ota, "exec_script", return_value=0) as run:
            ota.dispatch(parse(["ota", "extract", self.zip, "-o", "/tmp/out"]))
        run.assert_called_once_with(
            "extract_ota.sh",
            [self.zip, "/tmp/out", "--partitions", ota.DEFAULT_PARTITIONS])

    def test_inspect_passes_the_mount_point_second(self):
        with mock.patch.object(ota, "exec_script", return_value=0) as run:
            ota.dispatch(parse(["ota", "inspect", self.zip,
                                "--mount-point", "/mnt/x"]))
        run.assert_called_once_with(
            "inspect_partition_image.sh", [self.zip, "/mnt/x"])

    def test_a_failing_script_exit_code_is_passed_through(self):
        with mock.patch.object(ota, "exec_script", return_value=1):
            rc = ota.dispatch(parse(["ota", "extract", self.zip]))
        self.assertEqual(rc, 1)


if __name__ == "__main__":
    unittest.main()
