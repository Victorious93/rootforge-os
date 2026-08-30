"""`rootforge module` — scaffold, lint and build Magisk/KernelSU/Xposed modules.

P2 item 10 of docs/IMPLEMENTATION_PLAN.md. Wraps new_module_scaffold.sh,
lint_module.sh and build_magisk_module.sh rather than reimplementing them:
their behavior is proven and tested. What moves into Python is the argument
handling — see runner.py for why that is the part worth moving.

The module id rule is defined once, here, and is the rule lint_module.sh
enforces. Having the generator and the linter disagree about it is a real bug
this repository already hit: the scaffold produced ids the linter rejected,
and you only found out after building one.
"""
from __future__ import annotations

import argparse
import re
from typing import List

from rootforge.core.runner import exec_script

# Magisk requires a restricted id format. lint_module.sh checks this exact
# pattern; keep the two in step.
MODULE_ID_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_.-]*$")

TARGETS = ("magisk", "kernelsu", "xposed")
FRAMEWORKS = ("magisk", "kernelsu")


def valid_module_id(value: str) -> str:
    """argparse type for a module id.

    Rejecting here rather than in the shell means the error names the
    argument and the rule, and it happens before anything is created.
    """
    if not MODULE_ID_RE.match(value):
        raise argparse.ArgumentTypeError(
            f"'{value}' is not a valid module id — must start with a letter and "
            f"contain only [a-zA-Z0-9_.-]. Magisk requires a restricted id format, "
            f"and lint_module.sh enforces the same rule."
        )
    return value


def add_parser(subparsers) -> None:
    module = subparsers.add_parser(
        "module", help="Scaffold, lint and build Magisk/KernelSU/Xposed modules."
    )
    # required=True so `rootforge module` with no verb is an error naming the
    # verbs, rather than silently doing nothing.
    actions = module.add_subparsers(dest="module_command", required=True)

    scaffold = actions.add_parser("scaffold", help="Generate a new module skeleton.")
    scaffold.add_argument("module_id", type=valid_module_id)
    scaffold.add_argument("display_name", help="Human-readable name for module.prop")
    scaffold.add_argument(
        "--target", choices=TARGETS, default="magisk",
        help="Module type to scaffold (default: magisk)",
    )

    lint = actions.add_parser("lint", help="Check a module directory or zip.")
    lint.add_argument("target", help="Module source directory, or a built .zip")

    build = actions.add_parser("build", help="Package a module, and optionally install it.")
    build.add_argument("module_id", type=valid_module_id)
    build.add_argument(
        "--install", action="store_true", help="Push and install on a connected device"
    )
    build.add_argument(
        "--framework", choices=FRAMEWORKS, default="magisk",
        help="Root framework whose CLI installs the module (default: magisk)",
    )
    build.add_argument(
        "--serial",
        help="Target this device serial. Needed once more than one device is "
             "attached, where adb otherwise refuses outright.",
    )


def dispatch(args: argparse.Namespace) -> int:
    if args.module_command == "scaffold":
        return exec_script(
            "new_module_scaffold.sh",
            [args.module_id, args.display_name, args.target],
        )

    if args.module_command == "lint":
        return exec_script("lint_module.sh", [args.target])

    if args.module_command == "build":
        script_args: List[str] = [args.module_id]
        if args.install:
            script_args.append("--install")
        script_args += ["--framework", args.framework]
        if args.serial:
            script_args += ["--serial", args.serial]
        return exec_script("build_magisk_module.sh", script_args)

    # Unreachable: argparse rejects anything else. Kept so that adding a verb
    # above without adding a branch here is loud rather than silent.
    raise AssertionError(f"no dispatch branch for module command {args.module_command!r}")
