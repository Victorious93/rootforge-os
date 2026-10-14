#!/usr/bin/env python3
"""Headless RootForge toolkit API (stdlib only).

Speaks the same JSON envelope as the Android/desktop BridgeExecutor
(protocol_major 1, operation names rootforge.*). Transport is host
adb/fastboot (and optional `edl`) via subprocess. Destructive operations
require a `confirmation` field that matches the typed phrase the GUI uses.

Does not flash anything on its own in tests: the runner is injectable.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple
from urllib.parse import urlparse

PROTOCOL_MAJOR = 1
MAX_BODY = 64 * 1024

# Destructive phrases match the desktop/Android GUI.
PHRASE = {
    "rootforge.fastboot.flash": lambda p: f"FLASH {p.get('partition', '')}",
    "rootforge.fastboot.slot.set": lambda p: f"SLOT {p.get('slot', '')}",
    "rootforge.fastboot.unlock": lambda _p: "UNLOCK",
    "rootforge.fastboot.lock": lambda _p: "LOCK",
}

CONTROL_OPS = {
    "rootforge.adb.shell",
    "rootforge.adb.reboot",
    "rootforge.fastboot.reboot",
    "rootforge.module.build",
}
DESTRUCTIVE_OPS = set(PHRASE)
READ_OPS = {
    "rootforge.capabilities.get",
    "rootforge.devices.list",
    "rootforge.device.info",
}


class Runner:
    """Runs host binaries. Tests replace this."""

    def run(self, argv: List[str], timeout: float = 60.0) -> Tuple[int, str, str]:
        try:
            proc = subprocess.run(
                argv,
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
            )
        except FileNotFoundError as exc:
            return 127, "", str(exc)
        except subprocess.TimeoutExpired:
            return 124, "", f"timed out after {timeout}s"
        return proc.returncode, proc.stdout or "", proc.stderr or ""


class Toolkit:
    def __init__(self, runner: Optional[Runner] = None, node_id: Optional[str] = None) -> None:
        self.runner = runner or Runner()
        self.node_id = node_id or os.environ.get("ROOTFORGE_NODE_ID") or f"rf-docker-{uuid.uuid4().hex[:16]}"
        self.adb = shutil.which("adb")
        self.fastboot = shutil.which("fastboot")
        self.edl = shutil.which("edl")

    def _bin(self, name: str, path: Optional[str]) -> str:
        if not path:
            raise RuntimeError(f"{name} not found on PATH")
        return path

    def devices(self) -> List[dict]:
        out: List[dict] = []
        if self.adb:
            code, stdout, stderr = self.runner.run([self.adb, "devices", "-l"])
            for line in (stdout + stderr).splitlines():
                line = line.strip()
                if not line or line.startswith("List of devices"):
                    continue
                parts = line.split()
                if len(parts) < 2:
                    continue
                serial, state = parts[0], parts[1]
                out.append(
                    {
                        "serial": serial,
                        "mode": "fastboot" if state == "bootloader" else "adb",
                        "state": state,
                        "usable": state in ("device", "recovery", "sideload"),
                        "note": " ".join(parts[2:]),
                    }
                )
        if self.fastboot:
            _code, stdout, stderr = self.runner.run([self.fastboot, "devices"])
            for line in (stdout + stderr).splitlines():
                parts = line.split()
                if len(parts) >= 2 and parts[1] in ("fastboot", "fastbootd"):
                    out.append(
                        {
                            "serial": parts[0],
                            "mode": "fastboot",
                            "state": parts[1],
                            "usable": True,
                            "note": "",
                        }
                    )
        return out

    def capabilities(self) -> dict:
        ops = []
        for name in sorted(READ_OPS | CONTROL_OPS | DESTRUCTIVE_OPS):
            tier = "read" if name in READ_OPS else "control" if name in CONTROL_OPS else "destructive"
            ops.append({"name": name, "tier": tier})
        return {
            "protocol_major": PROTOCOL_MAJOR,
            "node_kind": "docker-toolkit",
            "app": "rootforge-os-docker",
            "app_version": "0.1.0",
            "node_id": self.node_id,
            "usb_host": True,
            "adb": self.adb,
            "fastboot": self.fastboot,
            "edl": self.edl,
            "operations": ops,
        }

    def handle(self, request: dict, confirmation: Optional[str]) -> dict:
        if request.get("protocol_major") != PROTOCOL_MAJOR:
            return self._err(request, "protocol_mismatch", f"node speaks protocol {PROTOCOL_MAJOR}")
        if request.get("target_node_id") not in (None, "", self.node_id):
            return self._err(request, "wrong_node", f"this node is not {request.get('target_node_id')}")
        op = request.get("operation")
        params = request.get("parameters") or {}
        if not isinstance(params, dict):
            return self._err(request, "bad_parameters", "parameters must be an object")
        if op not in READ_OPS | CONTROL_OPS | DESTRUCTIVE_OPS:
            return self._err(request, "unknown_operation", f"no such operation '{op}'")
        if op in DESTRUCTIVE_OPS:
            needed = PHRASE[op](params)
            if confirmation != needed:
                return self._err(
                    request,
                    "denied_by_user",
                    f"destructive op requires confirmation exactly {needed!r}",
                )
        try:
            result = self._run_op(op, params)
        except Exception as exc:  # noqa: BLE001 — surface as a bridge error
            return self._err(request, "operation_failed", str(exc))
        return {
            "protocol_major": PROTOCOL_MAJOR,
            "request_id": request.get("request_id"),
            "node_id": self.node_id,
            "ok": True,
            "result": result,
            "error": None,
        }

    def _run_op(self, op: str, params: dict) -> dict:
        if op == "rootforge.capabilities.get":
            return self.capabilities()
        if op == "rootforge.devices.list":
            return {"devices": self.devices()}
        if op == "rootforge.device.info":
            devices = self.devices()
            serial = params.get("serial") or (devices[0]["serial"] if devices else None)
            if not serial:
                raise RuntimeError("no target")
            info = {}
            if self.adb:
                _c, stdout, _e = self.runner.run([self.adb, "-s", serial, "shell", "getprop", "ro.product.model"])
                info["model"] = stdout.strip()
            return {"info": info, "serial": serial}
        if op == "rootforge.adb.shell":
            cmd = params.get("command")
            if not isinstance(cmd, str) or not cmd or "\x00" in cmd:
                raise RuntimeError("command must be a non-empty string")
            argv = [self._bin("adb", self.adb), "shell", cmd]
            serial = params.get("serial")
            if serial:
                argv = [argv[0], "-s", serial, "shell", cmd]
            code, stdout, stderr = self.runner.run(argv, timeout=120)
            output = (stdout + stderr)[:64 * 1024]
            return {"exit_code": code, "output": output, "truncated": False}
        if op == "rootforge.adb.reboot":
            target = params.get("target") or ""
            argv = [self._bin("adb", self.adb), "reboot"] + ([target] if target else [])
            self.runner.run(argv, timeout=30)
            return {"requested": target or "system"}
        if op == "rootforge.fastboot.reboot":
            target = params.get("target") or "system"
            mapping = {
                "system": ["reboot"],
                "bootloader": ["reboot-bootloader"],
                "recovery": ["reboot-recovery"],
                "fastbootd": ["reboot-fastboot"],
            }
            args = mapping.get(target)
            if args is None:
                raise RuntimeError("unknown reboot target")
            self.runner.run([self._bin("fastboot", self.fastboot)] + args, timeout=30)
            return {"requested": target}
        if op == "rootforge.module.build":
            # The Docker image has the CLI; the zip builder lives in Kotlin too.
            # Here we refuse to invent a second implementation and point at `rootforge module`.
            raise RuntimeError(
                "module.build in the Docker API is not reimplemented; "
                "run `rootforge module scaffold` / the desktop app library builder"
            )
        if op == "rootforge.fastboot.flash":
            part = params.get("partition")
            image = params.get("image")
            if not part or not image:
                raise RuntimeError("partition and image are required")
            if not Path(image).is_file():
                raise RuntimeError("image is not a readable file (pass a path inside the container)")
            code, stdout, stderr = self.runner.run(
                [self._bin("fastboot", self.fastboot), "flash", str(part), str(image)],
                timeout=600,
            )
            if code != 0:
                raise RuntimeError((stdout + stderr).strip() or "fastboot flash failed")
            return {"flashed": part, "image": Path(image).name}
        if op == "rootforge.fastboot.slot.set":
            slot = params.get("slot")
            if slot not in ("a", "b"):
                raise RuntimeError("slot must be a or b")
            code, stdout, stderr = self.runner.run(
                [self._bin("fastboot", self.fastboot), "set_active", slot], timeout=30
            )
            if code != 0:
                raise RuntimeError((stdout + stderr).strip() or "set_active failed")
            return {"active_slot": slot}
        if op == "rootforge.fastboot.unlock":
            code, stdout, stderr = self.runner.run(
                [self._bin("fastboot", self.fastboot), "flashing", "unlock"], timeout=60
            )
            if code != 0:
                raise RuntimeError((stdout + stderr).strip() or "unlock failed")
            return {"requested": "unlock"}
        if op == "rootforge.fastboot.lock":
            code, stdout, stderr = self.runner.run(
                [self._bin("fastboot", self.fastboot), "flashing", "lock"], timeout=60
            )
            if code != 0:
                raise RuntimeError((stdout + stderr).strip() or "lock failed")
            return {"requested": "lock"}
        raise RuntimeError(op)

    def _err(self, request: dict, category: str, message: str) -> dict:
        return {
            "protocol_major": PROTOCOL_MAJOR,
            "request_id": request.get("request_id"),
            "node_id": self.node_id,
            "ok": False,
            "result": None,
            "error": {"category": category, "message": message},
        }


def _web_html() -> bytes:
    env = os.environ.get("ROOTFORGE_WEB")
    candidates = []
    if env:
        candidates.append(Path(env))
    here = Path(__file__).resolve().parent.parent / "web" / "index.html"
    candidates.append(here)
    for path in candidates:
        if path.is_file():
            return path.read_bytes()
    return b"<!doctype html><title>RootForge OS</title><p>API up. POST /v1/bridge</p>"


def make_handler(toolkit: Toolkit, token: str) -> Callable:
    web = _web_html()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt: str, *args) -> None:  # noqa: A003
            sys.stderr.write("api: " + (fmt % args) + "\n")

        def _send(self, code: int, body: bytes, content_type: str) -> None:
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _auth_ok(self) -> bool:
            header = self.headers.get("Authorization", "")
            got = header[7:].strip() if header.lower().startswith("bearer ") else ""
            return bool(token) and got == token

        def do_GET(self) -> None:  # noqa: N802
            path = urlparse(self.path).path
            if path == "/health":
                body = json.dumps({"ok": True, "protocol_major": PROTOCOL_MAJOR, "node_id": toolkit.node_id}).encode()
                self._send(200, body, "application/json")
                return
            if path in ("/", "/ui"):
                self._send(200, web, "text/html; charset=utf-8")
                return
            self._send(404, b'{"ok":false,"error":"not found"}', "application/json")

        def do_POST(self) -> None:  # noqa: N802
            path = urlparse(self.path).path
            if path != "/v1/bridge":
                self._send(404, b'{"ok":false,"error":"not found"}', "application/json")
                return
            if not self._auth_ok():
                self._send(401, b'{"ok":false,"error":"bearer token required"}', "application/json")
                return
            length = int(self.headers.get("Content-Length") or "0")
            if length > MAX_BODY:
                self._send(413, b'{"ok":false,"error":"too large"}', "application/json")
                return
            raw = self.rfile.read(length)
            try:
                request = json.loads(raw.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                self._send(400, b'{"ok":false,"error":"not JSON"}', "application/json")
                return
            if not isinstance(request, dict):
                self._send(400, b'{"ok":false,"error":"not an object"}', "application/json")
                return
            confirmation = request.get("confirmation")
            response = toolkit.handle(request, confirmation if isinstance(confirmation, str) else None)
            body = json.dumps(response).encode()
            self._send(200, body, "application/json")

    return Handler


def serve(host: str, port: int, toolkit: Toolkit, token: str) -> ThreadingHTTPServer:
    httpd = ThreadingHTTPServer((host, port), make_handler(toolkit, token))
    return httpd


def main(argv: Optional[List[str]] = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    host = os.environ.get("ROOTFORGE_API_BIND", "0.0.0.0")
    port = int(os.environ.get("ROOTFORGE_API_PORT", "8787"))
    if "--port" in argv:
        port = int(argv[argv.index("--port") + 1])
    token = os.environ.get("ROOTFORGE_API_TOKEN", "").strip()
    if not token:
        token = uuid.uuid4().hex
        sys.stderr.write(
            "ROOTFORGE_API_TOKEN unset; generated a one-shot token for this process "
            f"(not printed). Set the env var.\n"
        )
        # Print a hint without the token so logs are safe; write token to a 0600 file.
        token_file = Path(os.environ.get("ROOTFORGE_HOME", "/tmp")) / "api.token"
        try:
            token_file.parent.mkdir(parents=True, exist_ok=True)
            token_file.write_text(token + "\n")
            token_file.chmod(0o600)
            sys.stderr.write(f"Token written to {token_file}\n")
        except OSError as exc:
            sys.stderr.write(f"Could not write token file: {exc}\n")
            return 1
    toolkit = Toolkit()
    httpd = serve(host, port, toolkit, token)
    sys.stderr.write(f"RootForge OS toolkit API on {host}:{port}  node_id={toolkit.node_id}\n")
    sys.stderr.write(f"adb={toolkit.adb} fastboot={toolkit.fastboot} edl={toolkit.edl}\n")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
