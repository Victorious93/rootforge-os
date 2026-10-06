"""Unit tests for rootforge.core.log — JSON-lines output and redaction."""
import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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


if __name__ == "__main__":
    unittest.main()
