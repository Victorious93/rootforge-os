#!/usr/bin/env python3
"""Hermetic tests for docker/api/rootforge_api.py (no Docker, no device)."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "docker" / "api"))

import rootforge_api as api  # noqa: E402


class FakeRunner(api.Runner):
    def __init__(self) -> None:
        self.calls = []
        self.script = []

    def run(self, argv, timeout=60.0):
        self.calls.append(list(argv))
        if self.script:
            return self.script.pop(0)
        return 0, "", ""


class ToolkitApiTests(unittest.TestCase):
    def setUp(self):
        self.runner = FakeRunner()
        self.tk = api.Toolkit(runner=self.runner, node_id="rf-test")
        self.tk.adb = "/stub/adb"
        self.tk.fastboot = "/stub/fastboot"

    def handle(self, op, params=None, confirmation=None, **extra):
        req = {
            "protocol_major": 1,
            "request_id": "t1",
            "target_node_id": "rf-test",
            "operation": op,
            "parameters": params or {},
        }
        req.update(extra)
        return self.tk.handle(req, confirmation)

    def test_capabilities_lists_bridge_ops(self):
        r = self.handle("rootforge.capabilities.get")
        self.assertTrue(r["ok"])
        names = {o["name"] for o in r["result"]["operations"]}
        self.assertIn("rootforge.fastboot.flash", names)
        self.assertIn("rootforge.devices.list", names)

    def test_devices_parses_adb_and_fastboot(self):
        self.runner.script = [
            (0, "List of devices attached\nSERIAL1 device usb:1-1\n", ""),
            (0, "FB1\tfastboot\n", ""),
        ]
        r = self.handle("rootforge.devices.list")
        serials = [d["serial"] for d in r["result"]["devices"]]
        self.assertEqual(serials, ["SERIAL1", "FB1"])

    def test_flash_refused_without_confirmation(self):
        r = self.handle("rootforge.fastboot.flash", {"partition": "boot", "image": "/tmp/x.img"})
        self.assertFalse(r["ok"])
        self.assertEqual(r["error"]["category"], "denied_by_user")
        self.assertEqual(self.runner.calls, [])

    def test_flash_wrong_phrase_is_denied(self):
        r = self.handle(
            "rootforge.fastboot.flash",
            {"partition": "boot", "image": "/tmp/x.img"},
            confirmation="flash boot",
        )
        self.assertFalse(r["ok"])
        self.assertEqual(self.runner.calls, [])

    def queue_fastboot(self, listing="FB1\tfastboot\n", follow=None):
        """adb devices, fastboot devices, then an optional command result."""
        script = [(0, "", ""), (0, listing, "")]
        if follow is not None:
            script.append(follow)
        self.runner.script = script

    def test_unlock_pins_the_only_fastboot_device(self):
        self.queue_fastboot(follow=(0, "", ""))
        r = self.handle("rootforge.fastboot.unlock", confirmation="UNLOCK")
        self.assertTrue(r["ok"], r)
        self.assertEqual(
            self.runner.calls[-1],
            ["/stub/fastboot", "-s", "FB1", "flashing", "unlock"],
        )

    def test_unlock_refuses_a_serial_that_is_not_in_fastboot(self):
        self.queue_fastboot()
        r = self.handle("rootforge.fastboot.unlock", {"serial": "OTHER"}, confirmation="UNLOCK")
        self.assertFalse(r["ok"])
        self.assertIn("refusing to target a different device", r["error"]["message"])
        self.assertFalse(any("flashing" in call for call in self.runner.calls))

    def test_flash_uses_the_requested_serial_not_the_other_device(self):
        with tempfile.NamedTemporaryFile() as image:
            image.write(b"ANDROID!")
            image.flush()
            self.queue_fastboot("KEEP\tfastboot\nOTHER\tfastboot\n", follow=(0, "OKAY\n", ""))
            missed = self.handle(
                "rootforge.fastboot.flash",
                {"partition": "boot", "image": image.name},
                confirmation="FLASH boot",
            )
            self.assertFalse(missed["ok"])
            self.assertIn("more than one device", missed["error"]["message"])
            self.assertFalse(any("flash" in call for call in self.runner.calls))

            self.queue_fastboot("KEEP\tfastboot\nOTHER\tfastboot\n", follow=(0, "OKAY\n", ""))
            hit = self.handle(
                "rootforge.fastboot.flash",
                {"partition": "boot", "image": image.name, "serial": "OTHER"},
                confirmation="FLASH boot",
            )
            self.assertTrue(hit["ok"], hit)
            self.assertEqual(
                self.runner.calls[-1],
                ["/stub/fastboot", "-s", "OTHER", "flash", "boot", image.name],
            )

    def test_adb_shell_pins_serial_and_refuses_an_ambiguous_target(self):
        self.runner.script = [
            (0, "List of devices attached\nPHONE\tdevice\nOTHER\tdevice\n", ""),
            (0, "", ""),
        ]
        missed = self.handle("rootforge.adb.shell", {"command": "id"})
        self.assertFalse(missed["ok"])
        self.assertIn("more than one device", missed["error"]["message"])
        self.assertFalse(any("shell" in call for call in self.runner.calls))

        self.runner.calls.clear()
        self.runner.script = [
            (0, "List of devices attached\nPHONE\tdevice\nOTHER\tdevice\n", ""),
            (0, "", ""),
            (0, "uid=0\n", ""),
        ]
        hit = self.handle("rootforge.adb.shell", {"command": "id", "serial": "OTHER"})
        self.assertTrue(hit["ok"], hit)
        self.assertEqual(self.runner.calls[-1], ["/stub/adb", "-s", "OTHER", "shell", "id"])

        self.runner.calls.clear()
        self.runner.script = [
            (0, "List of devices attached\nONLY\tdevice\n", ""),
            (0, "", ""),
            (0, "ok\n", ""),
        ]
        only = self.handle("rootforge.adb.shell", {"command": "id"})
        self.assertTrue(only["ok"], only)
        self.assertEqual(self.runner.calls[-1][:4], ["/stub/adb", "-s", "ONLY", "shell"])

    def test_adb_reboot_pins_serial(self):
        self.runner.script = [
            (0, "PHONE\tdevice\n", ""),
            (0, "", ""),
            (0, "", ""),
        ]
        r = self.handle("rootforge.adb.reboot", {"target": "bootloader", "serial": "PHONE"})
        self.assertTrue(r["ok"], r)
        self.assertEqual(self.runner.calls[-1][:4], ["/stub/adb", "-s", "PHONE", "reboot"])

    def test_wrong_node(self):
        r = self.handle("rootforge.devices.list")
        r = self.tk.handle(
            {
                "protocol_major": 1,
                "request_id": "t1",
                "target_node_id": "rf-other",
                "operation": "rootforge.devices.list",
                "parameters": {},
            },
            None,
        )
        self.assertEqual(r["error"]["category"], "wrong_node")

    def test_unknown_operation(self):
        r = self.handle("rootforge.rm.rf")
        self.assertEqual(r["error"]["category"], "unknown_operation")

    def test_http_health_and_auth(self):
        httpd = api.serve("127.0.0.1", 0, self.tk, "secret-token")
        host, port = httpd.server_address[:2]
        t = __import__("threading").Thread(target=httpd.serve_forever, daemon=True)
        t.start()
        try:
            import urllib.error
            import urllib.request

            health = urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2)
            body = json.loads(health.read().decode())
            self.assertTrue(body["ok"])

            req = urllib.request.Request(
                f"http://127.0.0.1:{port}/v1/bridge",
                data=json.dumps(
                    {
                        "protocol_major": 1,
                        "request_id": "h",
                        "target_node_id": "rf-test",
                        "operation": "rootforge.capabilities.get",
                        "parameters": {},
                    }
                ).encode(),
                method="POST",
            )
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(req, timeout=2)
            self.assertEqual(ctx.exception.code, 401)

            req.add_header("Authorization", "Bearer secret-token")
            with urllib.request.urlopen(req, timeout=2) as resp:
                payload = json.loads(resp.read().decode())
            self.assertTrue(payload["ok"])
        finally:
            httpd.shutdown()
            httpd.server_close()


class ToolkitHttpHardeningTests(unittest.TestCase):
    """Authenticated-client edge cases and the generated-token file, against the real HTTP handler."""

    TOKEN = "secret-token"

    def setUp(self):
        runner = FakeRunner()
        self.tk = api.Toolkit(runner=runner, node_id="rf-test")
        self.tk.adb = "/stub/adb"
        self.tk.fastboot = "/stub/fastboot"
        self.httpd = api.serve("127.0.0.1", 0, self.tk, self.TOKEN)
        self.port = self.httpd.server_address[1]
        threading = __import__("threading")
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.addCleanup(self._stop)

    def _stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def raw(self, headers: str, body: bytes = b"", wait: float = 3.0, auth: bool = True):
        """One request on its own connection; returns (status line, header block lowercased) or (None, None) on a hang."""
        import socket

        head = "POST /v1/bridge HTTP/1.1\r\nHost: x\r\n"
        if auth:
            head += f"Authorization: Bearer {self.TOKEN}\r\n"
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=wait)
        try:
            sock.sendall((head + headers + "\r\n").encode("latin-1") + body)
            data = b""
            try:
                while b"\r\n\r\n" not in data:
                    chunk = sock.recv(4096)
                    if not chunk:
                        break
                    data += chunk
            except socket.timeout:
                return None, None
            if not data:
                return "", ""
            status, _, rest = data.partition(b"\r\n")
            return status.decode("latin-1"), rest.partition(b"\r\n\r\n")[0].decode("latin-1").lower()
        finally:
            sock.close()

    def test_a_negative_content_length_is_a_400_not_a_hung_thread(self):
        status, _ = self.raw("Content-Length: -1\r\n")
        self.assertIsNotNone(status, "the handler thread hung reading until EOF")
        self.assertIn(" 400 ", status)

    def test_a_non_numeric_content_length_is_a_400(self):
        status, _ = self.raw("Content-Length: abc\r\n")
        self.assertIn(" 400 ", status or "(no response)")

    def test_json_nested_deeper_than_the_decoder_allows_is_a_400(self):
        body = b"[" * 30000 + b"]" * 30000
        self.assertLess(len(body), api.MAX_BODY)
        status, _ = self.raw(f"Content-Length: {len(body)}\r\n", body)
        self.assertIn(" 400 ", status or "(no response)")

    def test_the_server_keeps_working_after_those_requests(self):
        self.raw("Content-Length: abc\r\n")
        body = json.dumps({"protocol_major": 1, "operation": "rootforge.capabilities.get"}).encode()
        status, _ = self.raw(f"Content-Length: {len(body)}\r\n", body)
        self.assertIn(" 200 ", status or "(no response)")

    def test_a_non_ascii_token_is_a_401_not_a_crash(self):
        # hmac.compare_digest raises TypeError on a non-ASCII str, so the comparison must be on bytes.
        status, _ = self.raw("Authorization: Bearer caf\xe9\r\nContent-Length: 0\r\n", auth=False)
        self.assertIn(" 401 ", status or "(no response)")

    def test_an_unread_body_closes_the_connection(self):
        status, headers = self.raw("Content-Length: 5\r\n", b"hello", auth=False)
        self.assertIn(" 401 ", status or "(no response)")
        self.assertIn("connection: close", headers or "")

    def test_the_token_file_is_created_0600_even_under_a_loose_umask(self):
        import os
        import stat

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "sub" / "api.token"
            old = os.umask(0o022)
            try:
                api._write_token_file(path, "abc123")
            finally:
                os.umask(old)
            self.assertEqual(path.read_text(), "abc123\n")
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_the_token_file_is_never_written_through_a_symlink(self):
        import os
        import stat

        with tempfile.TemporaryDirectory() as tmp:
            victim = Path(tmp) / "victim.txt"
            victim.write_text("precious\n")
            victim.chmod(0o644)
            path = Path(tmp) / "api.token"
            path.symlink_to(victim)
            api._write_token_file(path, "abc123")
            self.assertEqual(victim.read_text(), "precious\n")
            self.assertEqual(stat.S_IMODE(victim.stat().st_mode), 0o644)
            self.assertFalse(path.is_symlink())
            self.assertEqual(path.read_text(), "abc123\n")
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_a_stale_token_file_is_replaced_with_a_private_one(self):
        import os
        import stat

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "api.token"
            path.write_text("old\n")
            path.chmod(0o644)
            api._write_token_file(path, "new")
            self.assertEqual(path.read_text(), "new\n")
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)


if __name__ == "__main__":
    unittest.main()
