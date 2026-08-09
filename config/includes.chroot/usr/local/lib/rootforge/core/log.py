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

import json
import os
import re
import secrets
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
        self.execution_id = execution_id or secrets.token_hex(4)
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
        with self.path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, sort_keys=True) + "\n")
        if self.echo:
            stream = sys.stderr if level in ("warn", "error") else sys.stdout
            print(f"[{self.command}] {event}", file=stream)

    def info(self, event: str, **fields: Any) -> None:
        self._write("info", event, **fields)

    def warn(self, event: str, **fields: Any) -> None:
        self._write("warn", event, **fields)

    def error(self, event: str, **fields: Any) -> None:
        self._write("error", event, **fields)
