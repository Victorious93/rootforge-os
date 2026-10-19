#!/usr/bin/env python3
"""Crop the RootForge emblem from the repo logo and write Android launcher icons.

Source: assets/logo/rootforge-os-logo.jpg (fist + anvil + rings; no wordmark).
Regenerate:

    python3 android-app/tools/generate_launcher_icons.py

Writes adaptive layers (foreground in the 66% / 72dp-of-108dp safe zone,
solid navy background colour, monochrome stencil) and legacy
mdpi..xxxhdpi mipmaps (ic_launcher + ic_launcher_round).
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parents[2]
DEFAULT_LOGO = REPO / "assets/logo/rootforge-os-logo.jpg"
RES = REPO / "android-app/app/src/main/res"

# Android adaptive icon: 108dp canvas, inner 72dp is the unclipped safe zone.
SAFE_ZONE = 72 / 108
# Sampled from the logo corners (JPEG; see sample_background()).
NAVY_RGB = (12, 29, 54)  # #0C1D36
NAVY_HEX = "#0C1D36"

# Legacy / preview mipmap sizes (px).
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
# 108dp * 4 (xxxhdpi) for the adaptive layers.
FOREGROUND_PX = 432

# Soft chroma-key: below LO the pixel is the navy field; above HI it is emblem.
KEY_LO = 10.0
KEY_HI = 32.0
# Row-energy threshold used to find the gap between emblem and wordmark.
ROW_GAP_THRESHOLD = 12.0
CONTENT_THRESHOLD = 16.0
# Outer rings are faint (JPEG + glow); use a lower cut for the crop box.
BBOX_THRESHOLD = 8.0
CROP_PAD = 16


def sample_background(rgb: np.ndarray) -> tuple[int, int, int]:
    """Median of the four corners — the logo field, not the glowing strokes."""
    patches = [
        rgb[:24, :24],
        rgb[:24, -24:],
        rgb[-24:, :24],
        rgb[-24:, -24:],
    ]
    corners = np.concatenate([p.reshape(-1, 3) for p in patches], axis=0)
    bg = np.median(corners, axis=0)
    return tuple(int(round(c)) for c in bg)


def _row_energy(dist: np.ndarray, threshold: float) -> np.ndarray:
    mask = dist > threshold
    rows = mask.sum(axis=1).astype(np.float64)
    kernel = np.ones(9, dtype=np.float64) / 9.0
    return np.convolve(rows, kernel, mode="same")


def emblem_bbox(dist: np.ndarray) -> tuple[int, int, int, int]:
    """Bounding box of the fist/anvil/rings, stopping before the wordmark.

    The logo is emblem on top, then a navy gap, then "RootForge OS". The first
    deep valley in row-energy after the emblem starts is that gap.
    """
    smooth = _row_energy(dist, CONTENT_THRESHOLD)
    height = dist.shape[0]
    start = None
    for y, value in enumerate(smooth):
        if value > 30:
            start = y
            break
    if start is None:
        raise RuntimeError("logo has no emblem-sized content")

    gap_y = None
    for y in range(start + 40, height):
        if smooth[y] < ROW_GAP_THRESHOLD:
            gap_y = y
            break
    if gap_y is None:
        raise RuntimeError("could not find the navy gap under the emblem")

    region = dist[:gap_y] > BBOX_THRESHOLD
    ys, xs = np.where(region)
    if ys.size == 0:
        raise RuntimeError("emblem crop is empty")
    left, right = int(xs.min()), int(xs.max()) + 1
    top, bottom = int(ys.min()), int(ys.max()) + 1
    left = max(0, left - CROP_PAD)
    top = max(0, top - CROP_PAD)
    right = min(dist.shape[1], right + CROP_PAD)
    bottom = min(gap_y, bottom + CROP_PAD)
    return left, top, right, bottom


def extract_emblem(logo_path: Path) -> tuple[Image.Image, tuple[int, int, int]]:
    """Return a tight RGBA crop of the emblem and the sampled navy."""
    rgb_img = Image.open(logo_path).convert("RGB")
    rgb = np.asarray(rgb_img).astype(np.float32)
    bg = sample_background(rgb)
    dist = np.linalg.norm(rgb - np.asarray(bg, dtype=np.float32), axis=2)
    left, top, right, bottom = emblem_bbox(dist)

    crop_rgb = rgb[top:bottom, left:right]
    crop_dist = dist[top:bottom, left:right]
    alpha = np.clip((crop_dist - KEY_LO) / (KEY_HI - KEY_LO), 0.0, 1.0)
    rgba = np.zeros((crop_rgb.shape[0], crop_rgb.shape[1], 4), dtype=np.uint8)
    rgba[..., :3] = np.clip(np.rint(crop_rgb), 0, 255).astype(np.uint8)
    rgba[..., 3] = np.clip(np.rint(alpha * 255.0), 0, 255).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA"), bg


def _fit_into_safe_zone(emblem: Image.Image, canvas_px: int) -> Image.Image:
    """Center the emblem so its longer side fills the inner 66% safe zone."""
    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    safe = max(1, int(round(canvas_px * SAFE_ZONE)))
    src_w, src_h = emblem.size
    scale = min(safe / src_w, safe / src_h)
    new_w = max(1, int(round(src_w * scale)))
    new_h = max(1, int(round(src_h * scale)))
    fitted = emblem.resize((new_w, new_h), Image.Resampling.LANCZOS)
    x = (canvas_px - new_w) // 2
    y = (canvas_px - new_h) // 2
    canvas.alpha_composite(fitted, (x, y))
    return canvas


def make_foreground(emblem: Image.Image, canvas_px: int = FOREGROUND_PX) -> Image.Image:
    return _fit_into_safe_zone(emblem, canvas_px)


def make_monochrome(foreground: Image.Image) -> Image.Image:
    """White stencil: themed icons (API 33+) tint this layer."""
    src = np.asarray(foreground)
    out = np.zeros_like(src)
    out[..., :3] = 255
    out[..., 3] = src[..., 3]
    return Image.fromarray(out, "RGBA")


def _navy_canvas(size: int, navy: tuple[int, int, int]) -> Image.Image:
    return Image.new("RGBA", (size, size), navy + (255,))


def make_legacy_square(foreground: Image.Image, size: int, navy: tuple[int, int, int]) -> Image.Image:
    layer = foreground.resize((size, size), Image.Resampling.LANCZOS)
    out = _navy_canvas(size, navy)
    out.alpha_composite(layer)
    return out.convert("RGB")


def make_legacy_round(foreground: Image.Image, size: int, navy: tuple[int, int, int]) -> Image.Image:
    square = make_legacy_square(foreground, size, navy).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    # Inset 0.5px so the antialiased rim does not leave a hard corner.
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    square.putalpha(mask)
    return square


def make_preview(
    foreground: Image.Image,
    monochrome: Image.Image,
    navy: tuple[int, int, int],
    tile: int = 320,
) -> Image.Image:
    """Four tiles: square, circle, squircle-ish mask, monochrome on mid grey."""
    pad = 24
    tiles = 4
    width = pad + tiles * (tile + pad)
    height = tile + pad * 2
    sheet = Image.new("RGB", (width, height), (24, 24, 28))

    square = make_legacy_square(foreground, tile, navy)
    circle = make_legacy_round(foreground, tile, navy).convert("RGBA")
    circle_bg = Image.new("RGB", (tile, tile), (24, 24, 28))
    circle_bg.paste(circle, mask=circle.split()[3])

    # Approximate a squircle with a rounded-rect mask (launcher-style).
    squir = make_legacy_square(foreground, tile, navy).convert("RGBA")
    smask = Image.new("L", (tile, tile), 0)
    radius = int(tile * 0.22)
    ImageDraw.Draw(smask).rounded_rectangle((0, 0, tile - 1, tile - 1), radius=radius, fill=255)
    squir.putalpha(smask)
    squir_bg = Image.new("RGB", (tile, tile), (24, 24, 28))
    squir_bg.paste(squir, mask=squir.split()[3])

    mono = monochrome.resize((tile, tile), Image.Resampling.LANCZOS)
    mono_bg = Image.new("RGBA", (tile, tile), (90, 96, 104, 255))
    mono_bg.alpha_composite(mono)
    labels_src = [square, circle_bg, squir_bg, mono_bg.convert("RGB")]
    for i, src in enumerate(labels_src):
        x = pad + i * (tile + pad)
        sheet.paste(src.convert("RGB"), (x, pad))
    return sheet


def write_icons(res_dir: Path, foreground: Image.Image, monochrome: Image.Image, navy: tuple[int, int, int]) -> None:
    xxx = res_dir / "drawable-xxxhdpi"
    xxx.mkdir(parents=True, exist_ok=True)
    foreground.save(xxx / "ic_launcher_foreground.png", "PNG")
    monochrome.save(xxx / "ic_launcher_monochrome.png", "PNG")

    for name, size in DENSITIES.items():
        folder = res_dir / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)
        make_legacy_square(foreground, size, navy).save(folder / "ic_launcher.png", "PNG")
        make_legacy_round(foreground, size, navy).save(folder / "ic_launcher_round.png", "PNG")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--logo", type=Path, default=DEFAULT_LOGO)
    parser.add_argument("--res", type=Path, default=RES)
    parser.add_argument("--preview", type=Path, default=None, help="optional contact-sheet PNG")
    args = parser.parse_args(argv)

    if not args.logo.is_file():
        print(f"logo not found: {args.logo}", file=sys.stderr)
        return 2

    emblem, bg = extract_emblem(args.logo)
    if any(abs(a - b) > 2 for a, b in zip(bg, NAVY_RGB)):
        print(f"warning: sampled navy {bg} != pinned {NAVY_RGB}", file=sys.stderr)
    foreground = make_foreground(emblem)
    monochrome = make_monochrome(foreground)
    write_icons(args.res, foreground, monochrome, NAVY_RGB)
    if args.preview is not None:
        args.preview.parent.mkdir(parents=True, exist_ok=True)
        make_preview(foreground, monochrome, NAVY_RGB).save(args.preview, "PNG")
        print(f"wrote preview {args.preview}")
    print(f"wrote launcher icons under {args.res}")
    print(f"background {NAVY_HEX}  safe-zone {SAFE_ZONE:.4f}  emblem {emblem.size[0]}x{emblem.size[1]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
