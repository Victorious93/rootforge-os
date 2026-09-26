"""Unit tests for `rootforge module` and the script runner behind it.

The point of wrapping the shell scripts is the argument handling, so that is
what these pin: the four failure modes every bug sweep in this repository
re-found in hand-written shell parsing, now handled structurally by argparse.
"""
import argparse
import unittest
from unittest import mock

from rootforge.core import module as module_cmd
from rootforge.core import runner
from rootforge.core.cli import build_parser


def parse(argv):
    return build_parser().parse_args(argv)


class TestModuleIdValidation(unittest.TestCase):
    def test_accepts_a_normal_id(self):
        self.assertEqual(module_cmd.valid_module_id("mymod"), "mymod")

    def test_accepts_the_full_permitted_character_set(self):
        for ok in ("a", "mod_1", "my.mod", "my-mod", "A1._-"):
            self.assertEqual(module_cmd.valid_module_id(ok), ok)

    def test_rejects_a_leading_digit(self):
        """lint_module.sh requires the id to start with a letter."""
        with self.assertRaises(argparse.ArgumentTypeError):
            module_cmd.valid_module_id("9mod")

    def test_rejects_punctuation(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            module_cmd.valid_module_id("bad-id!")

    def test_rejects_a_path_separator(self):
        """The id becomes a directory name; '/' must never reach the shell."""
        for escape in ("../escaped", "a/b", "/abs"):
            with self.assertRaises(argparse.ArgumentTypeError):
                module_cmd.valid_module_id(escape)

    def test_the_error_names_the_rule_and_where_it_comes_from(self):
        with self.assertRaises(argparse.ArgumentTypeError) as ctx:
            module_cmd.valid_module_id("9mod")
        self.assertIn("lint_module.sh", str(ctx.exception))

    def test_matches_the_pattern_lint_module_enforces(self):
        """Generator and linter disagreeing about this is a bug we already hit.

        lint_module.sh checks ^[a-zA-Z][a-zA-Z0-9_.-]*$; if that ever changes,
        this is the test that should fail.
        """
        self.assertEqual(module_cmd.MODULE_ID_RE.pattern, r"^[a-zA-Z][a-zA-Z0-9_.-]*$")


class TestArgumentHandling(unittest.TestCase):
    """The four shell failure modes, now handled by argparse."""

    def test_missing_option_value_is_rejected(self):
        # Was: raw "$2: unbound variable" under set -u.
        with self.assertRaises(SystemExit):
            parse(["module", "build", "mymod", "--framework"])

    def test_unknown_flag_is_rejected(self):
        # Was: silently ignored, so the run used defaults and said nothing.
        with self.assertRaises(SystemExit):
            parse(["module", "build", "mymod", "--frmework", "magisk"])

    def test_missing_subcommand_is_rejected(self):
        # Was: fell through and did nothing.
        with self.assertRaises(SystemExit):
            parse(["module"])

    def test_unknown_target_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["module", "scaffold", "mymod", "Name", "--target", "magsik"])

    def test_unknown_framework_is_rejected(self):
        with self.assertRaises(SystemExit):
            parse(["module", "build", "mymod", "--framework", "bogus"])

    def test_valid_invocations_parse(self):
        args = parse(["module", "scaffold", "mymod", "My Mod", "--target", "kernelsu"])
        self.assertEqual(args.module_id, "mymod")
        self.assertEqual(args.display_name, "My Mod")
        self.assertEqual(args.target, "kernelsu")


class TestDispatch(unittest.TestCase):
    """What actually reaches the shell."""

    def dispatch(self, argv):
        with mock.patch.object(module_cmd, "exec_script", return_value=0) as ex:
            module_cmd.dispatch(parse(argv))
        return ex.call_args

    def test_scaffold_passes_positional_order(self):
        name, args = self.dispatch(["module", "scaffold", "mymod", "My Mod"])[0]
        self.assertEqual(name, "new_module_scaffold.sh")
        self.assertEqual(args, ["mymod", "My Mod", "magisk"])

    def test_lint_passes_its_target(self):
        name, args = self.dispatch(["module", "lint", "/some/dir"])[0]
        self.assertEqual(name, "lint_module.sh")
        self.assertEqual(args, ["/some/dir"])

    def test_build_without_install_omits_the_flag(self):
        name, args = self.dispatch(["module", "build", "mymod"])[0]
        self.assertEqual(name, "build_magisk_module.sh")
        self.assertNotIn("--install", args)

    def test_build_with_install_and_serial(self):
        _, args = self.dispatch(
            ["module", "build", "mymod", "--install", "--serial", "ABC123"]
        )[0]
        self.assertIn("--install", args)
        self.assertIn("--serial", args)
        self.assertIn("ABC123", args)

    def test_a_display_name_with_spaces_stays_one_argument(self):
        """The list form is what keeps this from re-splitting in the shell."""
        _, args = self.dispatch(["module", "scaffold", "mymod", "My Great Mod"])[0]
        self.assertIn("My Great Mod", args)

    def test_dispatch_returns_the_script_exit_code(self):
        with mock.patch.object(module_cmd, "exec_script", return_value=3):
            self.assertEqual(module_cmd.dispatch(parse(["module", "lint", "x"])), 3)


class TestRunner(unittest.TestCase):
    def test_missing_script_exits_127_rather_than_raising(self):
        with mock.patch.object(
            runner, "find_script", side_effect=runner.ScriptNotFound("nope.sh missing")
        ):
            with mock.patch("builtins.print"):
                self.assertEqual(runner.exec_script("nope.sh", []), 127)

    def test_exit_code_is_passed_through_untouched(self):
        """Several scripts use non-zero to report a finding, not a crash."""
        completed = mock.Mock(returncode=1)
        with mock.patch.object(runner, "run_script", return_value=completed):
            self.assertEqual(runner.exec_script("whatever.sh", []), 1)

    def test_find_script_prefers_the_installed_location(self):
        with mock.patch.object(runner.Path, "is_file", return_value=True):
            self.assertEqual(
                runner.find_script("x.sh"), runner.INSTALLED_BIN / "x.sh"
            )


if __name__ == "__main__":
    unittest.main()
