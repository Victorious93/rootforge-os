"""Unit tests for rootforge doctor's result handling."""
import unittest
from unittest import mock

from rootforge.core import doctor


class TestCheckResult(unittest.TestCase):
    def test_status_mapping(self):
        self.assertEqual(doctor.CheckResult("a", True, "d").status, "ok")
        self.assertEqual(doctor.CheckResult("a", False, "d", required=True).status, "fail")
        self.assertEqual(doctor.CheckResult("a", False, "d", required=False).status, "warn")


class TestRunChecks(unittest.TestCase):
    def test_a_raising_check_does_not_abort_the_run(self):
        """One broken check must not hide every other check's result.

        A doctor run is exactly when the rest of the diagnostics matter
        most, so a check that throws is reported as a warning, not an
        exception that ends the run.
        """
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
        self.assertFalse(results[0].required)  # a broken check is not a hard failure
        self.assertTrue(results[1].ok)


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
