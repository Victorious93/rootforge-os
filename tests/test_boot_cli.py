"""Unit tests for the `rootforge boot` command group.

The tag rule is the one that matters: an unvalidated KernelSU tag redirected
a GitHub API query to an arbitrary repository, whose release asset then
became the kernel of a boot image the user flashes.
"""
import argparse
import hashlib
import io
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


class FakeMagiskboot:
    """A stand-in magiskboot that creates what the real one would."""

    def __init__(self, test, rc=0, produce_repack=True):
        self.test = test
        self.rc = rc
        self.produce_repack = produce_repack
        self.calls = []

    def run(self, argv, **kwargs):
        self.calls.append(list(argv))
        cwd = Path(kwargs.get("cwd", "."))
        if argv[1:2] == ["unpack"] and self.rc == 0:
            (cwd / "kernel").write_bytes(b"KERNEL")
            (cwd / "ramdisk.cpio").write_bytes(b"CPIO")
        if argv[1:2] == ["repack"] and self.rc == 0 and self.produce_repack:
            (cwd / "new-boot.img").write_bytes(b"NEWBOOT")
        return mock.Mock(returncode=self.rc, stdout="", stderr="")


class TestBootCommandsThroughDispatch(unittest.TestCase):
    """The public path: parse, dispatch, run the tool, log, hash."""

    def setUp(self):
        import contextlib
        import os
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        env = mock.patch.dict(os.environ, {"ROOTFORGE_HOME": str(self.root / "rf")})
        env.start()
        self.addCleanup(env.stop)
        self.img = self.root / "boot.img"
        self.img.write_bytes(b"ANDROID!payload")
        self.out = io.StringIO()
        redirect = contextlib.redirect_stdout(self.out)
        redirect.__enter__()
        self.addCleanup(redirect.__exit__, None, None, None)

    def run_boot(self, fake, argv, tool="magiskboot"):
        with mock.patch.object(boot.shutil, "which", return_value=f"/usr/bin/{tool}"), \
                mock.patch.object(boot.subprocess, "run", side_effect=fake.run):
            return boot.dispatch(parse(["boot", *argv]))

    def events(self, name):
        files = list((self.root / "rf" / "logs").glob(f"rootforge-{name}-*.jsonl"))
        self.assertEqual(len(files), 1, files)
        import json
        return [json.loads(line) for line in files[0].read_text().splitlines()]

    def test_inspect_lists_components_and_logs_the_image_hash(self):
        fake = FakeMagiskboot(self)
        rc = self.run_boot(fake, ["inspect", str(self.img)])
        self.assertEqual(rc, 0)
        self.assertIn("kernel", self.out.getvalue())
        started = self.events("boot-inspect")[0]
        self.assertEqual(started["image_sha256"], hashlib.sha256(b"ANDROID!payload").hexdigest())

    def test_inspect_never_modifies_the_original_image(self):
        self.run_boot(FakeMagiskboot(self), ["inspect", str(self.img)])
        self.assertEqual(self.img.read_bytes(), b"ANDROID!payload")
        self.assertEqual(sorted(p.name for p in self.root.iterdir() if p.name != "rf"), ["boot.img"])

    def test_inspect_propagates_a_tool_failure(self):
        rc = self.run_boot(FakeMagiskboot(self, rc=3), ["inspect", str(self.img)])
        self.assertEqual(rc, 3)
        self.assertEqual(self.events("boot-inspect")[-1]["event"], "inspect failed")

    def test_a_missing_image_is_reported_before_any_tool_runs(self):
        fake = FakeMagiskboot(self)
        rc = self.run_boot(fake, ["inspect", str(self.root / "nope.img")])
        self.assertEqual(rc, 1)
        self.assertEqual(fake.calls, [])

    def test_a_missing_tool_is_reported_not_raised(self):
        with mock.patch.object(boot.shutil, "which", return_value=None):
            rc = boot.dispatch(parse(["boot", "inspect", str(self.img)]))
        self.assertEqual(rc, 1)
        self.assertIn("magiskboot not found", self.out.getvalue())

    def test_unpack_then_repack_round_trip_records_the_output_hash(self):
        work = self.root / "work"
        fake = FakeMagiskboot(self)
        self.assertEqual(self.run_boot(fake, ["unpack", str(self.img), str(work)]), 0)
        self.assertTrue((work / "ramdisk.cpio").is_file())
        self.assertEqual(self.run_boot(fake, ["repack", str(work)]), 0)
        finished = self.events("boot-repack")[-1]
        self.assertEqual(finished["output_sha256"], hashlib.sha256(b"NEWBOOT").hexdigest())

    def test_repack_that_produces_nothing_is_a_failure(self):
        """It used to print a warning and exit 0, reporting success with no image."""
        work = self.root / "work"
        work.mkdir()
        (work / "boot.img").write_bytes(b"x")
        rc = self.run_boot(FakeMagiskboot(self, produce_repack=False), ["repack", str(work)])
        self.assertEqual(rc, 1)
        self.assertIn("wasn't produced", self.out.getvalue())

    def test_repack_without_an_unpacked_template_says_to_unpack_first(self):
        work = self.root / "empty"
        work.mkdir()
        rc = self.run_boot(FakeMagiskboot(self), ["repack", str(work)])
        self.assertEqual(rc, 1)
        self.assertIn("boot unpack", self.out.getvalue())

    def test_cpio_runs_the_commands_against_the_unpacked_ramdisk(self):
        work = self.root / "work"
        work.mkdir()
        (work / "ramdisk.cpio").write_bytes(b"CPIO")
        fake = FakeMagiskboot(self)
        rc = self.run_boot(fake, ["cpio", str(work), "ramdisk.cpio", "--", "add 0750 init magiskinit"])
        self.assertEqual(rc, 0)
        self.assertEqual(fake.calls[-1], ["/usr/bin/magiskboot", "cpio", "ramdisk.cpio", "add 0750 init magiskinit"])

    def test_cpio_needs_a_ramdisk_and_commands(self):
        work = self.root / "work"
        work.mkdir()
        fake = FakeMagiskboot(self)
        self.assertEqual(self.run_boot(fake, ["cpio", str(work), "ramdisk.cpio", "--", "x"]), 1)
        (work / "ramdisk.cpio").write_bytes(b"CPIO")
        self.assertEqual(self.run_boot(fake, ["cpio", str(work), "ramdisk.cpio"]), 1)
        self.assertEqual(fake.calls, [])

    def test_verify_reports_the_avbtool_verdict_in_its_exit_code(self):
        fake = FakeMagiskboot(self, rc=0)
        self.assertEqual(self.run_boot(fake, ["verify", str(self.img)], tool="avbtool"), 0)
        self.assertIn("AVB verification passed", self.out.getvalue())
        fake = FakeMagiskboot(self, rc=1)
        self.assertEqual(self.run_boot(fake, ["verify", str(self.img)], tool="avbtool"), 1)
        self.assertIn("failed or image is unsigned", self.out.getvalue())

    def test_patch_and_flash_last_forward_the_serial_to_the_script(self):
        with mock.patch.object(boot, "exec_script", return_value=0) as run:
            boot.dispatch(parse(["boot", "patch", "--stock-boot", str(self.img),
                                 "--android-version", "14", "--serial", "ABC123"]))
            boot.dispatch(parse(["boot", "flash-last", "--serial", "ABC123"]))
        patch_args, flash_args = (c.args[1] for c in run.call_args_list)
        self.assertEqual(patch_args[-2:], ["--serial", "ABC123"])
        self.assertEqual(flash_args, ["--flash", "--serial", "ABC123"])


if __name__ == "__main__":
    unittest.main()
