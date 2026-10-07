"""Tests for rootforge.core.audit — the CLI-side trail for state-changing commands."""
import contextlib
import io
import json
import os
import stat
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]
                      / "config/includes.chroot/usr/local/lib"))

from rootforge.core import audit, cli, runner  # noqa: E402
from rootforge.core import log as rflog  # noqa: E402


class AuditTestCase(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.home = self.root / "rf"
        env = mock.patch.dict(os.environ, {"ROOTFORGE_HOME": str(self.home)})
        env.start()
        self.addCleanup(env.stop)
        os.environ.pop(rflog.EXECUTION_ID_ENV, None)
        runner.reset_executed_scripts()

    def events(self, name):
        files = sorted((self.home / "logs").glob(f"rootforge-{name}-*.jsonl"))
        self.assertEqual(len(files), 1, f"{name}: {files}")
        return [json.loads(line) for line in files[0].read_text().splitlines()]

    def fake_script(self, body, name="fake.sh"):
        path = self.root / name
        path.write_text("#!/usr/bin/env bash\n" + body + "\n")
        path.chmod(0o755)
        return path


class TestAuditedWrapper(AuditTestCase):
    def test_a_successful_command_writes_started_and_finished(self):
        rc = audit.audited("flash boot", ["flash", "boot", "x.img"], lambda: 0)
        self.assertEqual(rc, 0)
        started, finished = self.events("flash-boot")
        self.assertEqual(started["event"], "command started")
        self.assertEqual(started["command"], "flash boot")
        self.assertEqual(started["argv"], ["flash", "boot", "x.img"])
        self.assertEqual(finished["event"], "command finished")
        self.assertEqual(finished["returncode"], 0)
        self.assertEqual(finished["level"], "info")
        self.assertEqual(started["execution_id"], finished["execution_id"])

    def test_the_exit_status_is_returned_untouched_and_a_nonzero_one_is_a_warning(self):
        for code in (1, 2, 3, 4, 5, 130):
            with self.subTest(code=code):
                self.assertEqual(audit.audited(f"backup c{code}", [], lambda c=code: c), code)
                finished = self.events(f"backup-c{code}")[-1]
                self.assertEqual(finished["returncode"], code)
                self.assertEqual(finished["level"], "warn")

    def test_an_exception_is_recorded_and_re_raised(self):
        def boom():
            raise RuntimeError("boom")
        with self.assertRaises(RuntimeError):
            audit.audited("avd create", [], boom)
        last = self.events("avd-create")[-1]
        self.assertEqual(last["event"], "command crashed")
        self.assertEqual(last["error_type"], "RuntimeError")
        self.assertEqual(last["level"], "error")

    def test_ctrl_c_is_recorded_and_still_propagates(self):
        def interrupted():
            raise KeyboardInterrupt
        with self.assertRaises(KeyboardInterrupt):
            audit.audited("flash boot", [], interrupted)
        self.assertEqual(self.events("flash-boot")[-1]["error_type"], "KeyboardInterrupt")

    def test_a_secret_in_the_arguments_is_redacted_in_the_file(self):
        secret = "sk-ant-api03-abcdefghijklmnop"
        audit.audited("module build", ["module", "build", secret], lambda: 0)
        path = next((self.home / "logs").glob("rootforge-module-build-*.jsonl"))
        self.assertNotIn(secret, path.read_text())
        self.assertIn("REDACTED", path.read_text())

    def test_argv_none_falls_back_to_the_process_arguments(self):
        with mock.patch.object(sys, "argv", ["rootforge", "backup", "list", "dev"]):
            audit.audited("backup list", None, lambda: 0)
        self.assertEqual(self.events("backup-list")[0]["argv"], ["backup", "list", "dev"])

    def test_the_record_says_who_ran_it(self):
        with mock.patch.dict(os.environ, {"SUDO_USER": "alice"}):
            audit.audited("flash boot", [], lambda: 0)
        started = self.events("flash-boot")[0]
        self.assertEqual(started["sudo_user"], "alice")
        self.assertEqual(started["euid"], os.geteuid())

    def test_an_unwritable_log_does_not_stop_the_command(self):
        blocker = self.root / "not-a-dir"
        blocker.write_text("x")
        ran = []
        err = io.StringIO()
        with mock.patch.dict(os.environ, {"ROOTFORGE_HOME": str(blocker / "rf")}), \
                contextlib.redirect_stderr(err):
            rc = audit.audited("flash boot", [], lambda: ran.append(1) or 4)
        self.assertEqual(rc, 4)
        self.assertEqual(ran, [1])
        self.assertIn("will not be recorded", err.getvalue())

    def test_labels_become_safe_file_names(self):
        audit.audited("flash ../../etc/x", [], lambda: 0)
        names = [p.name for p in (self.home / "logs").iterdir()]
        self.assertEqual(len(names), 1)
        self.assertNotIn("/", names[0])
        self.assertTrue(names[0].startswith("rootforge-flash-etc-x-"), names)


class TestThroughTheCli(AuditTestCase):
    """The public path: cli.main -> dispatch -> script, with a fake script."""

    def run_main(self, argv, script=None):
        out, err = io.StringIO(), io.StringIO()
        patches = [mock.patch.object(runner, "find_script", return_value=script)] if script else []
        with contextlib.ExitStack() as stack, \
                contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            for p in patches:
                stack.enter_context(p)
            rc = cli.main(argv)
        return rc, out.getvalue(), err.getvalue()

    def test_a_wrapped_script_run_records_the_script_its_status_and_its_log(self):
        script = self.fake_script(
            'mkdir -p "$ROOTFORGE_HOME/logs"\n'
            'printf "# rootforge execution %s: fake.sh started now\\n" "$ROOTFORGE_EXECUTION_ID" '
            '> "$ROOTFORGE_HOME/logs/fake_run.log"\n'
            "exit 4"
        )
        rc, _, _ = self.run_main(["backup", "create", "testdev"], script)
        self.assertEqual(rc, 4)
        started, finished = self.events("backup-create")
        self.assertEqual(started["argv"], ["backup", "create", "testdev"])
        self.assertEqual(finished["scripts"], [{"script": "backup_partitions.sh", "returncode": 4}])
        self.assertEqual(finished["returncode"], 4)
        self.assertEqual(finished["script_logs"], [str(self.home / "logs" / "fake_run.log")])

    def test_each_group_is_audited(self):
        script = self.fake_script("exit 0")
        image = self.root / "x.img"
        image.write_bytes(b"ANDROID!" + bytes(1016))
        cases = [
            (["flash", "boot", str(image)], "flash-boot"),
            (["backup", "list", "dev"], "backup-list"),
            (["module", "scaffold", "my_mod", "My Mod", "--target", "magisk"], "module-scaffold"),
            (["avd", "list"], "avd-list"),
        ]
        for argv, name in cases:
            with self.subTest(argv=argv):
                self.run_main(argv, script)
                self.assertEqual(self.events(name)[0]["event"], "command started")

    def test_boot_flash_last_and_patch_are_audited_but_boot_inspect_is_not_doubled(self):
        """flash-last writes the boot partition; inspect already logs itself."""
        script = self.fake_script("exit 1")
        self.run_main(["boot", "flash-last"], script)
        self.assertEqual(self.events("boot-flash-last")[0]["command"], "boot flash-last")
        image = self.root / "boot.img"
        image.write_bytes(b"ANDROID!x")
        self.run_main(["boot", "patch", "--stock-boot", str(image), "--android-version", "14"], script)
        self.assertEqual(self.events("boot-patch")[-1]["scripts"][0]["script"], "kernelsu_patch_boot.sh")
        with mock.patch.object(cli.boot.shutil, "which", return_value="/usr/bin/magiskboot"), \
                mock.patch.object(cli.boot.subprocess, "run",
                                  return_value=mock.Mock(returncode=0, stdout="", stderr="")):
            self.run_main(["boot", "inspect", str(image)])
        inspect_events = [e["event"] for e in self.events("boot-inspect")]
        self.assertIn("inspect started", inspect_events)
        self.assertNotIn("command started", inspect_events)

    def test_python_native_commands_are_audited_too(self):
        """backup verify never reaches a script; it used to leave no record."""
        rc, _, _ = self.run_main(["backup", "verify", "nodev", "20240101_000000"])
        self.assertNotEqual(rc, 0)
        started, finished = self.events("backup-verify")
        self.assertEqual(started["command"], "backup verify")
        self.assertEqual(finished["scripts"], [])
        self.assertEqual(finished["returncode"], rc)

    def test_a_rejected_command_line_runs_nothing_and_records_nothing(self):
        with self.assertRaises(SystemExit):
            self.run_main(["backup", "create"])  # missing codename -> argparse exit 2
        self.assertFalse((self.home / "logs").exists())

    def test_read_only_commands_are_not_audited(self):
        self.run_main(["config", "show"])
        logs = list((self.home / "logs").glob("*")) if (self.home / "logs").exists() else []
        self.assertEqual([p.name for p in logs if "config" in p.name], [])

    def test_the_audit_log_is_private(self):
        old = os.umask(0o000)
        self.addCleanup(os.umask, old)
        self.run_main(["avd", "list"], self.fake_script("exit 0"))
        path = next((self.home / "logs").glob("rootforge-avd-list-*.jsonl"))
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_scripts_run_only_by_this_command_are_listed(self):
        """The record is reset per command, not accumulated across a long process."""
        runner._EXECUTED.append({"script": "left_over.sh", "returncode": 9})
        self.run_main(["avd", "list"], self.fake_script("exit 0"))
        finished = self.events("avd-list")[-1]
        self.assertEqual([s["script"] for s in finished["scripts"]], ["setup_rooted_avd.sh"])


class TestScriptLogsFor(AuditTestCase):
    def setUp(self):
        super().setUp()
        self.logs = self.home / "logs"
        self.logs.mkdir(parents=True)

    def write(self, name, text, age=0):
        path = self.logs / name
        path.write_text(text)
        if age:
            past = time.time() - age
            os.utime(path, (past, past))
        return str(path)

    def test_only_logs_stamped_with_this_id_are_found(self):
        mine = self.write("a.log", "# rootforge execution abc12345: x.sh started\nrest")
        self.write("b.log", "# rootforge execution zzzz9999: x.sh started\n")
        self.write("c.log", "no header at all\n")
        self.assertEqual(rflog.script_logs_for("abc12345"), [mine])

    def test_logs_older_than_the_run_are_ignored(self):
        self.write("old.log", "# rootforge execution abc12345: x.sh started\n", age=3600)
        self.assertEqual(rflog.script_logs_for("abc12345", since=time.time() - 60), [])

    def test_a_symlink_is_not_followed(self):
        target = self.root / "elsewhere.log"
        target.write_text("# rootforge execution abc12345: x.sh started\n")
        (self.logs / "link.log").symlink_to(target)
        self.assertEqual(rflog.script_logs_for("abc12345"), [])

    def test_a_missing_log_directory_is_not_an_error(self):
        os.environ["ROOTFORGE_HOME"] = str(self.root / "nowhere")
        self.assertEqual(rflog.script_logs_for("abc12345"), [])

    def test_the_id_must_match_exactly_not_as_a_prefix(self):
        self.write("a.log", "# rootforge execution abc123456789: x.sh started\n")
        self.assertEqual(rflog.script_logs_for("abc12345"), [])


if __name__ == "__main__":
    unittest.main()
