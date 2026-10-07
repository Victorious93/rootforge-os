"""Unit tests for rootforge.core.log — JSON-lines output and redaction."""
import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from rootforge.core import log as rflog
from rootforge.core.log import Logger


class LogTestCase(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.home = Path(tmp.name)
        env = mock.patch.dict(os.environ, {"ROOTFORGE_HOME": str(self.home)})
        env.start()
        self.addCleanup(env.stop)

    def records(self, logger):
        return [json.loads(line) for line in logger.path.read_text().splitlines()]


class TestLogger(LogTestCase):
    def test_writes_one_json_object_per_event_with_standard_fields(self):
        logger = Logger("doctor", execution_id="abcd1234", echo=False)
        logger.info("started")
        logger.error("failed", code=3)
        first, second = self.records(logger)
        self.assertEqual(first["level"], "info")
        self.assertEqual(first["event"], "started")
        self.assertEqual(first["execution_id"], "abcd1234")
        self.assertEqual(first["command"], "doctor")
        self.assertIn("ts", first)
        self.assertEqual((second["level"], second["code"]), ("error", 3))

    def test_log_file_lives_under_rootforge_home_logs(self):
        logger = Logger("boot", execution_id="deadbeef", echo=False)
        self.assertEqual(logger.path, self.home / "logs" / "rootforge-boot-deadbeef.jsonl")

    def test_generated_execution_ids_are_8_hex_chars_and_distinct(self):
        ids = {Logger("x", echo=False).execution_id for _ in range(20)}
        self.assertGreater(len(ids), 1)
        for value in ids:
            self.assertRegex(value, r"^[0-9a-f]{8}$")

    def test_echo_sends_warn_and_error_to_stderr_info_to_stdout(self):
        logger = Logger("cmd", echo=True)
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            logger.info("i")
            logger.warn("w")
            logger.error("e")
        self.assertEqual(out.getvalue(), "[cmd] i\n")
        self.assertEqual(err.getvalue(), "[cmd] w\n[cmd] e\n")

    def test_echo_false_prints_nothing(self):
        logger = Logger("cmd", echo=False)
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            logger.info("i")
        self.assertEqual(out.getvalue(), "")


class TestRedaction(LogTestCase):
    def logged(self, **fields):
        logger = Logger("t", execution_id="00000000", echo=False)
        logger.info("event", **fields)
        return self.records(logger)[0]

    def test_secret_looking_field_names_are_redacted(self):
        record = self.logged(api_key="hunter2", token="t", Password="p", client_secret="s")
        for name in ("api_key", "token", "Password", "client_secret"):
            self.assertEqual(record[name], "***REDACTED***", name)

    def test_nested_secret_fields_are_redacted(self):
        record = self.logged(env={"ANTHROPIC_API_KEY": "abc", "HOME": "/root"},
                             items=[{"password": "x"}])
        self.assertEqual(record["env"]["ANTHROPIC_API_KEY"], "***REDACTED***")
        self.assertEqual(record["env"]["HOME"], "/root")
        self.assertEqual(record["items"][0]["password"], "***REDACTED***")

    def test_known_secret_shapes_are_redacted_from_free_text(self):
        secrets_ = [
            "sk-ant-api03-abcdefghijklmnop",
            "ghp_" + "a" * 36,
            "github_pat_" + "B" * 30,
            "AIza" + "c" * 35,
            "Bearer abcdefghijklmnop.qrstuv",
        ]
        for secret in secrets_:
            with self.subTest(secret=secret[:10]):
                record = self.logged(detail=f"calling api with {secret} now")
                self.assertNotIn(secret, record["detail"])
                self.assertIn("***REDACTED***", record["detail"])
                self.assertIn("calling api with", record["detail"])

    def test_log_file_is_private_even_under_a_permissive_umask(self):
        old = os.umask(0o000)
        self.addCleanup(os.umask, old)
        logger = Logger("doctor", execution_id="priv0001", echo=False)
        logger.info("started")
        self.assertEqual(logger.path.stat().st_mode & 0o777, 0o600)

    def test_event_message_is_redacted_too(self):
        logger = Logger("t", execution_id="00000000", echo=False)
        logger.info("using ghp_" + "z" * 30)
        self.assertNotIn("ghp_z", logger.path.read_text())

    def test_echoed_line_does_not_leak_a_secret_in_the_event(self):
        logger = Logger("t", execution_id="00000000", echo=True)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            logger.info("using ghp_" + "z" * 30)
        self.assertNotIn("ghp_z", out.getvalue())

    def test_ordinary_values_are_untouched(self):
        record = self.logged(count=3, ok=True, path="/tmp/x", note=None)
        self.assertEqual((record["count"], record["ok"], record["path"], record["note"]),
                         (3, True, "/tmp/x", None))


class TestExecutionId(LogTestCase):
    def setUp(self):
        super().setUp()
        env = mock.patch.dict(os.environ)
        env.start()
        self.addCleanup(env.stop)
        os.environ.pop(rflog.EXECUTION_ID_ENV, None)

    def test_a_valid_inherited_id_is_used(self):
        os.environ[rflog.EXECUTION_ID_ENV] = "abc12345"
        self.assertEqual(Logger("doctor", echo=False).execution_id, "abc12345")

    def test_an_unsafe_inherited_id_is_ignored(self):
        """The ID reaches file names and log lines, so it must be a plain token."""
        for bad in ("../../etc/x", "a b", "ab", "x" * 40, "id\nINJECTED", ""):
            os.environ[rflog.EXECUTION_ID_ENV] = bad
            got = Logger("doctor", echo=False).execution_id
            self.assertNotEqual(got, bad, bad)
            self.assertRegex(got, r"^[0-9a-f]{8}$")

    def test_without_a_scope_each_logger_gets_its_own_id(self):
        self.assertNotEqual(Logger("a", echo=False).execution_id, Logger("b", echo=False).execution_id)

    def test_a_scope_gives_every_logger_one_id_and_is_restored_after(self):
        with rflog.execution_scope() as scoped:
            first = Logger("boot-inspect", echo=False)
            second = Logger("boot-verify", echo=False)
            self.assertEqual(first.execution_id, scoped)
            self.assertEqual(second.execution_id, scoped)
            self.assertEqual(os.environ[rflog.EXECUTION_ID_ENV], scoped)
        self.assertNotIn(rflog.EXECUTION_ID_ENV, os.environ)

    def test_a_scope_keeps_an_id_inherited_from_a_calling_script(self):
        os.environ[rflog.EXECUTION_ID_ENV] = "feedbeef"
        with rflog.execution_scope() as scoped:
            self.assertEqual(scoped, "feedbeef")
        self.assertEqual(os.environ[rflog.EXECUTION_ID_ENV], "feedbeef")

    def test_the_id_is_in_every_record_and_the_file_name(self):
        with rflog.execution_scope() as scoped:
            logger = Logger("doctor", echo=False)
            logger.info("x")
        self.assertIn(scoped, logger.path.name)
        self.assertEqual({r["execution_id"] for r in self.records(logger)}, {scoped})

    def test_main_runs_the_command_inside_a_scope_and_children_inherit_it(self):
        from rootforge.core import cli, runner
        seen = {}

        def fake_dispatch(parser, args):
            seen["env_in_cli"] = os.environ.get(rflog.EXECUTION_ID_ENV)
            with mock.patch.object(runner, "find_script", return_value=Path("/bin/true")), \
                    mock.patch.object(runner.subprocess, "run") as run:
                run.return_value = mock.Mock(returncode=0)
                runner.run_script("anything.sh", [])
            seen["env_in_child"] = run.call_args.kwargs["env"].get(rflog.EXECUTION_ID_ENV)
            return 0

        with mock.patch.object(cli, "_dispatch", fake_dispatch):
            self.assertEqual(cli.main(["doctor"]), 0)
        self.assertRegex(seen["env_in_cli"], r"^[0-9a-f]{8}$")
        self.assertEqual(seen["env_in_child"], seen["env_in_cli"])
        self.assertNotIn(rflog.EXECUTION_ID_ENV, os.environ)


class TestLogOwnership(LogTestCase):
    """Under sudo, a root-created 0600 log must still belong to the person who ran it."""

    def setUp(self):
        super().setUp()
        env = mock.patch.dict(os.environ, {"SUDO_USER": "alice"})
        env.start()
        self.addCleanup(env.stop)

    def test_a_new_log_is_handed_to_the_sudo_user_once(self):
        with mock.patch.object(rflog.os, "geteuid", return_value=0), \
                mock.patch.object(rflog.shutil, "chown") as chown:
            logger = Logger("harden", execution_id="own00001", echo=False)
            logger.info("one")
            logger.info("two")
        chown.assert_called_once_with(logger.path, user="alice")

    def test_a_non_root_run_never_chowns(self):
        with mock.patch.object(rflog.os, "geteuid", return_value=1000), \
                mock.patch.object(rflog.shutil, "chown") as chown:
            Logger("harden", execution_id="own00002", echo=False).info("x")
        chown.assert_not_called()

    def test_a_failed_chown_does_not_stop_logging(self):
        with mock.patch.object(rflog.os, "geteuid", return_value=0), \
                mock.patch.object(rflog.shutil, "chown", side_effect=LookupError("no such user")):
            logger = Logger("harden", execution_id="own00003", echo=False)
            logger.info("still written")
        self.assertEqual(self.records(logger)[0]["event"], "still written")


if __name__ == "__main__":
    unittest.main()
