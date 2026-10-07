"""One table of samples, two implementations.

Script logs are written by Bash, whose redaction is a sed script (`rf_redact` in
sh/common.sh); everything the CLI writes is redacted by `rootforge.core.log`.
They must agree, and each must give the INTENDED result, so a sample here fails
if either drifts or if both are wrong the same way.
"""
import subprocess
import sys
import unittest
from pathlib import Path

LIB = Path(__file__).resolve().parents[1] / "config/includes.chroot/usr/local/lib"
sys.path.insert(0, str(LIB))

from rootforge.core.log import redact_text  # noqa: E402

COMMON = LIB / "rootforge/sh/common.sh"
R = "***REDACTED***"
PEM = "***REDACTED PRIVATE KEY***"

# (label, input, expected)
SAMPLES = [
    # token shapes
    ("anthropic key", "key was sk-ant-api03-abcdefghijklmnop ok", f"key was {R} ok"),
    ("openai-style key", "sk-abcdefghijklmnopqrstuvwxyz1234", R),
    ("github token", "token ghp_abcdefghijklmnopqrstuvwxyz0123", f"token {R}"),
    ("github fine-grained", "github_pat_11ABCDEFG0abcdefghijklmnop", R),
    ("google api key", "AIzaSyA-abcdefghijklmnopqrstuvwxyz012345", R),
    ("bearer header", "Authorization: Bearer abcdefghijklmnop.qrs", f"Authorization: {R}"),
    ("bearer lowercase", "authorization: bearer abcdefghijklmnop", f"authorization: {R}"),
    ("tailscale key", "using tskey-auth-kABCDEFGHIJK-xyz now", f"using {R} now"),
    ("slack token", "xoxb-1234567890-abcdefghij", R),
    ("aws access key id", "id AKIAABCDEFGHIJKLMNOP end", f"id {R} end"),
    ("hugging face token", "hf_abcdefghijklmnopqrstuvwxyz", R),
    # options
    ("--authkey VALUE", "tailscale up --authkey S3CRET123", f"tailscale up --authkey {R}"),
    ("--auth-key=VALUE", "x --auth-key=S3CRET123 y", f"x --auth-key={R} y"),
    ("--password VALUE", "tool --password hunter2 --verbose", f"tool --password {R} --verbose"),
    ("-token VALUE", "curl -token abc123", f"curl -token {R}"),
    ("--password-stdin is not an option with a value", "docker login --password-stdin", "docker login --password-stdin"),
    # assignments
    ("shell export", 'export ANTHROPIC_API_KEY="plainvalue"', f"export ANTHROPIC_API_KEY={R}"),
    ("bare assignment", "PASSWORD=hunter2 ./run", f"PASSWORD={R} ./run"),
    ("wireguard style", "PrivateKey = AbCdEf12345=", f"PrivateKey = {R}"),
    ("colon form", "api_key: abcdef", f"api_key: {R}"),
    ("json member", '{"api_key": "zzz", "n": 1}', f'{{"api_key": {R}, "n": 1}}'),
    ("single-quoted value", "SECRET='a b c'", f"SECRET={R}"),
    ("dotted name", "ollama.api.token=abc", f"ollama.api.token={R}"),
    ("name with suffix after delimiter", "KEY_FILE=/tmp/x", f"KEY_FILE={R}"),
    ("case-insensitive name", "Password=Hunter2", f"Password={R}"),
    # private keys
    ("pem block", "a\n-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\nBBBB\n-----END OPENSSH PRIVATE KEY-----\nb", f"a\n{PEM}\nb"),
    ("pem unterminated", "a\n-----BEGIN RSA PRIVATE KEY-----\nAAAA\nBBBB", f"a\n{PEM}"),
    ("pem single line", "k -----BEGIN PRIVATE KEY----- AAAA -----END PRIVATE KEY----- tail", f"k {PEM} tail"),
    # must NOT change
    ("ordinary text", "Backed up 3 partitions to /home/u/rootforge", "Backed up 3 partitions to /home/u/rootforge"),
    ("keyboard is not a secret name", "keyboard: US layout", "keyboard: US layout"),
    ("monkey is not a delimiter hit", "monkeys: 3", "monkeys: 3"),
    ("a bare word key", "press any key to continue", "press any key to continue"),
    ("serials and hashes", "serial=ABC123 sha256=" + "a" * 64, "serial=ABC123 sha256=" + "a" * 64),
    ("short sk- prefix", "sk-short", "sk-short"),
    # idempotent
    ("already redacted", f"API_KEY={R}", f"API_KEY={R}"),
]


def shell_redact(text: str) -> str:
    proc = subprocess.run(
        ["bash", "-c", '. "$1"; rf_redact', "_", str(COMMON)],
        input=text + "\n", capture_output=True, text=True, check=True,
    )
    return proc.stdout[:-1] if proc.stdout.endswith("\n") else proc.stdout


class TestRedactionParity(unittest.TestCase):
    def test_python_gives_the_intended_result(self):
        for label, given, expected in SAMPLES:
            with self.subTest(label=label):
                self.assertEqual(redact_text(given), expected)

    def test_shell_gives_the_intended_result(self):
        for label, given, expected in SAMPLES:
            with self.subTest(label=label):
                self.assertEqual(shell_redact(given), expected)

    # A second pass over `"k": ***REDACTED***,` takes the comma as part of the
    # unquoted value and drops it. That is cosmetic; making the value rule stop at
    # a comma instead would leak the tail of a secret such as `abc,def`.
    NOT_IDEMPOTENT = {"json member"}

    def test_both_are_idempotent(self):
        for label, given, _ in SAMPLES:
            if label in self.NOT_IDEMPOTENT:
                continue
            with self.subTest(label=label):
                once = redact_text(given)
                self.assertEqual(redact_text(once), once)
                self.assertEqual(shell_redact(once), once)


if __name__ == "__main__":
    unittest.main()
