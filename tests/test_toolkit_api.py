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


if __name__ == "__main__":
    unittest.main()
