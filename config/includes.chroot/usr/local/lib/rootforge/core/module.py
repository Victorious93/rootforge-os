"""Validated CLI wrapper for module scaffolding, linting, and packaging."""
from __future__ import annotations

import argparse
import re
from typing import List

from rootforge.core.runner import exec_script

VALID_TARGETS = ("magisk", "kernelsu", "apatch", "zygisk", "xposed")
MODULE_ID_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_.-]*$")


def valid_module_id(value: str) -> str:
    if not MODULE_ID_RE.fullmatch(value):
        raise argparse.ArgumentTypeError(
            f"'{value}' is not a valid module id; lint_module.sh enforces "
            "^[a-zA-Z][a-zA-Z0-9_.-]*$ (starts with a letter; letters, digits, dot, underscore, hyphen only)."
        )
    return value


def add_parser(subparsers) -> None:
    parser = subparsers.add_parser("module", help="Scaffold, lint, and build Android modules.", allow_abbrev=False)
    actions = parser.add_subparsers(dest="module_command", required=True)
    scaffold = actions.add_parser("scaffold", help="Create a module skeleton.", allow_abbrev=False)
    scaffold.add_argument("module_id", type=valid_module_id)
    scaffold.add_argument("display_name")
    scaffold.add_argument("--target", choices=VALID_TARGETS, default="magisk")
    lint = actions.add_parser("lint", help="Lint a module directory or ZIP.", allow_abbrev=False)
    lint.add_argument("path")
    lint.add_argument("--json", action="store_true")
    build = actions.add_parser("build", help="Package a module and optionally install it.", allow_abbrev=False)
    build.add_argument("module_id", type=valid_module_id)
    build.add_argument("--install", action="store_true")
    build.add_argument("--framework", choices=("magisk", "kernelsu"), default="magisk")
    build.add_argument("--serial", default=None)


def dispatch(args: argparse.Namespace) -> int:
    if args.module_command == "scaffold":
        return exec_script("new_module_scaffold.sh", [args.module_id, args.display_name, args.target])
    if args.module_command == "lint":
        argv = (["--json"] if args.json else []) + [args.path]
        return exec_script("lint_module.sh", argv)
    if args.module_command == "build":
        argv: List[str] = [args.module_id]
        if args.install:
            argv.append("--install")
        argv += ["--framework", args.framework]
        if args.serial:
            argv += ["--serial", args.serial]
        return exec_script("build_magisk_module.sh", argv)
    raise AssertionError(f"unknown module command: {args.module_command!r}")
