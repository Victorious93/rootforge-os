"""Unit tests for the `rootforge boot` command group.

The tag rule is the one that matters: an unvalidated KernelSU tag redirected
a GitHub API query to an arbitrary repository, whose release asset then
became the kernel of a boot image the user flashes.
"""
import argparse
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]
                      / "config/includes.chroot/usr/local/lib"))

from rootforge.core import boot  # noqa: E402
from rootforge.core import cli  # noqa: E402


def parse(argv):
    return cli.build_parser().parse_args(argv)


class TestReleaseTag(unittest.TestCase):
    def test_an_ordinary_tag_passes(self):
        self.assertEqual(boot.release_tag("v0.9.5"), "v0.9.5")
        self.assertEqual(boot.release_tag("latest"), "latest")

    def test_the_url_redirecting_tag_is_rejected(self):
        with self.assertRaises(argparse.ArgumentTypeError) as ctx:
            boot.release_tag("../../../../octocat/Hello-World/releases/latest")
        self.assertIn("different repository", str(ctx.exception))

    def test_a_bare_slash_is_rejected(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            boot.release_tag("v1/../v2")

    def test_an_empty_tag_is_rejected(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            boot.release_tag("")


class TestCodename(unittest.TestCase):
    def test_real_codenames_pass(self):
        for name in ("oriole", "pixel_6a", "sm-g991b", "raven.1"):
            self.assertEqual(boot.device_codename(name), name)

    def test_a_separator_is_rejected(self):
        with self.assertRaises(argparse.ArgumentTypeError) as ctx:
            boot.device_codename("../../escaped")
        self.assertIn("filename", str(ctx.exception))


class TestAndroidVersion(unittest.TestCase):
    def test_a_version_number_passes(self):
        self.assertEqual(boot.android_version("14"), "14")

    def test_a_non_number_is_rejected(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            boot.android_version("14; rm -rf /")

    def test_a_three_digit_version_is_rejected(self):
        # It is matched against release asset names; 140 would match nothing
        # and the failure would surface as "no GKI Image asset found".
        with self.assertRaises(argparse.ArgumentTypeError):
            boot.android_version("140")


class TestDispatch(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.NamedTemporaryFile(suffix=".img", delete=False)
        self._tmp.write(b"ANDROID!" + b"\0" * 100)
        self._tmp.close()
        self.img = self._tmp.name

    def tearDown(self):
        Path(self.img).unlink(missing_ok=True)

    def test_patch_passes_the_options_the_script_expects(self):
        with mock.patch.object(boot, "exec_script", return_value=0) as run:
            boot.dispatch(parse(["boot", "patch", "--stock-boot", self.img,
                                 "--android-version", "14", "--device", "oriole"]))
        run.assert_called_once_with("kernelsu_patch_boot.sh", [
            "--stock-boot", self.img,
            "--android-version", "14",
            "--ksu-version", "latest",
            "--device", "oriole",
        ])

    def test_an_unnamed_device_is_left_to_the_script_default(self):
        with mock.patch.object(boot, "exec_script", return_value=0) as run:
            boot.dispatch(parse(["boot", "patch", "--stock-boot", self.img,
                                 "--android-version", "14"]))
        args = run.call_args[0][1]
        self.assertNotIn("--device", args)

    def test_flash_last_passes_the_flash_flag(self):
        with mock.patch.object(boot, "exec_script", return_value=0) as run:
            boot.dispatch(parse(["boot", "flash-last", "--device", "oriole"]))
        run.assert_called_once_with("kernelsu_patch_boot.sh",
                                    ["--flash", "--device", "oriole"])

    def test_a_failing_script_exit_code_is_passed_through(self):
        with mock.patch.object(boot, "exec_script", return_value=1):
            rc = boot.dispatch(parse(["boot", "flash-last"]))
        self.assertEqual(rc, 1)


class TestParsing(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.NamedTemporaryFile(suffix=".img", delete=False)
        self._tmp.write(b"ANDROID!")
        self._tmp.close()
        self.img = self._tmp.name

    def tearDown(self):
        Path(self.img).unlink(missing_ok=True)

    def test_a_missing_required_option_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["boot", "patch", "--stock-boot", self.img])

    def test_an_abbreviated_flag_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["boot", "patch", "--stock", self.img,
                   "--android-version", "14"])

    def test_a_missing_subcommand_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["boot"])


if __name__ == "__main__":
    unittest.main()
