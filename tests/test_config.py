"""Unit tests for rootforge.core.config's layering and error handling."""
import contextlib
import copy
import io
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from rootforge.core import config


class ConfigTestCase(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.xdg = self.root / "xdg"
        self.home = self.root / "rfhome"
        self.project = self.root / "project" / "sub"
        self.project.mkdir(parents=True)
        env = mock.patch.dict(
            os.environ,
            {"XDG_CONFIG_HOME": str(self.xdg), "ROOTFORGE_HOME": str(self.home)},
        )
        env.start()
        self.addCleanup(env.stop)

    def write(self, path: Path, text: str) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def user(self, text):
        return self.write(self.xdg / "rootforge" / "config.yaml", text)

    def project_file(self, text):
        return self.write(self.root / "project" / "rootforge.yaml", text)

    def device(self, codename, text):
        return self.write(self.home / "devices" / codename / "rootforge.yaml", text)

    def load(self, codename=None):
        return config.load_config(codename=codename, project_dir=self.project)


class TestLayering(ConfigTestCase):
    def test_defaults_only_when_no_files_exist(self):
        cfg = self.load()
        self.assertEqual(cfg["_sources"], [])
        self.assertEqual(cfg["backup"]["partitions"], config.DEFAULTS["backup"]["partitions"])

    def test_precedence_is_defaults_user_project_device(self):
        self.user("backup:\n  partitions: [a]\n  level: user\n")
        self.project_file("backup:\n  level: project\n")
        self.device("pixel", "backup:\n  level: device\n")
        self.assertEqual(self.load()["backup"]["level"], "project")
        self.assertEqual(self.load("pixel")["backup"]["level"], "device")

    def test_nested_dicts_merge_instead_of_replacing(self):
        self.project_file("backup:\n  compress: true\n")
        cfg = self.load()
        self.assertTrue(cfg["backup"]["compress"])
        self.assertIn("boot", cfg["backup"]["partitions"])

    def test_lists_are_replaced_not_concatenated(self):
        self.project_file("backup:\n  partitions: [boot]\n")
        self.assertEqual(self.load()["backup"]["partitions"], ["boot"])

    def test_sources_lists_only_files_actually_read_in_order(self):
        user = self.user("a: 1\n")
        proj = self.project_file("b: 2\n")
        dev = self.device("pixel", "c: 3\n")
        self.assertEqual(self.load("pixel")["_sources"], [str(user), str(proj), str(dev)])

    def test_device_file_ignored_without_codename(self):
        self.device("pixel", "backup:\n  level: device\n")
        self.assertNotIn("level", self.load()["backup"])

    def test_project_config_found_by_walking_up(self):
        self.project_file("found: true\n")
        self.assertTrue(self.load()["found"])

    def test_loading_does_not_mutate_defaults(self):
        before = copy.deepcopy(config.DEFAULTS)
        self.project_file("backup:\n  partitions: [x]\n  extra: 1\n")
        self.load()
        self.assertEqual(config.DEFAULTS, before)


class TestErrors(ConfigTestCase):
    def test_malformed_yaml_raises_config_error_naming_the_file(self):
        path = self.project_file("backup: [unclosed\n")
        with self.assertRaises(config.ConfigError) as ctx:
            self.load()
        self.assertIn(str(path), str(ctx.exception))

    def test_non_mapping_top_level_raises(self):
        self.project_file("- just\n- a list\n")
        with self.assertRaises(config.ConfigError):
            self.load()

    def test_empty_file_is_treated_as_empty_config(self):
        self.project_file("")
        self.assertEqual(self.load()["backup"], config.DEFAULTS["backup"])

    def test_yaml_cannot_construct_arbitrary_objects(self):
        self.project_file("x: !!python/object/apply:os.system ['true']\n")
        with self.assertRaises(config.ConfigError):
            self.load()


class TestSchemaValidation(ConfigTestCase):
    def rejects(self, text, fragment):
        path = self.project_file(text)
        with self.assertRaises(config.ConfigError) as ctx:
            self.load()
        self.assertIn(fragment, str(ctx.exception))
        self.assertIn(str(path), str(ctx.exception))

    def test_partitions_must_be_a_list(self):
        self.rejects("backup:\n  partitions: boot\n", "non-empty list")

    def test_partitions_must_not_be_empty(self):
        self.rejects("backup:\n  partitions: []\n", "non-empty list")

    def test_partition_names_are_restricted_to_safe_characters(self):
        for bad in ("../boot", "Boot", "boot;reboot", "a b", "1", "''"):
            with self.subTest(bad=bad):
                self.rejects(f"backup:\n  partitions: [{bad}]\n", "not a partition name")

    def test_duplicate_partitions_are_rejected(self):
        self.rejects("backup:\n  partitions: [boot, boot]\n", "more than once")

    def test_backup_must_be_a_mapping(self):
        self.rejects("backup: [a]\n", "must be a mapping")

    def test_invalid_value_in_the_user_layer_is_caught_before_merging(self):
        self.user("backup:\n  partitions: [../x]\n")
        with self.assertRaises(config.ConfigError):
            self.load()

    def test_invalid_device_layer_is_caught(self):
        self.device("pixel", "backup:\n  partitions: bad name\n")
        with self.assertRaises(config.ConfigError):
            self.load("pixel")

    def test_unreadable_file_is_a_config_error(self):
        path = self.project_file("a: 1\n")
        with mock.patch.object(Path, "open", side_effect=PermissionError(13, "Permission denied")):
            with self.assertRaises(config.ConfigError) as ctx:
                self.load()
        self.assertIn("cannot be read", str(ctx.exception))

    def test_valid_override_is_accepted(self):
        self.project_file("backup:\n  partitions: [boot, vbmeta]\n")
        self.assertEqual(self.load()["backup"]["partitions"], ["boot", "vbmeta"])


class TestJsonOutput(ConfigTestCase):
    def show(self, **kwargs):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err), mock.patch.object(
            config, "_find_project_config", return_value=None
        ):
            rc = config.cmd_show(**kwargs)
        return rc, out.getvalue(), err.getvalue()

    def test_json_is_parseable_and_includes_sources(self):
        user = self.user("backup:\n  partitions: [boot]\n")
        rc, out, _ = self.show(as_json=True)
        data = __import__("json").loads(out)
        self.assertEqual(rc, 0)
        self.assertEqual(data["backup"]["partitions"], ["boot"])
        self.assertEqual(data["_sources"], [str(user)])

    def test_json_mode_keeps_errors_off_stdout(self):
        self.user("backup:\n  partitions: bad\n")
        rc, out, err = self.show(as_json=True)
        self.assertEqual(rc, 1)
        self.assertEqual(out, "")
        self.assertIn("Config error", err)


class TestCmdShow(ConfigTestCase):
    def run_show(self, codename=None):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), mock.patch.object(
            config, "_find_project_config", return_value=None
        ):
            rc = config.cmd_show(codename)
        return rc, out.getvalue()

    def test_defaults_only_message(self):
        rc, out = self.run_show()
        self.assertEqual(rc, 0)
        self.assertIn("defaults only", out)
        self.assertNotIn("_sources", out)

    def test_error_is_reported_with_exit_1(self):
        self.user("a: [broken\n")
        rc, out = self.run_show()
        self.assertEqual(rc, 1)
        self.assertIn("Config error", out)

    def test_codename_applies_device_override(self):
        self.device("pixel", "marker: from-device\n")
        rc, out = self.run_show("pixel")
        self.assertEqual(rc, 0)
        self.assertIn("marker: from-device", out)


if __name__ == "__main__":
    unittest.main()
