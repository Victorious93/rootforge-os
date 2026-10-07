"""rootforge.core.audit — one audit trail per state-changing command.

`flash`, `backup`, `module` and `avd` used to run with no record on the CLI
side: the wrapped script kept its own text log, but nothing tied the
invocation to it, and the Python-native paths (`backup verify`,
`backup import-legacy`) left no record at all. `audited()` wraps a whole
command and writes two events to `rootforge-<command>-<execution id>.jsonl`:

  command started    the command, its (redacted) argument list, who ran it
  command finished   exit status, duration, the scripts that were run with
                     their exit statuses, and the script logs for this run

The execution ID is the one the CLI gave the wrapped scripts, so the JSON
log, the scripts' own logs and any `rootforge` call a script makes share it.

Auditing never changes the outcome: the command's exit status is returned
untouched, an exception is recorded and re-raised, and if the log cannot be
opened (read-only home, full disk) the command still runs and a warning is
printed — a destructive operation is not blocked by a logging problem, but the
operator is told it is unrecorded.
"""
from __future__ import annotations

import os
import re
import sys
import time
from typing import Callable, Optional, Sequence

from rootforge.core import runner
from rootforge.core.log import Logger, script_logs_for

# Commands that change a device or a workspace and previously left no CLI-side
# record. doctor and ota already log themselves, and so do boot's
# inspect/unpack/repack/cpio/verify; read-only commands need no record.
AUDITED_COMMANDS = ("flash", "backup", "module", "avd")
# Only the two `boot` subcommands that run a script: `patch` (KernelSU patch
# flow) and `flash-last` (writes the boot partition). Auditing the whole group
# would double-log the ones that already write their own events.
AUDITED_SUBCOMMANDS = (("boot", "patch"), ("boot", "flash-last"))


def is_audited(args) -> bool:
    sub = getattr(args, f"{args.command}_command", "") or ""
    return args.command in AUDITED_COMMANDS or (args.command, sub) in AUDITED_SUBCOMMANDS


def command_label(args) -> str:
    """'flash boot', 'backup verify', ... from the parsed arguments."""
    group = args.command
    sub = getattr(args, f"{group}_command", "") or ""
    return f"{group} {sub}".strip()


def _logger_name(label: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", label.lower()).strip("-") or "command"


def audited(label: str, argv: Optional[Sequence[str]], run: Callable[[], int]) -> int:
    """Run `run()` and record it. Returns its exit status unchanged."""
    try:
        logger: Optional[Logger] = Logger(_logger_name(label), echo=False)
    except OSError as exc:
        logger = None
        print(
            f"rootforge: warning: audit log unavailable ({exc}); this run will not be recorded",
            file=sys.stderr,
        )

    started_wall = time.time()
    started = time.monotonic()
    runner.reset_executed_scripts()

    if logger:
        logger.info(
            "command started",
            command=label,
            argv=list(argv) if argv is not None else sys.argv[1:],
            euid=os.geteuid(),
            sudo_user=os.environ.get("SUDO_USER", ""),
            cwd=os.getcwd(),
        )

    try:
        returncode = run()
    except BaseException as exc:  # recorded, then re-raised: SystemExit and Ctrl-C included
        if logger:
            logger.error(
                "command crashed",
                command=label,
                error_type=type(exc).__name__,
                duration_seconds=round(time.monotonic() - started, 3),
                scripts=runner.executed_scripts(),
                script_logs=script_logs_for(logger.execution_id, since=started_wall - 2),
            )
        raise

    if logger:
        fields = dict(
            command=label,
            returncode=returncode,
            duration_seconds=round(time.monotonic() - started, 3),
            scripts=runner.executed_scripts(),
            script_logs=script_logs_for(logger.execution_id, since=started_wall - 2),
            log_path=str(logger.path),
        )
        # A non-zero status is not always a failure (device check exits 3 for
        # "blocked", backup exits 4 for "partial"), so it is a warning, not an error.
        (logger.info if returncode == 0 else logger.warn)("command finished", **fields)
    return returncode
