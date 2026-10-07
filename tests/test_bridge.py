"""Tests for `rootforge bridge` — the optional controller bridge.

These pin the security properties the design depends on: identity comes from
the server side, grants are re-read per request, only passive operations are
registered, and a passive probe never reaches for root.
"""
import io
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

LIB = Path(__file__).resolve().parents[1] / "config/includes.chroot/usr/local/lib"
sys.path.insert(0, str(LIB))

from rootforge.core import bridge, devices  # noqa: E402
from rootforge.core.cli import build_parser, main  # noqa: E402


def req(node, op="rootforge.capabilities.get", **extra):
    body = {"protocol_major": 1, "request_id": "r1", "target_node_id": node, "operation": op, "parameters": {}}
    body.update(extra)
    return body


class BridgeCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.state = Path(self._tmp.name) / "state"
        self.node = bridge.init_node(self.state)

    def grant(self, controller="ctl", grants=("inspect",)):
        args = ["bridge", "grant", "--state-dir", str(self.state), "--controller", controller]
        for g in grants:
            args += ["--grant", g]
        with mock.patch("sys.stdout", new=io.StringIO()):
            self.assertEqual(main(args), 0)

    def ctx(self, controller="ctl"):
        return bridge.Context(self.state, controller, self.node)

    def call(self, body, controller="ctl"):
        return bridge.handle_line(self.ctx(controller), json.dumps(body).encode())


class TestIdentityAndState(BridgeCase):
    def test_node_id_is_stable_and_well_formed(self):
        self.assertRegex(self.node, r"^rf-[0-9a-f]{32}$")
        self.assertEqual(bridge.init_node(self.state), self.node)

    def test_state_files_are_private(self):
        self.grant()
        for name in ("node-id", "controllers.json"):
            mode = stat.S_IMODE(os.stat(self.state / name).st_mode)
            self.assertEqual(mode, 0o600, name)

    def test_serve_refuses_without_node_identity(self):
        empty = Path(self._tmp.name) / "none"
        rc = main(["bridge", "serve", "--state-dir", str(empty), "--controller", "ctl"])
        self.assertEqual(rc, 2)


class TestAuthorization(BridgeCase):
    def test_ungranted_controller_is_unauthorized(self):
        resp = self.call(req(self.node))
        self.assertFalse(resp["ok"])
        self.assertEqual(resp["error"]["category"], "unauthorized")

    def test_unknown_operation_does_not_leak_to_ungranted_controller(self):
        resp = self.call(req(self.node, op="rootforge.nope"))
        self.assertEqual(resp["error"]["category"], "unauthorized")

    def test_granted_controller_succeeds(self):
        self.grant()
        resp = self.call(req(self.node))
        self.assertTrue(resp["ok"], resp)
        self.assertEqual(resp["node_id"], self.node)
        self.assertEqual(resp["request_id"], "r1")

    def test_controller_identity_cannot_come_from_the_request(self):
        self.grant("legit")
        resp = self.call(req(self.node, controller="legit"), controller="attacker")
        self.assertEqual(resp["error"]["category"], "invalid_request")
        resp = self.call(req(self.node), controller="attacker")
        self.assertEqual(resp["error"]["category"], "unauthorized")

    def test_revocation_applies_to_an_open_session(self):
        self.grant()
        lines = (json.dumps(req(self.node, request_id="a")) + "\n").encode()
        out = io.StringIO()
        ctx = self.ctx()
        bridge.serve(ctx, io.BytesIO(lines), out)
        self.assertTrue(json.loads(out.getvalue())["ok"])
        # Same Context (same "session"), grants revoked in between.
        self.assertEqual(main(["bridge", "revoke", "--state-dir", str(self.state), "--controller", "ctl"]), 0)
        out2 = io.StringIO()
        bridge.serve(ctx, io.BytesIO(lines), out2)
        self.assertEqual(json.loads(out2.getvalue())["error"]["category"], "unauthorized")

    def test_corrupt_grants_file_fails_closed(self):
        self.grant()
        (self.state / "controllers.json").write_text("{not json")
        self.assertEqual(self.call(req(self.node))["error"]["category"], "unauthorized")

    def test_regrant_after_revoke_restores_access(self):
        self.grant()
        main(["bridge", "revoke", "--state-dir", str(self.state), "--controller", "ctl"])
        self.grant()
        self.assertTrue(self.call(req(self.node))["ok"])

    def test_only_enforceable_grants_are_accepted(self):
        with self.assertRaises(SystemExit):
            with mock.patch("sys.stderr", new=io.StringIO()):
                main(["bridge", "grant", "--state-dir", str(self.state), "--controller", "c", "--grant", "device_write"])


class TestProtocol(BridgeCase):
    def setUp(self):
        super().setUp()
        self.grant()

    def test_wrong_node_is_rejected(self):
        resp = self.call(req("rf-" + "0" * 32))
        self.assertEqual(resp["error"]["category"], "wrong_node")

    def test_incompatible_major_is_rejected_clearly(self):
        resp = self.call(req(self.node, protocol_major=2))
        self.assertEqual(resp["error"]["category"], "incompatible_protocol")

    def test_unknown_operation(self):
        resp = self.call(req(self.node, op="rootforge.flash.boot"))
        self.assertEqual(resp["error"]["category"], "unsupported_capability")

    def test_no_write_or_privileged_operation_is_registered(self):
        names = set(bridge.OPERATIONS)
        self.assertEqual(names, {"rootforge.capabilities.get", "rootforge.devices.list"})
        for forbidden in ("flash", "restore", "unlock", "terminal", "shell", "build", "su", "root"):
            self.assertFalse(any(forbidden in n[len('rootforge.'):] for n in names), forbidden)

    def test_malformed_json(self):
        resp = bridge.handle_line(self.ctx(), b"{nope")
        self.assertEqual(resp["error"]["category"], "invalid_request")

    def test_non_object_json(self):
        resp = bridge.handle_line(self.ctx(), b"[1,2]")
        self.assertEqual(resp["error"]["category"], "invalid_request")

    def test_unknown_field_rejected(self):
        resp = self.call(req(self.node, sudo=True))
        self.assertEqual(resp["error"]["category"], "invalid_request")

    def test_unexpected_parameters_rejected(self):
        resp = self.call(req(self.node, parameters={"serial": "x"}))
        self.assertEqual(resp["error"]["category"], "invalid_request")

    def test_bad_request_id_and_timeout(self):
        self.assertEqual(self.call(req(self.node, request_id="a b"))["error"]["category"], "invalid_request")
        self.assertEqual(self.call(req(self.node, timeout_ms=0))["error"]["category"], "invalid_request")
        self.assertEqual(self.call(req(self.node, timeout_ms=True))["error"]["category"], "invalid_request")

    def test_oversized_request_rejected_and_session_survives(self):
        big = b'{"x":"' + b"a" * (bridge.MAX_REQUEST_BYTES + 100) + b'"}\n'
        good = (json.dumps(req(self.node, request_id="after")) + "\n").encode()
        out = io.StringIO()
        bridge.serve(self.ctx(), io.BytesIO(big + good), out)
        frames = [json.loads(x) for x in out.getvalue().splitlines()]
        self.assertEqual(frames[0]["error"]["category"], "invalid_request")
        self.assertTrue(frames[1]["ok"])
        self.assertEqual(frames[1]["request_id"], "after")

    def test_timeout_is_enforced(self):
        slow = bridge.OperationSpec(lambda c, p: __import__("time").sleep(2), "inspect", "none")
        with mock.patch.dict(bridge.OPERATIONS, {"rootforge.test.slow": slow}):
            resp = self.call(req(self.node, op="rootforge.test.slow", timeout_ms=50))
        self.assertEqual(resp["error"]["category"], "timeout")

    def test_handler_bug_becomes_internal_error_without_detail_leak(self):
        def boom(c, p):
            raise RuntimeError("secret path /etc/shadow")
        with mock.patch.dict(bridge.OPERATIONS, {"rootforge.test.boom": bridge.OperationSpec(boom, "inspect", "none")}):
            with mock.patch("sys.stderr", new=io.StringIO()):
                resp = self.call(req(self.node, op="rootforge.test.boom"))
        self.assertEqual(resp["error"], {"category": "internal", "message": "internal error"})

    def test_stdout_is_protocol_only(self):
        out = io.StringIO()
        data = (json.dumps(req(self.node)) + "\n\n" + json.dumps(req(self.node, request_id="r2")) + "\n").encode()
        bridge.serve(self.ctx(), io.BytesIO(data), out)
        lines = out.getvalue().splitlines()
        self.assertEqual(len(lines), 2)
        for line in lines:
            json.loads(line)

    def test_capabilities_content(self):
        result = self.call(req(self.node))["result"]
        self.assertEqual(result["node_id"], self.node)
        self.assertEqual(result["protocol_majors"], [1])
        self.assertIn("rootforge.devices.list", [o["name"] for o in result["operations"]])
        self.assertEqual(set(result["tools"]), {"adb", "fastboot"})


class TestPassiveOperationsAreNotSideEffecting(BridgeCase):
    def test_devices_list_never_invokes_su_sudo_or_per_device_queries(self):
        self.grant()
        seen = []

        def fake_run(argv, **kwargs):
            seen.append(list(argv))
            return subprocess.CompletedProcess(argv, 0, "List of devices attached\nABC\tdevice\n", "")

        with mock.patch.object(devices.shutil, "which", return_value="/usr/bin/x"), \
                mock.patch.object(devices.subprocess, "run", side_effect=fake_run):
            resp = self.call(req(self.node, op="rootforge.devices.list"))
        self.assertTrue(resp["ok"], resp)
        flat = [tok for argv in seen for tok in argv]
        for banned in ("su", "sudo", "shell", "getprop", "root", "reboot"):
            self.assertNotIn(banned, flat)
        self.assertEqual({tuple(a) for a in seen}, {("adb", "devices"), ("fastboot", "devices")})
        self.assertEqual(resp["result"]["devices"][0]["serial"], "ABC")
        self.assertNotIn("properties", json.dumps(resp["result"]["devices"][0]) .replace('"properties": {}', ""))

    def test_bridge_does_not_import_the_device_profiler(self):
        src = (LIB / "rootforge/core/bridge.py").read_text()
        self.assertNotIn("profile_device", src)
        self.assertNotIn("core.device import", src)


class TestAuthorizedKeyLine(BridgeCase):
    KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleExampleExampleExampleExampleExample me@host"

    def test_line_is_restricted_and_pinned(self):
        line = bridge.authorized_key_line("dca-phone", self.state if str(self.state).startswith("/") else Path("/x"), self.KEY)
        self.assertTrue(line.startswith('restrict,command="'))
        self.assertIn("bridge serve --controller dca-phone", line)
        self.assertNotIn("me@host", line)
        self.assertIn("ssh-ed25519 AAAAC3", line)

    def test_injection_attempts_rejected(self):
        for bad in (
            'ssh-ed25519 AAAA" command="id',
            "ssh-ed25519 AAAA\nssh-ed25519 BBBB",
            "no-pty ssh-ed25519 AAAA",
            "",
        ):
            with self.assertRaises(bridge.BridgeError):
                bridge.authorized_key_line("c", Path("/s"), bad)
        parser = build_parser()
        for bad_id in ('x"; id', "a b", "../x", ""):
            with self.assertRaises(SystemExit):
                with mock.patch("sys.stderr", new=io.StringIO()):
                    parser.parse_args(["bridge", "revoke", "--controller", bad_id])
        with self.assertRaises(SystemExit):
            with mock.patch("sys.stderr", new=io.StringIO()):
                parser.parse_args(["bridge", "init", "--state-dir", '/tmp/a"b'])


class TestEndToEndProcess(BridgeCase):
    """Run the real CLI as a subprocess over pipes — the same shape SSH uses."""

    def run_session(self, lines, controller="ctl"):
        env = dict(os.environ, PYTHONPATH=str(LIB))
        proc = subprocess.run(
            [sys.executable, "-m", "rootforge.core.cli", "bridge", "serve",
             "--state-dir", str(self.state), "--controller", controller],
            input="".join(json.dumps(x) + "\n" for x in lines), capture_output=True, text=True, env=env, timeout=30,
        )
        return proc

    def test_round_trip(self):
        self.grant()
        proc = self.run_session([req(self.node), req(self.node, request_id="r2", op="rootforge.devices.list")])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        frames = [json.loads(x) for x in proc.stdout.splitlines()]
        self.assertEqual([f["request_id"] for f in frames], ["r1", "r2"])
        self.assertTrue(all(f["ok"] for f in frames))

    def test_unauthorized_round_trip(self):
        proc = self.run_session([req(self.node)], controller="stranger")
        self.assertEqual(json.loads(proc.stdout)["error"]["category"], "unauthorized")


if __name__ == "__main__":
    unittest.main()
