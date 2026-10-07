"""Shared pieces of the controller bridge: errors, private writes, grants, context.

Split out of bridge.py so operation modules (bridge_jobs.py) can use them
without importing the server module (which imports them).
"""
from __future__ import annotations

import json
import os
import tempfile
from pathlib import Path
from typing import Any, Dict

ERR_UNAUTHORIZED = "unauthorized"
ERR_UNSUPPORTED = "unsupported_capability"
ERR_INVALID = "invalid_request"
ERR_PROTOCOL = "incompatible_protocol"
ERR_WRONG_NODE = "wrong_node"
ERR_TIMEOUT = "timeout"
ERR_INTERNAL = "internal"


ERR_NOT_FOUND = "not_found"
ERR_BUSY = "busy"
ERR_CONFLICT = "conflict"
ERR_INTEGRITY = "integrity_failure"
ERR_QUOTA = "quota_exceeded"


class BridgeError(Exception):
    def __init__(self, category: str, message: str):
        super().__init__(message)
        self.category = category
        self.message = message



def write_private(path: Path, data: str) -> None:
    """Atomically write `data` to `path`, mode 0600 from creation."""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=path.name + ".")
    try:
        with os.fdopen(fd, "w") as handle:  # mkstemp creates the file 0600
            handle.write(data)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise



def load_grants(state_dir: Path) -> Dict[str, Any]:
    path = state_dir / "controllers.json"
    if not path.exists():
        return {"version": 1, "controllers": {}}
    try:
        data = json.loads(path.read_text())
    except (OSError, ValueError) as exc:
        raise BridgeError(ERR_INTERNAL, f"grants file unreadable: {exc}")
    if not isinstance(data, dict) or not isinstance(data.get("controllers"), dict):
        raise BridgeError(ERR_INTERNAL, "grants file malformed")
    return data


def save_grants(state_dir: Path, data: Dict[str, Any]) -> None:
    write_private(state_dir / "controllers.json", json.dumps(data, indent=2, sort_keys=True) + "\n")


def check_grant(state_dir: Path, controller: str, grant: str) -> None:
    """Raise unauthorized unless `controller` currently holds `grant`.

    Fails closed: any problem reading grants denies the request.
    """
    try:
        data = load_grants(state_dir)
    except BridgeError:
        raise BridgeError(ERR_UNAUTHORIZED, "controller is not authorized")
    entry = data["controllers"].get(controller)
    if not isinstance(entry, dict) or entry.get("revoked") is True:
        raise BridgeError(ERR_UNAUTHORIZED, "controller is not authorized")
    grants = entry.get("grants")
    if not isinstance(grants, list) or grant not in grants:
        raise BridgeError(ERR_UNAUTHORIZED, f"controller lacks the '{grant}' grant")



class Context:
    def __init__(self, state_dir: Path, controller: str, node_id: str):
        self.state_dir = state_dir
        self.controller = controller
        self.node_id = node_id


