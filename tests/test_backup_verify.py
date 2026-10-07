"""Unit tests for the backup integrity contract (rootforge.core.backup)."""
import contextlib
import hashlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from rootforge.core import backup, flashing
from rootforge.core.cli import build_parser


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class BackupTestCase(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.home = Path(tmp.name)
        env = mock.patch.dict(os.environ, {"ROOTFORGE_HOME": str(self.home)})
        env.start()
        self.addCleanup(env.stop)
        self.dir = self.home / "devices" / "pixel" / "backups" / "20260101_000000"
        self.dir.mkdir(parents=True)

    def write_images(self, images):
        for name, data in images.items():
            (self.dir / name).write_bytes(data)

    def write_manifest(self, images, **overrides):
        entries = [
            {"partition": name[:-4], "file": name, "sha256": digest(data),
             "size_bytes": len(data), "method": "fastboot-fetch", "slot": "a"}
            for name, data in images.items()
        ]
        manifest = {
            "manifest_version": 1, "trust": "captured", "codename": "pixel",
            "timestamp": "20260101_000000", "serial": "S1", "complete": True,
            "device": {"product": "pixel", "current_slot": "a"}, "entries": entries,
        }
        manifest.update(overrides)
        (self.dir / "manifest.json").write_text(json.dumps(manifest))
        return manifest

    def write_legacy(self, images, sums=None):
        self.write_images(images)
        lines = [f"{digest(data)}  {name}" for name, data in images.items()]
        (self.dir / "SHA256SUMS").write_text(sums if sums is not None else "\n".join(lines) + "\n")

    def captured(self, images=None):
        images = images or {"boot.img": b"boot-data", "vbmeta.img": b"vbmeta-data"}
        self.write_images(images)
        self.write_manifest(images)
        return images

    def check(self, partitions=None):
        return backup.check_backup(self.dir, partitions)


class TestManifestVerification(BackupTestCase):
    def test_intact_backup_verifies_and_returns_flashable_entries(self):
        self.captured()
        result = self.check()
        self.assertTrue(result.ok, result.problems)
        self.assertEqual(result.kind, "manifest")
        self.assertEqual(result.trust, "captured")
        self.assertEqual([e["partition"] for e in result.entries], ["boot", "vbmeta"])
        self.assertEqual(result.entries[0]["path"], str(self.dir / "boot.img"))
        self.assertEqual(result.device["product"], "pixel")

    def test_changed_image_is_a_mismatch_and_yields_no_entries(self):
        self.captured()
        (self.dir / "boot.img").write_bytes(b"boot-dataX")
        result = self.check()
        self.assertFalse(result.ok)
        self.assertEqual(result.entries, [])
        self.assertIn({"name": "boot.img", "status": "SIZE"}, result.images)

    def test_same_size_different_content_is_a_mismatch(self):
        self.captured()
        (self.dir / "boot.img").write_bytes(b"BOOT-DATA")
        self.assertIn({"name": "boot.img", "status": "MISMATCH"}, self.check().images)

    def test_empty_image_is_rejected(self):
        self.captured()
        (self.dir / "vbmeta.img").write_bytes(b"")
        self.assertIn({"name": "vbmeta.img", "status": "EMPTY"}, self.check().images)

    def test_missing_image_is_rejected(self):
        self.captured()
        (self.dir / "vbmeta.img").unlink()
        result = self.check()
        self.assertFalse(result.ok)
        self.assertIn({"name": "vbmeta.img", "status": "MISSING"}, result.images)

    def test_extra_unlisted_image_fails_the_whole_backup(self):
        """The reported restore flaw: an extra *.img used to be flashed unchecked."""
        self.captured()
        (self.dir / "vendor_boot.img").write_bytes(b"EXTRA-UNCHECKED")
        result = self.check()
        self.assertFalse(result.ok)
        self.assertEqual(result.entries, [])
        self.assertIn({"name": "vendor_boot.img", "status": "UNLISTED"}, result.images)

    def test_symlinked_image_is_rejected_even_if_its_content_matches(self):
        self.captured()
        outside = self.home / "outside.img"
        outside.write_bytes(b"boot-data")
        (self.dir / "boot.img").unlink()
        (self.dir / "boot.img").symlink_to(outside)
        self.assertIn({"name": "boot.img", "status": "SYMLINK"}, self.check().images)

    def test_symlink_to_a_sibling_image_is_also_rejected(self):
        self.captured()
        (self.dir / "vbmeta.img").unlink()
        (self.dir / "vbmeta.img").symlink_to(self.dir / "boot.img")
        self.assertIn({"name": "vbmeta.img", "status": "SYMLINK"}, self.check().images)

    def test_selected_partitions_only_are_verified_and_returned(self):
        self.captured()
        (self.dir / "vbmeta.img").write_bytes(b"corrupt!!!")  # unselected: not examined
        result = self.check(["boot"])
        self.assertTrue(result.ok, result.problems)
        self.assertEqual([e["partition"] for e in result.entries], ["boot"])

    def test_selecting_a_partition_not_in_the_manifest_fails(self):
        self.captured()
        result = self.check(["boot", "dtbo"])
        self.assertFalse(result.ok)
        self.assertTrue(any("dtbo" in p and "not in this backup" in p for p in result.problems))

    def test_incomplete_backup_still_verifies_but_says_so(self):
        images = {"boot.img": b"boot-data"}
        self.write_images(images)
        self.write_manifest(images, complete=False)
        result = self.check()
        self.assertTrue(result.ok)
        self.assertIs(result.complete, False)


class TestManifestSchema(BackupTestCase):
    def broken(self, mutate):
        images = {"boot.img": b"boot-data"}
        self.write_images(images)
        manifest = self.write_manifest(images)
        mutate(manifest)
        (self.dir / "manifest.json").write_text(json.dumps(manifest))
        return self.check()

    def test_unsupported_version(self):
        self.assertFalse(self.broken(lambda m: m.update(manifest_version=2)).ok)

    def test_unknown_trust_value(self):
        self.assertFalse(self.broken(lambda m: m.update(trust="verified-by-me")).ok)

    def test_no_entries(self):
        self.assertFalse(self.broken(lambda m: m.update(entries=[])).ok)

    def test_duplicate_partition_entries(self):
        result = self.broken(lambda m: m["entries"].append(dict(m["entries"][0])))
        self.assertFalse(result.ok)
        self.assertTrue(any("more than once" in p for p in result.problems))

    def test_file_name_must_match_the_partition(self):
        result = self.broken(lambda m: m["entries"][0].update(file="../boot.img"))
        self.assertFalse(result.ok)
        self.assertTrue(any("must be exactly" in p for p in result.problems))

    def test_partition_name_with_path_characters(self):
        result = self.broken(lambda m: m["entries"][0].update(partition="../boot", file="../boot.img"))
        self.assertFalse(result.ok)

    def test_bad_sha256(self):
        self.assertFalse(self.broken(lambda m: m["entries"][0].update(sha256="xyz")).ok)

    def test_bad_sizes(self):
        for size in (0, -1, True, "9", None):
            with self.subTest(size=size):
                self.assertFalse(self.broken(lambda m: m["entries"][0].update(size_bytes=size)).ok)

    def test_invalid_json(self):
        self.write_images({"boot.img": b"x"})
        (self.dir / "manifest.json").write_text("{not json")
        result = self.check()
        self.assertFalse(result.ok)
        self.assertTrue(any("could not be read" in p for p in result.problems))

    def test_manifest_that_is_not_an_object(self):
        self.write_images({"boot.img": b"x"})
        (self.dir / "manifest.json").write_text("[]")
        self.assertFalse(self.check().ok)


class TestLegacyBackups(BackupTestCase):
    def test_legacy_backup_verifies_but_is_labelled_legacy(self):
        self.write_legacy({"boot.img": b"boot"})
        result = self.check()
        self.assertTrue(result.ok)
        self.assertEqual((result.kind, result.trust), ("legacy-sums", "legacy"))
        self.assertEqual(result.entries, [])  # never flashable until imported

    def test_legacy_extra_image_fails(self):
        self.write_legacy({"boot.img": b"boot"})
        (self.dir / "dtbo.img").write_bytes(b"x")
        self.assertFalse(self.check().ok)

    def test_legacy_modified_image_fails(self):
        self.write_legacy({"boot.img": b"boot"})
        (self.dir / "boot.img").write_bytes(b"BOOT")
        self.assertFalse(self.check().ok)

    def test_legacy_malformed_and_traversal_lines_fail(self):
        outside = self.home / "outside.img"
        outside.write_bytes(b"secret")
        self.write_legacy({"boot.img": b"boot"},
                          sums=f"{digest(b'secret')}  ../../../../outside.img\nnot a line\n")
        result = self.check()
        self.assertFalse(result.ok)
        self.assertEqual([i["status"] for i in result.images if i["name"] == "boot.img"], ["UNLISTED"])

    def test_legacy_binary_marker_and_uppercase_digest_are_accepted(self):
        self.write_images({"boot.img": b"boot"})
        (self.dir / "SHA256SUMS").write_text(f"{digest(b'boot').upper()} *boot.img\n")
        self.assertTrue(self.check().ok)

    def test_empty_sums_file_fails(self):
        self.write_images({})
        (self.dir / "SHA256SUMS").write_text("")
        result = self.check()
        self.assertFalse(result.ok)
        self.assertTrue(any("nothing was verified" in p for p in result.problems))

    def test_directory_with_neither_manifest_nor_sums_fails(self):
        (self.dir / "boot.img").write_bytes(b"boot")
        result = self.check()
        self.assertFalse(result.ok)
        self.assertEqual(result.kind, "none")
        self.assertTrue(any("cannot be verified" in p for p in result.problems))

    def test_unknown_backup_directory(self):
        result = backup.check_backup(self.home / "nope")
        self.assertFalse(result.ok)
        self.assertTrue(any("no such backup" in p for p in result.problems))


class TestImportLegacy(BackupTestCase):
    def test_import_writes_a_labelled_manifest_not_a_captured_one(self):
        self.write_legacy({"boot.img": b"boot", "vbmeta.img": b"vb"})
        ok, message = backup.import_legacy(self.dir, "pixel")
        self.assertTrue(ok, message)
        manifest = json.loads((self.dir / "manifest.json").read_text())
        self.assertEqual(manifest["trust"], "legacy-imported")
        self.assertEqual(manifest["device"], {})
        self.assertIsNone(manifest["complete"])
        result = self.check()
        self.assertTrue(result.ok, result.problems)
        self.assertEqual(result.trust, "legacy-imported")

    def test_import_refuses_a_backup_that_does_not_verify(self):
        self.write_legacy({"boot.img": b"boot"})
        (self.dir / "boot.img").write_bytes(b"BOOT")
        ok, _ = backup.import_legacy(self.dir, "pixel")
        self.assertFalse(ok)
        self.assertFalse((self.dir / "manifest.json").exists())

    def test_import_refuses_when_a_manifest_already_exists(self):
        self.captured()
        ok, message = backup.import_legacy(self.dir, "pixel")
        self.assertFalse(ok)
        self.assertIn("already exists", message)

    def test_import_refuses_a_backup_with_no_checksums_at_all(self):
        (self.dir / "boot.img").write_bytes(b"boot")
        ok, _ = backup.import_legacy(self.dir, "pixel")
        self.assertFalse(ok)


class TestCommands(BackupTestCase):
    def run_verify(self, **kwargs):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = backup.cmd_verify("pixel", "20260101_000000", **kwargs)
        return rc, out.getvalue()

    def test_text_output_lists_each_image(self):
        self.captured()
        rc, out = self.run_verify()
        self.assertEqual(rc, 0)
        self.assertIn("[OK]        boot.img", out)
        self.assertIn("All 2 image(s) verified OK.", out)

    def test_mismatch_output_and_exit_code(self):
        self.captured()
        (self.dir / "boot.img").write_bytes(b"BOOT-DATA")
        rc, out = self.run_verify()
        self.assertEqual(rc, 1)
        self.assertIn("[MISMATCH]  boot.img", out)
        self.assertIn("do not restore this backup", out)

    def test_json_output_is_machine_readable(self):
        self.captured()
        rc, out = self.run_verify(as_json=True)
        payload = json.loads(out)
        self.assertEqual(rc, 0)
        self.assertTrue(payload["ok"])
        self.assertEqual(payload["entries"][0]["partition"], "boot")
        self.assertIn("sha256", payload["entries"][0])

    def test_incomplete_backup_is_called_out(self):
        self.write_images({"boot.img": b"b"})
        self.write_manifest({"boot.img": b"b"}, complete=False)
        _, out = self.run_verify()
        self.assertIn("INCOMPLETE", out)

    def test_unknown_backup_fails(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = backup.cmd_verify("pixel", "does_not_exist")
        self.assertEqual(rc, 1)
        self.assertIn("no such backup", out.getvalue())

    def test_import_command_reports_and_returns_status(self):
        (self.dir / "boot.img").write_bytes(b"boot")
        (self.dir / "SHA256SUMS").write_text(f"{digest(b'boot')}  boot.img\n")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = backup.cmd_import_legacy("pixel", "20260101_000000")
        self.assertEqual(rc, 0)
        self.assertIn("legacy-imported", out.getvalue())


class TestBackupCli(unittest.TestCase):
    def parse(self, argv):
        return build_parser().parse_args(argv)

    def rejects(self, argv):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as ctx:
            self.parse(argv)
        self.assertEqual(ctx.exception.code, 2)

    def test_verify_parses_flags(self):
        args = self.parse(["backup", "verify", "pixel", "ts", "--json", "--partitions", "boot,vbmeta"])
        self.assertEqual((args.codename, args.timestamp), ("pixel", "ts"))
        self.assertTrue(args.json)
        self.assertEqual(args.partitions, "boot,vbmeta")

    def test_traversal_in_codename_or_timestamp_is_rejected(self):
        self.rejects(["backup", "verify", "../evil", "ts"])
        self.rejects(["backup", "verify", "pixel", "../../x"])
        self.rejects(["backup", "import-legacy", "pixel", "../x"])

    def test_partition_lists_are_validated(self):
        for bad in ("", "boot,", "boot,boot", "Boot", "../boot", "boot vbmeta"):
            with self.subTest(bad=bad):
                self.rejects(["backup", "create", "pixel", "--partitions", bad])

    def test_dispatch_verify_passes_selection_and_json(self):
        args = self.parse(["backup", "verify", "pixel", "ts", "--partitions", "boot", "--json"])
        with mock.patch.object(backup, "cmd_verify", return_value=1) as verify:
            self.assertEqual(flashing.dispatch(args), 1)
        verify.assert_called_once_with("pixel", "ts", True, ["boot"])

    def test_dispatch_import_legacy(self):
        args = self.parse(["backup", "import-legacy", "pixel", "ts"])
        with mock.patch.object(backup, "cmd_import_legacy", return_value=0) as imp:
            self.assertEqual(flashing.dispatch(args), 0)
        imp.assert_called_once_with("pixel", "ts")

    def test_create_and_restore_forward_new_flags_to_the_scripts(self):
        with mock.patch.object(flashing, "exec_script", return_value=0) as ex:
            flashing.dispatch(self.parse(["backup", "create", "pixel", "--partitions", "boot"]))
            self.assertEqual(ex.call_args[0], ("backup_partitions.sh", ["pixel", "--partitions", "boot"]))
            flashing.dispatch(self.parse([
                "backup", "restore", "pixel", "ts", "--partitions", "boot", "--accept-legacy-import",
            ]))
            self.assertEqual(
                ex.call_args[0],
                ("restore_partitions.sh", ["pixel", "ts", "--partitions", "boot", "--accept-legacy-import"]),
            )


if __name__ == "__main__":
    unittest.main()
