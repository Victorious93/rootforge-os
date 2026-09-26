"""Unit tests for the `rootforge avd` command group."""
import argparse
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]
                      / "config/includes.chroot/usr/local/lib"))

from rootforge.core import avd  # noqa: E402
from rootforge.core import cli  # noqa: E402


def parse(argv):
    return cli.build_parser().parse_args(argv)


class TestAvdName(unittest.TestCase):
    def test_ordinary_names_pass(self):
        for n in ("pixel6", "test_avd", "avd-34", "a.b"):
            self.assertEqual(avd.avd_name(n), n)

    def test_a_traversing_name_is_rejected(self):
        # setup_rooted_avd.sh's `create` rejected this and `boot` did not, so
        # `boot --name ../../escaped` read its mode from a .conf outside the
        # profile directory. One declaration here covers both.
        with self.assertRaises(argparse.ArgumentTypeError) as ctx:
            avd.avd_name("../../escaped")
        self.assertIn("avd-profiles", str(ctx.exception))

    def test_dot_and_dotdot_are_rejected(self):
        for n in (".", ".."):
            with self.assertRaises(argparse.ArgumentTypeError):
                avd.avd_name(n)


class TestApiLevel(unittest.TestCase):
    def test_a_level_passes(self):
        self.assertEqual(avd.api_level("34"), "34")

    def test_a_non_number_is_rejected(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            avd.api_level("34;rm")


class TestParsing(unittest.TestCase):
    def test_mode_is_required(self):
        with self.assertRaises(SystemExit):
            parse(["avd", "create", "--name", "x"])

    def test_an_unknown_mode_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["avd", "create", "--name", "x", "--mode", "semirooted"])

    def test_an_unknown_abi_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["avd", "create", "--name", "x", "--mode", "rooted",
                   "--abi", "mips"])

    def test_an_abbreviated_flag_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["avd", "create", "--nam", "x", "--mode", "rooted"])

    def test_a_missing_subcommand_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["avd"])

    def test_boot_requires_a_name(self):
        with self.assertRaises(SystemExit):
            parse(["avd", "boot"])


class TestDispatch(unittest.TestCase):
    def test_create_passes_the_subcommand_first(self):
        with mock.patch.object(avd, "exec_script", return_value=0) as run:
            avd.dispatch(parse(["avd", "create", "--name", "t",
                                "--mode", "unrooted"]))
        run.assert_called_once_with("setup_rooted_avd.sh", [
            "create", "--name", "t", "--mode", "unrooted", "--api", "34",
            "--device", "pixel_6", "--abi", "x86_64", "--tag", "google_apis",
        ])

    def test_force_is_appended_only_when_asked(self):
        with mock.patch.object(avd, "exec_script", return_value=0) as run:
            avd.dispatch(parse(["avd", "create", "--name", "t",
                                "--mode", "unrooted", "--force"]))
        self.assertIn("--force", run.call_args[0][1])

    def test_rooted_on_a_play_image_is_refused_before_the_script_runs(self):
        # sdkmanager would otherwise download a multi-GB system image that
        # was never going to work.
        with mock.patch.object(avd, "exec_script", return_value=0) as run:
            rc = avd.dispatch(parse(["avd", "create", "--name", "t",
                                     "--mode", "rooted",
                                     "--tag", "google_apis_playstore"]))
        self.assertEqual(rc, 1)
        run.assert_not_called()

    def test_unrooted_on_a_play_image_is_allowed(self):
        with mock.patch.object(avd, "exec_script", return_value=0) as run:
            avd.dispatch(parse(["avd", "create", "--name", "t",
                                "--mode", "unrooted",
                                "--tag", "google_apis_playstore"]))
        run.assert_called_once()

    def test_boot_passes_the_snapshot_when_given(self):
        with mock.patch.object(avd, "exec_script", return_value=0) as run:
            avd.dispatch(parse(["avd", "boot", "--name", "t",
                                "--snapshot", "clean"]))
        run.assert_called_once_with(
            "setup_rooted_avd.sh", ["boot", "--name", "t", "--snapshot", "clean"])

    def test_list_takes_no_extra_arguments(self):
        with mock.patch.object(avd, "exec_script", return_value=0) as run:
            avd.dispatch(parse(["avd", "list"]))
        run.assert_called_once_with("setup_rooted_avd.sh", ["list"])

    def test_a_failing_script_exit_code_is_passed_through(self):
        with mock.patch.object(avd, "exec_script", return_value=1):
            rc = avd.dispatch(parse(["avd", "list"]))
        self.assertEqual(rc, 1)


if __name__ == "__main__":
    unittest.main()
