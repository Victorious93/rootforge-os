"""rootforge.core.log — structured JSON-lines logging.

Every `rootforge` invocation gets one execution ID (an 8-character hex
tag) and a `Logger` that appends newline-delimited JSON events to
`$ROOTFORGE_HOME/logs/rootforge-<command>-<execution_id>.jsonl` — one file
per run, alongside the existing shell scripts' own `$ROOTFORGE_HOME/logs/`
files.

Field names that look like secrets (key/token/secret/password/...) are
redacted before serialization, and a handful of known secret-shaped
patterns (sk-ant-*, ghp_*, AIza*, `Bearer <token>`) are redacted out of
free-text messages too — belt-and-suspenders, since a message might embed
a credential nobody thought to name as one.
"""
from __future__ import annotations

import contextlib
import json
import os
import re
import secrets
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict

_REDACTED = "***REDACTED***"

_SECRET_KEY_PATTERN = re.compile(
    r"(key|token|secret|password|passwd|credential)", re.IGNORECASE
)

_SECRET_VALUE_PATTERNS = [
    re.compile(r"sk-ant-[A-Za-z0-9_-]{10,}"),
    re.compile(r"sk-[A-Za-z0-9]{20,}"),
    re.compile(r"ghp_[A-Za-z0-9]{20,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"AIza[A-Za-z0-9_-]{30,}"),
    re.compile(r"(?i)bearer\s+[A-Za-z0-9._-]{10,}"),
]


def _redact_text(value: str) -> str:
    for pattern in _SECRET_VALUE_PATTERNS:
        value = pattern.sub(_REDACTED, value)
    return value


def _redact(obj: Any) -> Any:
    if isinstance(obj, dict):
        return {
            key: _REDACTED if _SECRET_KEY_PATTERN.search(str(key)) else _redact(value)
            for key, value in obj.items()
        }
    if isinstance(obj, list):
        return [_redact(item) for item in obj]
    if isinstance(obj, str):
        return _redact_text(obj)
    return obj


# One invocation of `rootforge` is one execution. The CLI sets this variable for
# its own process, so every Logger it opens shares the ID, and every wrapped
# script inherits it (scripts/sh/common.sh: rf_log_init) and stamps it into its
# own log. A script run directly, outside the CLI, generates its own.
EXECUTION_ID_ENV = "ROOTFORGE_EXECUTION_ID"
_EXECUTION_ID_RE = re.compile(r"^[A-Za-z0-9]{4,32}$")


def new_execution_id() -> str:
    return secrets.token_hex(4)


def current_execution_id() -> str:
    """The ID inherited from the environment, or a fresh one.

    An inherited value is used only if it is a short alphanumeric token: it ends
    up in file names and log lines, so anything else (a path, a newline, an
    escape sequence) is ignored rather than trusted.
    """
    inherited = os.environ.get(EXECUTION_ID_ENV, "")
    return inherited if _EXECUTION_ID_RE.match(inherited) else new_execution_id()


@contextlib.contextmanager
def execution_scope():
    """Give this process (and its children) one execution ID, then restore.

    Reuses a valid inherited ID, so a script that calls `rootforge` keeps its own.
    """
    previous = os.environ.get(EXECUTION_ID_ENV)
    os.environ[EXECUTION_ID_ENV] = current_execution_id()
    try:
        yield os.environ[EXECUTION_ID_ENV]
    finally:
        if previous is None:
            os.environ.pop(EXECUTION_ID_ENV, None)
        else:
            os.environ[EXECUTION_ID_ENV] = previous


def script_logs_for(execution_id: str, since: float = 0.0) -> list:
    """Paths of script logs that announce this execution ID.

    Scripts stamp `# rootforge execution <id>: ...` as the first line of every
    log they create (sh/common.sh: rf_log_init). Only regular files modified
    at or after `since` are opened, and only their first 200 bytes are read,
    so this stays cheap in a log directory that has grown over months.
    """
    marker = f"# rootforge execution {execution_id}:"
    found = []
    try:
        entries = sorted((_rootforge_home() / "logs").iterdir())
    except OSError:
        return found
    for entry in entries:
        try:
            if not entry.is_file() or entry.is_symlink() or entry.stat().st_mtime < since:
                continue
            with entry.open("rb") as fh:
                head = fh.read(200).decode("utf-8", "replace")
        except OSError:
            continue
        if head.startswith(marker):
            found.append(str(entry))
    return found


def _hand_to_invoking_user(path: Path) -> None:
    """Under `sudo`, give a log the invoking user can still read.

    A 0600 file created by root inside the user's home would otherwise be
    unreadable by the person whose log it is. Best effort: a failure to chown
    must never stop the run.
    """
    sudo_user = os.environ.get("SUDO_USER", "")
    if os.geteuid() != 0 or not sudo_user or sudo_user == "root":
        return
    try:
        shutil.chown(path, user=sudo_user)
    except (OSError, LookupError):
        pass


def _rootforge_home() -> Path:
    return Path(os.environ.get("ROOTFORGE_HOME", str(Path.home() / "rootforge")))


class Logger:
    """One Logger per CLI invocation.

    Always writes redacted JSON-lines to disk. When `echo` is true (the
    default), also prints a plain `[command] event` line to stdout/stderr
    — set `echo=False` when the caller already does its own human-readable
    printing, so output isn't doubled.
    """

    def __init__(self, command: str, execution_id: str = "", echo: bool = True):
        self.command = command
        self.execution_id = execution_id or current_execution_id()
        self.echo = echo
        log_dir = _rootforge_home() / "logs"
        log_dir.mkdir(parents=True, exist_ok=True)
        self.path = log_dir / f"rootforge-{self.command}-{self.execution_id}.jsonl"

    def _write(self, level: str, event: str, **fields: Any) -> None:
        record: Dict[str, Any] = {
            "ts": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "execution_id": self.execution_id,
            "command": self.command,
            "level": level,
            "event": event,
        }
        record.update(fields)
        record = _redact(record)
        # 0600 from creation: events can carry device serials and paths, and
        # a chmod after the fact would leave a window at the default umask.
        # The mode only applies when the file is created; an existing log
        # keeps whatever mode it already has.
        existed = self.path.exists()
        fd = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, sort_keys=True) + "\n")
        if not existed:
            _hand_to_invoking_user(self.path)
        if self.echo:
            stream = sys.stderr if level in ("warn", "error") else sys.stdout
            print(f"[{self.command}] {record['event']}", file=stream)

    def info(self, event: str, **fields: Any) -> None:
        self._write("info", event, **fields)

    def warn(self, event: str, **fields: Any) -> None:
        self._write("warn", event, **fields)

    def error(self, event: str, **fields: Any) -> None:
        self._write("error", event, **fields)
