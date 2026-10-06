"""Unit tests for `rootforge backup verify` (rootforge.core.backup)."""
import contextlib
import hashlib
import io
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from rootforge.core import backup, flashing
from rootforge.core.cli import build_parser


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class VerifyTestCase(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.home = Path(tmp.name)
        env = mock.patch.dict(os.environ, {"ROOTFORGE_HOME": str(self.home)})
        env.start()
        self.addCleanup(env.stop)
        self.dir = self.home / "devices" / "pixel" / "backups" / "20260101_000000"
        self.dir.mkdir(parents=True)

    def write_backup(self, images, sums=None):
        lines = []
        for name, data in images.items():
            (self.dir / name).write_bytes(data)
            lines.append(f"{digest(data)}  {name}")
        (self.dir / "SHA256SUMS").write_text(
            sums if sums is not None else "\n".join(lines) + "\n"
        )

    def verify(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = backup.cmd_verify("pixel", "20260101_000000")
        return rc, out.getvalue()


class TestVerifyBackup(VerifyTestCase):
    def test_intact_backup_passes(self):
        self.write_backup({"boot.img": b"boot", "init_boot.img": b"initboot"})
        rc, out = self.verify()
        self.assertEqual(rc, 0)
        self.assertIn("[OK]        boot.img", out)
        self.assertIn("All 2 image(s) verified OK.", out)

    def test_modified_image_is_a_mismatch(self):
        self.write_backup({"boot.img": b"boot"})
        (self.dir / "boot.img").write_bytes(b"boot-corrupted")
        rc, out = self.verify()
        self.assertEqual(rc, 1)
        self.assertIn("[MISMATCH]  boot.img", out)

    def test_truncated_image_is_a_mismatch(self):
        self.write_backup({"boot.img": b"x" * 4096})
        (self.dir / "boot.img").write_bytes(b"x" * 10)
        rc, _ = self.verify()
        self.assertEqual(rc, 1)

    def test_missing_image_is_reported(self):
        self.write_backup({"boot.img": b"boot", "dtbo.img": b"dtbo"})
        (self.dir / "dtbo.img").unlink()
        rc, out = self.verify()
        self.assertEqual(rc, 1)
        self.assertIn("[MISSING]   dtbo.img", out)
        self.assertIn("[OK]        boot.img", out)

    def test_no_sums_file_fails_rather_than_passing_silently(self):
        (self.dir / "boot.img").write_bytes(b"boot")
        rc, out = self.verify()
        self.assertEqual(rc, 1)
        self.assertIn("cannot be verified", out)

    def test_empty_sums_file_fails(self):
        self.write_backup({}, sums="")
        rc, out = self.verify()
        self.assertEqual(rc, 1)
        self.assertIn("nothing was verified", out)

    def test_unknown_backup_fails(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = backup.cmd_verify("pixel", "does_not_exist")
        self.assertEqual(rc, 1)
        self.assertIn("No such backup", out.getvalue())

    def test_malformed_line_fails_even_if_other_images_match(self):
        data = b"boot"
        self.write_backup(
            {"boot.img": data},
            sums=f"{digest(data)}  boot.img\nnot a checksum line\n",
        )
        rc, out = self.verify()
        self.assertEqual(rc, 1)
        self.assertIn("[MALFORMED] not a checksum line", out)

    def test_path_traversal_name_is_not_followed(self):
        outside = self.home / "outside.img"
        outside.write_bytes(b"secret")
        self.write_backup(
            {"boot.img": b"boot"},
            sums=f"{digest(b'secret')}  ../../../../outside.img\n",
        )
        rc, out = self.verify()
        self.assertEqual(rc, 1)
        self.assertIn("[MALFORMED]", out)
        self.assertNotIn("[OK]", out)

    def test_binary_marker_and_uppercase_digest_accepted(self):
        data = b"boot"
        self.write_backup(
            {"boot.img": data},
            sums=f"{digest(data).upper()} *boot.img\n",
        )
        rc, _ = self.verify()
        self.assertEqual(rc, 0)


class TestVerifyCli(unittest.TestCase):
    def test_parses_codename_and_timestamp(self):
        args = build_parser().parse_args(["backup", "verify", "pixel", "20260101_000000"])
        self.assertEqual(args.backup_command, "verify")
        self.assertEqual((args.codename, args.timestamp), ("pixel", "20260101_000000"))

    def test_traversal_in_codename_or_timestamp_is_rejected(self):
        for argv in (
            ["backup", "verify", "../evil", "ts"],
            ["backup", "verify", "pixel", "../../x"],
        ):
            with self.subTest(argv=argv), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as ctx:
                    build_parser().parse_args(argv)
                self.assertEqual(ctx.exception.code, 2)

    def test_dispatch_returns_verifier_exit_code(self):
        args = build_parser().parse_args(["backup", "verify", "pixel", "ts"])
        with mock.patch.object(backup, "cmd_verify", return_value=1) as verify:
            self.assertEqual(flashing.dispatch(args), 1)
        verify.assert_called_once_with("pixel", "ts")


if __name__ == "__main__":
    unittest.main()
