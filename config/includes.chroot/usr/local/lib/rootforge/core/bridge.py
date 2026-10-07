"""Optional controller bridge: a typed, versioned JSON contract over stdin/stdout.

This is the RootForge side of the DroidCommand AI integration described in
docs/DROIDCOMMAND_INTEGRATION.md. It is deliberately NOT a daemon and NOT a
second assistant: `rootforge bridge serve` reads newline-delimited JSON
requests from stdin and writes one JSON response per request to stdout, then
exits at EOF. The intended transport is an OpenSSH session whose authorized
key is pinned to this command (see `authorized-key`), but the same bytes work
over a local pipe, which is how the contract is tested.

Security model (each point is enforced in code and covered by tests):

* The controller identity comes from the `--controller` argument, which the
  RootForge owner bakes into the authorized_keys line. It is never read from
  request JSON.
* Grants live in a file under the state directory and are re-read for EVERY
  request, so revoking a controller takes effect on its already-open session.
* Only registered operations run; arguments arrive as JSON and are never
  interpolated into a shell command.
* Phase RF-DCA-1 exposes passive operations only. Anything that could write to
  a device, run a build, open a shell, or request root is absent from the
  registry. `rootforge device info` is intentionally NOT exposed: its profiler
  runs `su -c` on the device, which is not passive.
* stdout carries protocol frames only; diagnostics go to stderr.

RootForge works without this module ever being used. Nothing here is imported
by the other command groups.
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import uuid
from pathlib import Path
from typing import Any, Callable, Dict, IO, List, Optional

from rootforge.core import __version__

PROTOCOL_MAJOR = 1
MAX_REQUEST_BYTES = 64 * 1024
MAX_TIMEOUT_MS = 60_000
DEFAULT_TIMEOUT_MS = 10_000

CONTROLLER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
REQUEST_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
NODE_ID_RE = re.compile(r"^rf-[0-9a-f]{32}$")
PUBKEY_TYPES = (
    "ssh-ed25519",
    "ecdsa-sha2-nistp256",
    "ecdsa-sha2-nistp384",
    "ecdsa-sha2-nistp521",
    "sk-ssh-ed25519@openssh.com",
    "ssh-rsa",
)
# Safe characters for a state directory embedded in an authorized_keys
# command="..." option. No quotes, spaces, backslashes or control characters.
STATE_DIR_RE = re.compile(r"^/[A-Za-z0-9._/+-]+$")

# Grants a controller may hold in this phase. Write-capable grants from the
# blueprint (build, terminal, device_write, ...) are deliberately not
# accepted yet: granting something the bridge cannot enforce would be false
# assurance.
KNOWN_GRANTS = ("inspect",)

ERR_UNAUTHORIZED = "unauthorized"
ERR_UNSUPPORTED = "unsupported_capability"
ERR_INVALID = "invalid_request"
ERR_PROTOCOL = "incompatible_protocol"
ERR_WRONG_NODE = "wrong_node"
ERR_TIMEOUT = "timeout"
ERR_INTERNAL = "internal"

_TOP_LEVEL_KEYS = {"protocol_major", "request_id", "target_node_id", "operation", "timeout_ms", "parameters"}


class BridgeError(Exception):
    def __init__(self, category: str, message: str):
        super().__init__(message)
        self.category = category
        self.message = message


# --------------------------------------------------------------------------
# State directory: node identity and controller grants
# --------------------------------------------------------------------------

def default_state_dir() -> Path:
    base = os.environ.get("XDG_STATE_HOME") or os.path.join(os.path.expanduser("~"), ".local", "state")
    return Path(base) / "rootforge" / "bridge"


def _write_private(path: Path, data: str) -> None:
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


def init_node(state_dir: Path) -> str:
    """Create the node identity if absent; return it. Idempotent."""
    path = state_dir / "node-id"
    if path.is_file():
        return load_node_id(state_dir)
    node = "rf-" + uuid.uuid4().hex
    _write_private(path, node + "\n")
    return node


def load_node_id(state_dir: Path) -> str:
    path = state_dir / "node-id"
    try:
        value = path.read_text().strip()
    except OSError as exc:
        raise BridgeError(ERR_INTERNAL, f"node identity unavailable ({path}); run `rootforge bridge init`: {exc}")
    if not NODE_ID_RE.match(value):
        raise BridgeError(ERR_INTERNAL, f"node identity file {path} is malformed")
    return value


def _load_grants(state_dir: Path) -> Dict[str, Any]:
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


def _save_grants(state_dir: Path, data: Dict[str, Any]) -> None:
    _write_private(state_dir / "controllers.json", json.dumps(data, indent=2, sort_keys=True) + "\n")


def check_grant(state_dir: Path, controller: str, grant: str) -> None:
    """Raise unauthorized unless `controller` currently holds `grant`.

    Fails closed: any problem reading grants denies the request.
    """
    try:
        data = _load_grants(state_dir)
    except BridgeError:
        raise BridgeError(ERR_UNAUTHORIZED, "controller is not authorized")
    entry = data["controllers"].get(controller)
    if not isinstance(entry, dict) or entry.get("revoked") is True:
        raise BridgeError(ERR_UNAUTHORIZED, "controller is not authorized")
    grants = entry.get("grants")
    if not isinstance(grants, list) or grant not in grants:
        raise BridgeError(ERR_UNAUTHORIZED, f"controller lacks the '{grant}' grant")


# --------------------------------------------------------------------------
# Operations (passive only in this phase)
# --------------------------------------------------------------------------

def _op_capabilities(ctx: "Context", params: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "node_id": ctx.node_id,
        "rootforge_version": __version__,
        "protocol_majors": [PROTOCOL_MAJOR],
        "runtime": {
            "os": platform.system(),
            "machine": platform.machine(),
            "python": platform.python_version(),
        },
        # shutil.which only inspects PATH; it runs nothing.
        "tools": {name: shutil.which(name) is not None for name in ("adb", "fastboot")},
        "operations": [
            {"name": name, "required_grant": spec.grant, "side_effects": spec.side_effects}
            for name, spec in sorted(OPERATIONS.items())
        ],
        "not_exposed": [
            "device profiling (runs su on the device)",
            "builds, workspaces, jobs, artifacts",
            "backup, restore, flash, unlock",
            "terminal and desktop sessions",
        ],
    }


def _op_devices_list(ctx: "Context", params: Dict[str, Any]) -> Dict[str, Any]:
    # Imported lazily so the bridge's contract tests need no device tooling.
    from rootforge.core import devices

    found = devices.adb_devices() + devices.fastboot_devices()  # no per-device queries
    return {"devices": [d.as_dict() for d in found]}


class OperationSpec:
    def __init__(self, handler: Callable, grant: str, side_effects: str, params: tuple = ()):
        self.handler = handler
        self.grant = grant
        self.side_effects = side_effects
        self.params = params  # allowed parameter names


OPERATIONS: Dict[str, OperationSpec] = {
    "rootforge.capabilities.get": OperationSpec(_op_capabilities, "inspect", "none"),
    # `adb devices` starts the local adb server if it is not running. That is
    # the only side effect; no device is contacted beyond enumeration, and no
    # root is requested.
    "rootforge.devices.list": OperationSpec(_op_devices_list, "inspect", "may start the local adb server"),
}


class Context:
    def __init__(self, state_dir: Path, controller: str, node_id: str):
        self.state_dir = state_dir
        self.controller = controller
        self.node_id = node_id


# --------------------------------------------------------------------------
# Request handling
# --------------------------------------------------------------------------

def _validate(request: Any) -> Dict[str, Any]:
    if not isinstance(request, dict):
        raise BridgeError(ERR_INVALID, "request must be a JSON object")
    unknown = set(request) - _TOP_LEVEL_KEYS
    if unknown:
        raise BridgeError(ERR_INVALID, "unknown field(s): " + ", ".join(sorted(unknown)))
    major = request.get("protocol_major")
    if isinstance(major, bool) or not isinstance(major, int):
        raise BridgeError(ERR_INVALID, "protocol_major must be an integer")
    if major != PROTOCOL_MAJOR:
        raise BridgeError(ERR_PROTOCOL, f"protocol_major {major} unsupported; this node speaks {PROTOCOL_MAJOR}")
    for key in ("request_id", "target_node_id", "operation"):
        if not isinstance(request.get(key), str):
            raise BridgeError(ERR_INVALID, f"{key} must be a string")
    if not REQUEST_ID_RE.match(request["request_id"]):
        raise BridgeError(ERR_INVALID, "request_id must be 1-128 chars of [A-Za-z0-9._:-]")
    timeout = request.get("timeout_ms", DEFAULT_TIMEOUT_MS)
    if isinstance(timeout, bool) or not isinstance(timeout, int) or not 1 <= timeout <= MAX_TIMEOUT_MS:
        raise BridgeError(ERR_INVALID, f"timeout_ms must be an integer in 1..{MAX_TIMEOUT_MS}")
    params = request.get("parameters", {})
    if not isinstance(params, dict):
        raise BridgeError(ERR_INVALID, "parameters must be an object")
    return {**request, "timeout_ms": timeout, "parameters": params}


def _run_with_timeout(fn: Callable[[], Any], timeout_ms: int) -> Any:
    box: Dict[str, Any] = {}

    def target() -> None:
        try:
            box["value"] = fn()
        except BaseException as exc:  # reported to the caller below
            box["error"] = exc

    thread = threading.Thread(target=target, daemon=True)
    thread.start()
    thread.join(timeout_ms / 1000)
    if thread.is_alive():
        raise BridgeError(ERR_TIMEOUT, f"operation exceeded {timeout_ms} ms")
    if "error" in box:
        raise box["error"]
    return box["value"]


def handle_line(ctx: Context, line: bytes) -> Dict[str, Any]:
    """Turn one request line into one response frame. Never raises."""
    request_id: Optional[str] = None
    try:
        if len(line) > MAX_REQUEST_BYTES:
            raise BridgeError(ERR_INVALID, f"request exceeds {MAX_REQUEST_BYTES} bytes")
        try:
            raw = json.loads(line.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise BridgeError(ERR_INVALID, "request is not valid UTF-8 JSON")
        if isinstance(raw, dict) and isinstance(raw.get("request_id"), str) and REQUEST_ID_RE.match(raw["request_id"]):
            request_id = raw["request_id"]
        req = _validate(raw)
        if req["target_node_id"] != ctx.node_id:
            raise BridgeError(ERR_WRONG_NODE, "request targets a different node")
        spec = OPERATIONS.get(req["operation"])
        # Authorization is checked before revealing whether an operation exists
        # to a controller that holds no grants at all.
        check_grant(ctx.state_dir, ctx.controller, spec.grant if spec else "inspect")
        if spec is None:
            raise BridgeError(ERR_UNSUPPORTED, f"unknown operation '{req['operation']}'")
        if req["parameters"] and not set(req["parameters"]) <= set(spec.params):
            raise BridgeError(ERR_INVALID, "unexpected parameter(s): " + ", ".join(sorted(set(req["parameters"]) - set(spec.params))))
        result = _run_with_timeout(lambda: spec.handler(ctx, req["parameters"]), req["timeout_ms"])
        return _frame(ctx, request_id, ok=True, result=result)
    except BridgeError as exc:
        return _frame(ctx, request_id, ok=False, error={"category": exc.category, "message": exc.message})
    except Exception as exc:  # noqa: BLE001 — a handler bug must not kill the session
        print(f"rootforge bridge: internal error: {exc!r}", file=sys.stderr)
        return _frame(ctx, request_id, ok=False, error={"category": ERR_INTERNAL, "message": "internal error"})


def _frame(ctx: Context, request_id: Optional[str], **body: Any) -> Dict[str, Any]:
    return {"protocol_major": PROTOCOL_MAJOR, "request_id": request_id, "node_id": ctx.node_id, **body}


def serve(ctx: Context, stdin: IO[bytes], stdout: IO[str]) -> int:
    while True:
        line = stdin.readline(MAX_REQUEST_BYTES + 2)
        if not line:
            return 0
        if not line.endswith(b"\n") and len(line) > MAX_REQUEST_BYTES:
            # Oversized frame: drain the remainder of the line, then reject.
            while line and not line.endswith(b"\n"):
                line = stdin.readline(MAX_REQUEST_BYTES + 2)
            frame = _frame(ctx, None, ok=False, error={"category": ERR_INVALID, "message": f"request exceeds {MAX_REQUEST_BYTES} bytes"})
        elif not line.strip():
            continue
        else:
            frame = handle_line(ctx, line)
        stdout.write(json.dumps(frame, separators=(",", ":")) + "\n")
        stdout.flush()


# --------------------------------------------------------------------------
# Owner-facing administration
# --------------------------------------------------------------------------

def controller_id(value: str) -> str:
    if not CONTROLLER_RE.match(value):
        raise argparse.ArgumentTypeError("controller id must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    return value


def state_dir_arg(value: str) -> Path:
    path = Path(value)
    if not STATE_DIR_RE.match(str(path)):
        raise argparse.ArgumentTypeError("state dir must be an absolute path of [A-Za-z0-9._/+-] characters")
    return path


def authorized_key_line(controller: str, state_dir: Path, pubkey_text: str, exe: Optional[str] = None) -> str:
    """Build the restricted authorized_keys line for a controller's public key.

    `restrict` turns off PTY allocation, port/agent/X11 forwarding and
    ~/.ssh/rc; `command=` forces this bridge no matter what the client asks
    to run.
    """
    parts = pubkey_text.strip().split()
    if "\n" in pubkey_text.strip() or len(parts) < 2 or parts[0] not in PUBKEY_TYPES:
        raise BridgeError(ERR_INVALID, "expected a single OpenSSH public key line (type, base64 key[, comment])")
    if not re.fullmatch(r"[A-Za-z0-9+/=]+", parts[1]):
        raise BridgeError(ERR_INVALID, "public key body is not base64")
    exe = exe or shutil.which("rootforge") or "/usr/local/bin/rootforge"
    if not STATE_DIR_RE.match(exe):
        raise BridgeError(ERR_INVALID, f"rootforge path {exe!r} contains characters unsafe for authorized_keys")
    command = f"{exe} bridge serve --controller {controller} --state-dir {state_dir}"
    return f'restrict,command="{command}" {parts[0]} {parts[1]} rootforge-controller-{controller}'


def _cmd_init(args: argparse.Namespace) -> int:
    print(init_node(args.state_dir))
    return 0


def _cmd_grant(args: argparse.Namespace) -> int:
    init_node(args.state_dir)
    data = _load_grants(args.state_dir)
    entry = data["controllers"].setdefault(args.controller, {"grants": []})
    entry["revoked"] = False
    entry["grants"] = sorted(set(entry.get("grants", [])) | set(args.grant))
    _save_grants(args.state_dir, data)
    print(f"controller {args.controller}: grants {', '.join(entry['grants'])}")
    return 0


def _cmd_revoke(args: argparse.Namespace) -> int:
    data = _load_grants(args.state_dir)
    if args.controller not in data["controllers"]:
        print(f"rootforge: error: unknown controller '{args.controller}'", file=sys.stderr)
        return 1
    data["controllers"][args.controller]["revoked"] = True
    _save_grants(args.state_dir, data)
    print(f"controller {args.controller}: revoked (takes effect on the next request, including open sessions)")
    return 0


def _cmd_list(args: argparse.Namespace) -> int:
    data = _load_grants(args.state_dir)
    if args.json:
        print(json.dumps(data, indent=2, sort_keys=True))
    elif not data["controllers"]:
        print("No controllers authorized.")
    else:
        for name, entry in sorted(data["controllers"].items()):
            status = "REVOKED" if entry.get("revoked") else "active"
            print(f"{name}  {status}  grants: {', '.join(entry.get('grants', [])) or '(none)'}")
    return 0


def _cmd_authorized_key(args: argparse.Namespace) -> int:
    try:
        text = Path(args.pubkey_file).read_text()
        print(authorized_key_line(args.controller, args.state_dir, text, args.exe))
    except OSError as exc:
        print(f"rootforge: error: {exc}", file=sys.stderr)
        return 1
    except BridgeError as exc:
        print(f"rootforge: error: {exc.message}", file=sys.stderr)
        return 1
    return 0


def _cmd_host_key(args: argparse.Namespace) -> int:
    """Print the SSH host public key and fingerprint for out-of-band pinning."""
    path = Path(args.file)
    try:
        key = path.read_text().strip()
    except OSError as exc:
        print(f"rootforge: error: {exc}", file=sys.stderr)
        return 1
    print(key)
    if shutil.which("ssh-keygen"):
        proc = subprocess.run(["ssh-keygen", "-lf", str(path)], capture_output=True, text=True, check=False)
        if proc.returncode == 0:
            print(proc.stdout.strip())
    return 0


def _cmd_serve(args: argparse.Namespace) -> int:
    try:
        node = load_node_id(args.state_dir)
    except BridgeError as exc:
        print(f"rootforge bridge: {exc.message}", file=sys.stderr)
        return 2
    ctx = Context(args.state_dir, args.controller, node)
    return serve(ctx, sys.stdin.buffer, sys.stdout)


def add_parser(sub: "argparse._SubParsersAction") -> None:
    bridge = sub.add_parser("bridge", help="Optional controller bridge for remote clients (e.g. DroidCommand AI).", allow_abbrev=False)
    bsub = bridge.add_subparsers(dest="bridge_command", required=True)

    def common(p: argparse.ArgumentParser) -> None:
        p.add_argument("--state-dir", type=state_dir_arg, default=default_state_dir())

    p = bsub.add_parser("init", help="Create this node's identity.", allow_abbrev=False)
    common(p)
    p.set_defaults(_bridge=_cmd_init)

    p = bsub.add_parser("grant", help="Authorize a controller (additive).", allow_abbrev=False)
    common(p)
    p.add_argument("--controller", type=controller_id, required=True)
    p.add_argument("--grant", action="append", choices=KNOWN_GRANTS, required=True)
    p.set_defaults(_bridge=_cmd_grant)

    p = bsub.add_parser("revoke", help="Revoke a controller immediately.", allow_abbrev=False)
    common(p)
    p.add_argument("--controller", type=controller_id, required=True)
    p.set_defaults(_bridge=_cmd_revoke)

    p = bsub.add_parser("list", help="List controllers and grants.", allow_abbrev=False)
    common(p)
    p.add_argument("--json", action="store_true")
    p.set_defaults(_bridge=_cmd_list)

    p = bsub.add_parser("authorized-key", help="Print a restricted authorized_keys line for a controller key.", allow_abbrev=False)
    common(p)
    p.add_argument("--controller", type=controller_id, required=True)
    p.add_argument("--pubkey-file", required=True)
    p.add_argument("--exe", default=None, help="absolute path of the rootforge executable (default: PATH lookup)")
    p.set_defaults(_bridge=_cmd_authorized_key)

    p = bsub.add_parser("host-key", help="Print an SSH host public key and fingerprint to confirm out of band.", allow_abbrev=False)
    p.add_argument("--file", default="/etc/ssh/ssh_host_ed25519_key.pub")
    p.set_defaults(_bridge=_cmd_host_key)

    p = bsub.add_parser("serve", help="Speak the bridge protocol on stdin/stdout (used by SSH).", allow_abbrev=False)
    common(p)
    p.add_argument("--controller", type=controller_id, required=True)
    p.set_defaults(_bridge=_cmd_serve)


def dispatch(args: argparse.Namespace) -> int:
    return args._bridge(args)
