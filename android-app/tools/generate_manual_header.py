#!/usr/bin/env python3
"""Build the how-to manual header: emblem + neon cyan-to-magenta ring.

Source: assets/logo/rootforge-os-logo.jpg (fist + anvil + rings; no wordmark).
Regenerate:

    python3 android-app/tools/generate_manual_header.py

Writes docs/images/manual/header.png and the same bytes under
android-app/core/src/main/resources/ai/rootforge/os/manual/header.png.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = Path(__file__).resolve().parents[2]
TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
from generate_launcher_icons import NAVY_RGB, extract_emblem  # noqa: E402

CYAN = (0, 240, 255)
MAGENTA = (255, 0, 196)
WIDTH = 1280
HEIGHT = 640
EMBLEM_PX = 320
RING_INNER = 188
RING_OUTER = 212
GLOW_OUTER = 248


def paint_ring(canvas: Image.Image, cx: int, cy: int) -> None:
    """Additive neon ring: a bright band plus a soft outer glow."""
    arr = np.asarray(canvas).astype(np.float32)
    h, w = arr.shape[:2]
    ys, xs = np.ogrid[:h, :w]
    dx = xs.astype(np.float32) - cx
    dy = ys.astype(np.float32) - cy
    r = np.sqrt(dx * dx + dy * dy)
    ang = np.arctan2(dy, dx)

    # Colour field (H, W, 3)
    t = (np.sin(ang) + 1.0) * 0.5
    colour = np.empty((h, w, 3), dtype=np.float32)
    for i in range(3):
        colour[..., i] = CYAN[i] + (MAGENTA[i] - CYAN[i]) * t

    mid = (RING_INNER + RING_OUTER) / 2.0
    half = (RING_OUTER - RING_INNER) / 2.0
    band = np.clip(1.0 - np.abs(r - mid) / half, 0.0, 1.0)
    band = band ** 0.55

    glow = np.clip(1.0 - np.abs(r - mid) / (GLOW_OUTER - mid), 0.0, 1.0)
    glow = glow ** 2.2 * 0.55

    strength = np.clip(band + glow, 0.0, 1.0)[..., None]
    arr[..., :3] = arr[..., :3] * (1.0 - strength * 0.85) + colour * strength
    canvas.paste(Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8), "RGB"))


def _title_font(size: int) -> ImageFont.ImageFont:
    for name in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        "/usr/share/fonts/truetype/freefont/FreeSansBold.ttf",
    ):
        path = Path(name)
        if path.is_file():
            return ImageFont.truetype(str(path), size)
    return ImageFont.load_default()


def make_header(logo: Path) -> Image.Image:
    emblem, _bg = extract_emblem(logo)
    canvas = Image.new("RGB", (WIDTH, HEIGHT), NAVY_RGB)
    cx, cy = WIDTH // 2, HEIGHT // 2 - 28
    paint_ring(canvas, cx, cy)

    fitted = emblem.resize((EMBLEM_PX, EMBLEM_PX), Image.Resampling.LANCZOS)
    # Soft drop-shadow so the emblem sits above the glow.
    shadow = Image.new("RGBA", (EMBLEM_PX + 24, EMBLEM_PX + 24), (0, 0, 0, 0))
    shadow.paste((0, 0, 0, 90), (12, 14, 12 + EMBLEM_PX, 14 + EMBLEM_PX))
    shadow = shadow.filter(ImageFilter.GaussianBlur(10))
    canvas.paste(shadow, (cx - EMBLEM_PX // 2 - 12, cy - EMBLEM_PX // 2 - 10), shadow)
    canvas.paste(fitted, (cx - EMBLEM_PX // 2, cy - EMBLEM_PX // 2), fitted)

    draw = ImageDraw.Draw(canvas)
    title = "RootForge OS"
    subtitle = "How-to manual"
    title_font = _title_font(42)
    sub_font = _title_font(22)
    tw = draw.textlength(title, font=title_font)
    sw = draw.textlength(subtitle, font=sub_font)
    draw.text(((WIDTH - tw) / 2, HEIGHT - 118), title, fill=(230, 240, 255), font=title_font)
    draw.text(((WIDTH - sw) / 2, HEIGHT - 68), subtitle, fill=(0, 240, 255), font=sub_font)
    return canvas


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--logo", type=Path, default=REPO / "assets/logo/rootforge-os-logo.jpg")
    parser.add_argument("--out", type=Path, default=REPO / "docs/images/manual/header.png")
    parser.add_argument(
        "--bundle",
        type=Path,
        default=REPO / "android-app/core/src/main/resources/ai/rootforge/os/manual/header.png",
    )
    args = parser.parse_args(argv)
    if not args.logo.is_file():
        print(f"logo not found: {args.logo}", file=sys.stderr)
        return 2
    image = make_header(args.logo)
    for dest in (args.out, args.bundle):
        dest.parent.mkdir(parents=True, exist_ok=True)
        image.save(dest, "PNG", optimize=True)
        print(f"wrote {dest} ({dest.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
