"""Unit tests for the `rootforge ota` command group.

Pins the validation, the argument order handed to the wrapped script, and the
two failure modes the shell version of this parsing actually hit.
"""
import argparse
import contextlib
import hashlib
import io
import json
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

    def test_extract_goes_through_cmd_extract_so_hashes_are_recorded(self):
        with mock.patch.object(ota, "cmd_extract", return_value=0) as extract, \
                mock.patch.object(ota, "exec_script") as run:
            ota.dispatch(parse(["ota", "extract", self.zip, "--partitions", "boot"]))
        extract.assert_called_once_with(self.zip, None, "boot")
        run.assert_not_called()

    def test_extract_output_option_and_positional_both_reach_cmd_extract(self):
        with mock.patch.object(ota, "cmd_extract", return_value=0) as extract:
            ota.dispatch(parse(["ota", "extract", self.zip, "-o", "/tmp/out"]))
            ota.dispatch(parse(["ota", "extract", self.zip, "/tmp/pos"]))
        self.assertEqual(
            [c.args for c in extract.call_args_list],
            [(self.zip, "/tmp/out", ota.DEFAULT_PARTITIONS), (self.zip, "/tmp/pos", ota.DEFAULT_PARTITIONS)],
        )

    def test_two_different_output_directories_are_refused(self):
        with mock.patch.object(ota, "cmd_extract") as extract, \
                contextlib.redirect_stderr(io.StringIO()) as err:
            rc = ota.dispatch(parse(["ota", "extract", self.zip, "/tmp/a", "-o", "/tmp/b"]))
        self.assertEqual(rc, 2)
        extract.assert_not_called()
        self.assertIn("two different output directories", err.getvalue())

    def test_inspect_identifies_the_ota_rather_than_mounting_an_image(self):
        with mock.patch.object(ota, "cmd_inspect", return_value=0) as inspect, \
                mock.patch.object(ota, "exec_script") as run:
            ota.dispatch(parse(["ota", "inspect", self.zip]))
        inspect.assert_called_once_with(self.zip)
        run.assert_not_called()

    def test_inspect_image_wraps_the_loop_mount_tool(self):
        with mock.patch.object(ota, "exec_script", return_value=0) as run:
            ota.dispatch(parse(["ota", "inspect-image", self.zip, "--mount-point", "/mnt/x"]))
        run.assert_called_once_with("inspect_partition_image.sh", [self.zip, "/mnt/x"])

    def test_a_failing_extract_exit_code_is_passed_through(self):
        with mock.patch.object(ota, "exec_script", return_value=1):
            rc = ota.dispatch(parse(["ota", "extract", self.zip]))
        self.assertEqual(rc, 1)


class TestCmdExtract(unittest.TestCase):
    def setUp(self):
        import os
        import tempfile
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        env = mock.patch.dict(os.environ, {"ROOTFORGE_HOME": str(self.root / "rf")})
        env.start()
        self.addCleanup(env.stop)
        self.ota = self.root / "ota.zip"
        self.ota.write_bytes(b"PK\x03\x04payload")
        self.out = self.root / "out"

    def fake_script(self, produce, rc=0):
        def _run(name, argv, **kwargs):
            self.calls = (name, list(argv))
            self.out.mkdir(parents=True, exist_ok=True)
            for filename, data in produce.items():
                (self.out / filename).write_bytes(data)
            return rc
        return _run

    def extract(self, partitions="boot,vbmeta"):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = ota.cmd_extract(str(self.ota), str(self.out), partitions)
        return rc, out.getvalue()

    def logged(self):
        files = list((self.root / "rf" / "logs").glob("rootforge-ota-extract-*.jsonl"))
        self.assertEqual(len(files), 1)
        return [json.loads(line) for line in files[0].read_text().splitlines()]

    def test_hashes_each_requested_image_and_logs_them(self):
        with mock.patch.object(ota, "exec_script", self.fake_script(
                {"boot.img": b"boot", "vbmeta.img": b"vb"})):
            rc, out = self.extract()
        self.assertEqual(rc, 0)
        self.assertIn(hashlib.sha256(b"boot").hexdigest(), out)
        events = self.logged()
        self.assertEqual([e["event"] for e in events], ["extract started", "extract finished"])
        self.assertEqual(
            events[-1]["extracted"],
            {"boot.img": hashlib.sha256(b"boot").hexdigest(),
             "vbmeta.img": hashlib.sha256(b"vb").hexdigest()},
        )
        self.assertEqual(events[0]["input_sha256"], hashlib.sha256(self.ota.read_bytes()).hexdigest())

    def test_passes_a_known_output_directory_and_the_partition_list_to_the_script(self):
        with mock.patch.object(ota, "exec_script", self.fake_script({"boot.img": b"b"})):
            self.extract("boot")
        self.assertEqual(self.calls, ("extract_ota.sh", [str(self.ota), str(self.out), "--partitions", "boot"]))

    def test_stale_images_in_a_reused_directory_are_not_reported(self):
        self.out.mkdir()
        (self.out / "dtbo.img").write_bytes(b"stale")
        with mock.patch.object(ota, "exec_script", self.fake_script({"boot.img": b"b"})):
            _, out = self.extract("boot")
        self.assertNotIn("dtbo.img", out)
        self.assertEqual(list(self.logged()[-1]["extracted"]), ["boot.img"])

    def test_a_requested_partition_that_was_not_produced_is_reported_not_hashed(self):
        with mock.patch.object(ota, "exec_script", self.fake_script({"boot.img": b"b"})):
            rc, out = self.extract("boot,vbmeta")
        self.assertEqual(rc, 0)
        self.assertIn("vbmeta.img  not produced", out)
        self.assertEqual(self.logged()[-1]["not_produced"], ["vbmeta"])

    def test_an_empty_image_counts_as_not_produced(self):
        with mock.patch.object(ota, "exec_script", self.fake_script({"boot.img": b""})):
            _, out = self.extract("boot")
        self.assertIn("boot.img  not produced", out)

    def test_a_failed_script_is_logged_and_its_exit_code_returned(self):
        with mock.patch.object(ota, "exec_script", self.fake_script({}, rc=7)):
            rc, _ = self.extract()
        self.assertEqual(rc, 7)
        self.assertEqual(self.logged()[-1]["event"], "extract failed")
        self.assertEqual(self.logged()[-1]["returncode"], 7)

    def test_a_missing_input_is_reported_without_running_anything(self):
        with mock.patch.object(ota, "exec_script") as run, contextlib.redirect_stdout(io.StringIO()):
            rc = ota.cmd_extract(str(self.root / "nope.zip"), str(self.out), None)
        self.assertEqual(rc, 1)
        run.assert_not_called()

    def test_no_output_directory_gets_a_timestamped_default(self):
        with mock.patch.object(ota, "exec_script", return_value=0) as run, \
                contextlib.redirect_stdout(io.StringIO()):
            ota.cmd_extract(str(self.ota), None, "boot")
        self.assertRegex(run.call_args.args[1][1], r"^\./ota_extracted_\d{8}_\d{6}$")


if __name__ == "__main__":
    unittest.main()
