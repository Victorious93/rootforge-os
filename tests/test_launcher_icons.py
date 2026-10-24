#!/usr/bin/env python3
"""Hermetic checks for the Android launcher icon produced from the repo logo.

Parses committed PNG/XML with the stdlib only (no Pillow). Does not flash a
device and does not run Gradle.
"""
from __future__ import annotations

import struct
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android-app/app/src/main/res"
GRADLE = ROOT / "android-app/app/build.gradle.kts"
MANIFEST = ROOT / "android-app/app/src/main/AndroidManifest.xml"

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
FOREGROUND_PX = 432
PNG_SIG = b"\x89PNG\r\n\x1a\n"


def png_ihdr(path: Path) -> tuple[int, int, int, int]:
    """Return (width, height, bit_depth, color_type) from a PNG IHDR."""
    data = path.read_bytes()
    if data[:8] != PNG_SIG:
        raise AssertionError(f"{path} is not a PNG")
    length, chunk = struct.unpack(">I4s", data[8:16])
    if chunk != b"IHDR" or length != 13:
        raise AssertionError(f"{path} missing IHDR")
    width, height, bit_depth, color_type = struct.unpack(">IIBB", data[16:26])
    return width, height, bit_depth, color_type


class LauncherIconTests(unittest.TestCase):
    def test_adaptive_xml_has_navy_foreground_and_monochrome(self) -> None:
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            text = (RES / "mipmap-anydpi-v26" / name).read_text()
            self.assertIn("<adaptive-icon", text)
            self.assertIn("@color/ic_launcher_background", text)
            self.assertIn("@drawable/ic_launcher_foreground", text)
            self.assertIn("@drawable/ic_launcher_monochrome", text)
            self.assertNotIn("ic_launcher_foreground.xml", text)

    def test_background_colour_is_logo_navy(self) -> None:
        colors = (RES / "values/colors.xml").read_text()
        self.assertIn('<color name="ic_launcher_background">#0C1D36</color>', colors)
        # App chrome stays on the existing near-black; do not reuse it as the icon field.
        self.assertIn('<color name="forge_bg">#0E1116</color>', colors)

    def test_legacy_mipmaps_exist_at_android_sizes(self) -> None:
        for density, size in DENSITIES.items():
            for name in ("ic_launcher.png", "ic_launcher_round.png"):
                path = RES / f"mipmap-{density}" / name
                self.assertTrue(path.is_file(), path)
                width, height, bit_depth, color_type = png_ihdr(path)
                self.assertEqual((width, height), (size, size), path)
                self.assertEqual(bit_depth, 8, path)
                # Square is opaque RGB (2); round has an alpha mask (6 = RGBA).
                if name.endswith("_round.png"):
                    self.assertEqual(color_type, 6, path)
                else:
                    self.assertEqual(color_type, 2, path)

    def test_adaptive_layers_are_xxxhdpi_rgba(self) -> None:
        for name in ("ic_launcher_foreground.png", "ic_launcher_monochrome.png"):
            path = RES / "drawable-xxxhdpi" / name
            width, height, bit_depth, color_type = png_ihdr(path)
            self.assertEqual((width, height, bit_depth, color_type), (FOREGROUND_PX, FOREGROUND_PX, 8, 6), path)

    def test_old_vector_foreground_is_gone(self) -> None:
        self.assertFalse((RES / "drawable/ic_launcher_foreground.xml").exists())

    def test_manifest_round_icon(self) -> None:
        text = MANIFEST.read_text()
        self.assertIn('android:icon="@mipmap/ic_launcher"', text)
        self.assertIn('android:roundIcon="@mipmap/ic_launcher_round"', text)

    def test_signing_application_id_and_version_untouched(self) -> None:
        text = GRADLE.read_text()
        self.assertIn('applicationId = "ai.rootforge.os"', text)
        self.assertIn("versionCode = 2", text)
        self.assertIn('versionName = "0.3.0"', text)
        self.assertIn('storeFile = file("debug.keystore")', text)
        self.assertIn('id("com.android.application")', text)


if __name__ == "__main__":
    unittest.main()
