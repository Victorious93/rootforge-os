#!/usr/bin/env python3
"""Hermetic checks for the how-to manual and its in-app copy.

Does not flash a device and does not run Gradle. Stdlib only.
"""
from __future__ import annotations

import struct
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs" / "MANUAL.md"
BUNDLE = ROOT / "android-app/core/src/main/resources/ai/rootforge/os/manual/MANUAL.md"
HEADER_DOCS = ROOT / "docs/images/manual/header.png"
HEADER_BUNDLE = ROOT / "android-app/core/src/main/resources/ai/rootforge/os/manual/header.png"
MANIFEST = ROOT / "android-app/app/src/main/AndroidManifest.xml"
UI = ROOT / "android-app/app/src/main/kotlin/ai/rootforge/os/ui/RootForgeUi.kt"
SETTINGS = ROOT / "android-app/app/src/main/kotlin/ai/rootforge/os/ui/screens/SettingsScreen.kt"
GRADLE = ROOT / "android-app/app/build.gradle.kts"
PNG_SIG = b"\x89PNG\r\n\x1a\n"

REQUIRED_HEADINGS = (
    "## What RootForge OS is, and its place in the fleet",
    "## Install and first-run setup",
    "## Every feature, step by step",
    "## Every permission, and why",
    "## Fleet integration",
    "## Troubleshooting and FAQ",
    "## Privacy and data handling",
    "## Glossary",
)


def png_ihdr(path: Path) -> tuple[int, int]:
    data = path.read_bytes()
    if data[:8] != PNG_SIG:
        raise AssertionError(f"{path} is not a PNG")
    length, chunk = struct.unpack(">I4s", data[8:16])
    if chunk != b"IHDR" or length != 13:
        raise AssertionError(f"{path} missing IHDR")
    width, height = struct.unpack(">II", data[16:24])
    return width, height


class ManualDocTests(unittest.TestCase):
    def test_docs_and_bundle_are_identical(self) -> None:
        self.assertTrue(DOCS.is_file(), f"missing {DOCS}")
        self.assertTrue(BUNDLE.is_file(), f"missing {BUNDLE}")
        self.assertEqual(DOCS.read_bytes(), BUNDLE.read_bytes())

    def test_required_sections_and_claims(self) -> None:
        text = DOCS.read_text(encoding="utf-8")
        for heading in REQUIRED_HEADINGS:
            self.assertIn(heading, text)
        self.assertIn("EXTERNAL_NODE", text)
        self.assertIn("protocol_major", text)
        self.assertIn("v0.2.0", text)
        self.assertIn("adb uninstall ai.rootforge.os.debug", text)
        self.assertIn("Allow external controllers", text)
        self.assertIn("off by default", text.lower())
        self.assertIn("POST_NOTIFICATIONS", text)
        self.assertIn("INTERNET", text)
        self.assertIn("moe.shizuku.manager.permission.API_V23", text)
        self.assertNotIn("coming in the next update", text.lower())
        self.assertNotIn("feat/recovery-installer", text)
        self.assertIn("FLASH RECOVERY", text)
        self.assertIn("docs/CUSTOM_RECOVERY_INSTALLER.md", text)
        self.assertIn("Install with Custom Recovery", text)
        self.assertIn("pitchblack.tech", text)
        self.assertIn("Look up TWRP", text)
        self.assertIn("Pin this VictorSuite", text)
        self.assertIn("rootforge-desktop-mode", text)
        self.assertIn("com.termux.permission.RUN_COMMAND", text)
        self.assertIn("docs/DESKTOP_MODE.md", text)
        self.assertNotIn("feat/desktop-mode-x11", text)
        self.assertIn("images/manual/header.png", text)
        self.assertNotIn("applicationIdSuffix", text)
        self.assertIn("**Root.**", text)
        self.assertIn("node.status.get", text)
        self.assertIn("NodeStatusSnapshot", text)
        self.assertIn("**Bootloop.**", text)
        self.assertIn("**LSPosed.**", text)
        self.assertIn("**SHA-256 / checksum.**", text)
        self.assertIn("### Devices", text)
        self.assertIn("Root plan", text)
        self.assertIn("Bootloader unlock portals", text)
        self.assertIn("oem get_unlock_data", text)
        self.assertIn("never sends serial", text.lower())
        self.assertIn("ESP32 & Pi helpers", text)
        self.assertIn("Factory Reset Protection", text)
        self.assertIn("05c6:9008", text)
        self.assertIn("••••", text)

    def test_header_art_is_wide_png_and_copied(self) -> None:
        self.assertTrue(HEADER_DOCS.is_file())
        self.assertTrue(HEADER_BUNDLE.is_file())
        self.assertEqual(HEADER_DOCS.read_bytes(), HEADER_BUNDLE.read_bytes())
        width, height = png_ihdr(HEADER_DOCS)
        self.assertGreaterEqual(width, 640)
        self.assertGreaterEqual(height, 320)
        self.assertGreater(HEADER_DOCS.stat().st_size, 20_000)

    def test_in_app_help_is_wired_and_identity_untouched(self) -> None:
        ui = UI.read_text(encoding="utf-8")
        self.assertIn('Help("Help"', ui)
        self.assertIn("ManualScreen", ui)
        self.assertIn('Links("Links"', ui)
        self.assertIn("LinksScreen", ui)
        self.assertIn('Glossary("Glossary"', ui)
        self.assertIn("GlossaryScreen", ui)
        self.assertIn("onOpenGlossary", ui)
        self.assertIn('Devices("Devices"', ui)
        self.assertIn("DevicesScreen", ui)
        settings = SETTINGS.read_text(encoding="utf-8")
        self.assertIn("Open the manual", settings)
        self.assertIn("onOpenHelp", settings)
        self.assertIn("onOpenLinks", settings)
        self.assertIn("Links directory", settings)
        help_screen = (ROOT / "android-app/app/src/main/kotlin/ai/rootforge/os/ui/screens/ManualScreen.kt").read_text(encoding="utf-8")
        self.assertIn("onOpenGlossary", help_screen)
        self.assertIn('"Glossary"', help_screen)
        links_screen = (ROOT / "android-app/app/src/main/kotlin/ai/rootforge/os/ui/screens/LinksScreen.kt").read_text(encoding="utf-8")
        self.assertIn("onOpenGlossary", links_screen)
        self.assertIn('"Glossary"', links_screen)
        gradle = GRADLE.read_text(encoding="utf-8")
        self.assertIn('applicationId = "ai.rootforge.os"', gradle)
        self.assertIn('versionCode = 1', gradle)
        self.assertIn('versionName = "0.1.0"', gradle)
        self.assertIn("debug.keystore", gradle)
        manifest = MANIFEST.read_text(encoding="utf-8")
        self.assertIn("android.permission.INTERNET", manifest)
        self.assertIn("android.permission.POST_NOTIFICATIONS", manifest)
        self.assertIn("com.termux.permission.RUN_COMMAND", manifest)
        self.assertIn("ai.victorsuite.permission.DESKTOP_MODE", manifest)

    def test_glossary_entries_are_non_empty_and_unique(self) -> None:
        text = DOCS.read_text(encoding="utf-8")
        self.assertIn("## Glossary", text)
        section = text.split("## Glossary", 1)[1]
        if "\n---" in section:
            section = section.split("\n---", 1)[0]
        terms: list[str] = []
        for line in section.splitlines():
            if not line.startswith("**") or ".** " not in line:
                continue
            term, body = line[2:].split(".** ", 1)
            self.assertTrue(term.strip(), "empty glossary term")
            self.assertTrue(body.strip(), f"empty glossary line for {term}")
            terms.append(term)
        required = (
            "Root", "su", "Magisk", "KernelSU", "KernelSU Next", "APatch", "Module",
            "Zygisk", "Zygisk Next", "ReZygisk", "Xposed", "LSPosed", "Vector",
            "Shizuku", "Sui", "Dhizuku", "ADB", "fastboot", "EDL", "Serial", "SoC", "Slot", "USB mode", "Bootloader",
            "Recovery", "TWRP", "PitchBlack", "Boot image", "ROM", "Flash",
            "Dry run", "SHA-256 / checksum", "Bootloop",
        )
        for needed in required:
            self.assertIn(needed, terms, f"missing {needed}")
        self.assertEqual(len(terms), len(set(terms)))
        self.assertEqual(len(terms), len({t.lower() for t in terms}))


if __name__ == "__main__":
    unittest.main()
