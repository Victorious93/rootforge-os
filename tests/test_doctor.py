"""Unit tests for rootforge doctor's result handling."""
import contextlib
import io
import json
import unittest
from pathlib import Path
from unittest import mock

from rootforge.core import cli, doctor


class TestCheckResult(unittest.TestCase):
    def test_status_mapping(self):
        self.assertEqual(doctor.CheckResult("a", True, "d").status, "ok")
        self.assertEqual(doctor.CheckResult("a", False, "d", required=True).status, "fail")
        self.assertEqual(doctor.CheckResult("a", False, "d", required=False).status, "warn")


class TestRunChecks(unittest.TestCase):
    def test_a_raising_check_does_not_abort_the_run(self):
        """One broken check must not hide every other check's result."""
        def boom():
            raise RuntimeError("kaboom")

        boom.__name__ = "check_boom"

        def fine():
            return doctor.CheckResult("fine", True, "ok")

        with mock.patch.object(doctor, "CHECKS", [boom, fine]):
            results = doctor.run_checks()

        self.assertEqual(len(results), 2)
        self.assertEqual(results[0].name, "boom")
        self.assertIn("kaboom", results[0].detail)
        self.assertTrue(results[1].ok)

    def test_a_crashed_required_check_is_still_a_failure(self):
        """The check could not establish health, so it must not pass as a warning."""
        def boom():
            raise RuntimeError("kaboom")

        boom.__name__ = "check_boom"
        with mock.patch.object(doctor, "CHECKS", [boom]):
            (result,) = doctor.run_checks()
        self.assertTrue(result.required)
        self.assertEqual(result.status, "fail")

    def test_a_crashed_optional_check_is_only_a_warning(self):
        @doctor.optional_check
        def boom():
            raise RuntimeError("kaboom")

        boom.__name__ = "check_some_optional_thing"
        with mock.patch.object(doctor, "CHECKS", [boom]):
            (result,) = doctor.run_checks()
        self.assertEqual(result.name, "some-optional-thing")
        self.assertEqual(result.status, "warn")

    def test_declared_severity_matches_what_each_real_check_reports(self):
        """Severity is declared once; a check may not report a different one."""
        for check in doctor.CHECKS:
            with self.subTest(check=check.__name__):
                result = check()
                if doctor._is_optional(check):
                    self.assertFalse(result.required, "declared optional but reports required")
                elif result.ok:
                    continue  # a passing required check may carry required=False (e.g. a dir not yet created)
                else:
                    self.assertTrue(result.required, "declared required but its failure is only a warning")

    def test_jq_is_a_required_check_and_pyyaml_an_optional_one(self):
        self.assertIn(doctor.check_jq, doctor.CHECKS)
        self.assertFalse(doctor._is_optional(doctor.check_jq))
        self.assertIn(doctor.check_pyyaml, doctor.CHECKS)
        self.assertTrue(doctor._is_optional(doctor.check_pyyaml))


class TestPublicPath(unittest.TestCase):
    """Behaviour through `rootforge doctor` itself, not just the helpers."""

    def setUp(self):
        import os
        import tempfile
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.home = Path(tmp.name)
        env = mock.patch.dict(os.environ, {"ROOTFORGE_HOME": str(self.home)})
        env.start()
        self.addCleanup(env.stop)

    def run_cli(self, checks, *argv):
        out = io.StringIO()
        with mock.patch.object(doctor, "CHECKS", checks), contextlib.redirect_stdout(out):
            rc = cli.main(["doctor", *argv])
        return rc, out.getvalue()

    def test_a_crashed_required_check_exits_one_through_the_cli(self):
        def check_disk_space():
            raise OSError("statvfs failed")

        rc, out = self.run_cli([check_disk_space], "--json")
        payload = json.loads(out)
        self.assertEqual(rc, 1)
        self.assertEqual(payload["failed"], 1)
        self.assertEqual(payload["checks"][0]["status"], "fail")

    def test_a_crashed_optional_check_still_exits_zero_through_the_cli(self):
        @doctor.optional_check
        def check_docker():
            raise OSError("socket error")

        rc, _ = self.run_cli([check_docker])
        self.assertEqual(rc, 0)

    def test_events_are_written_to_the_audit_log(self):
        def check_fine():
            return doctor.CheckResult("fine", True, "ok")

        def check_bad():
            return doctor.CheckResult("bad", False, "broken")

        rc, _ = self.run_cli([check_fine, check_bad], "--quiet")
        self.assertEqual(rc, 1)
        logs = list((self.home / "logs").glob("rootforge-doctor-*.jsonl"))
        self.assertEqual(len(logs), 1)
        events = [json.loads(line) for line in logs[0].read_text().splitlines()]
        self.assertEqual([e["event"] for e in events], ["doctor started", "check", "check", "doctor finished"])
        self.assertEqual(events[-1]["required_failures"], 1)
        self.assertEqual(events[2]["level"], "error")

    def test_an_unwritable_log_directory_does_not_stop_the_report(self):
        with mock.patch.object(doctor, "Logger", side_effect=PermissionError("read-only")):
            rc, out = self.run_cli([lambda: doctor.CheckResult("fine", True, "ok")])
        self.assertEqual(rc, 0)
        self.assertIn("fine", out)


class TestExitCodes(unittest.TestCase):
    def run_with(self, results, **kwargs):
        with mock.patch.object(doctor, "run_checks", return_value=results):
            with mock.patch("builtins.print"):
                return doctor.run_doctor(**kwargs)

    def test_all_ok_exits_zero(self):
        self.assertEqual(self.run_with([doctor.CheckResult("a", True, "d")]), 0)

    def test_required_failure_exits_one(self):
        self.assertEqual(
            self.run_with([doctor.CheckResult("a", False, "d", required=True)]), 1
        )

    def test_warning_alone_exits_zero(self):
        self.assertEqual(
            self.run_with([doctor.CheckResult("a", False, "d", required=False)]), 0
        )

    def test_strict_turns_a_warning_into_a_failure(self):
        self.assertEqual(
            self.run_with(
                [doctor.CheckResult("a", False, "d", required=False)], strict=True
            ),
            1,
        )


class TestJsonOutput(unittest.TestCase):
    def test_json_mode_emits_parseable_output(self):
        import json

        results = [
            doctor.CheckResult("a", True, "fine"),
            doctor.CheckResult("b", False, "broken", required=True),
        ]
        printed = []
        with mock.patch.object(doctor, "run_checks", return_value=results):
            with mock.patch("builtins.print", side_effect=printed.append):
                rc = doctor.run_doctor(as_json=True)

        self.assertEqual(rc, 1)
        payload = json.loads(printed[0])
        self.assertEqual(payload["failed"], 1)
        self.assertEqual(payload["warnings"], 0)
        self.assertEqual([c["status"] for c in payload["checks"]], ["ok", "fail"])


if __name__ == "__main__":
    unittest.main()
