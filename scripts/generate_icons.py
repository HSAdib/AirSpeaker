#!/usr/bin/env python3
"""
generate_icons.py — Convert logo.jpg -> assets/icon_07_master.png and generate
shaped transparent desktop icons (Windows/Linux/macOS), Android adaptive + legacy
icons, and opaque iOS AppIcon assets for AirSpeaker.
"""

import collections
import math
import sys
from pathlib import Path
from typing import Tuple

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
RAW_JPG = ROOT / "logo.jpg"
ASSETS_DIR = ROOT / "assets"
MASTER_PNG = ASSETS_DIR / "icon_07_master.png"

PC_VENDOR_DIR = ROOT / "airspeaker-pc" / "vendor"
ANDROID_RES_DIR = ROOT / "AirSpeakerAndroid" / "app" / "src" / "main" / "res"
IOS_ICONSET_DIR = ROOT / "AirSpeakeriOS" / "AirSpeaker" / "Assets.xcassets" / "AppIcon.appiconset"


def migrate_logo_to_assets() -> Path:
    """Convert repo-root logo.jpg to assets/icon_07_master.png and remove logo.jpg."""
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    if RAW_JPG.exists():
        img = Image.open(RAW_JPG).convert("RGB")
        img.save(MASTER_PNG, format="PNG")
        RAW_JPG.unlink()
        print(f"[OK] Converted & moved {RAW_JPG.name} -> {MASTER_PNG.relative_to(ROOT)}")
    elif not MASTER_PNG.exists():
        raise FileNotFoundError(
            f"Missing source logo! Place logo.jpg at {RAW_JPG} or master PNG at {MASTER_PNG}."
        )
    else:
        print(f"[OK] Using existing master logo: {MASTER_PNG.relative_to(ROOT)}")
    return MASTER_PNG


def extract_clean_rgba_silhouette(src_path: Path) -> Image.Image:
    """
    Border-seeded flood-fill background removal with edge color decontamination.
    Strictly preserves bright/white interior details (play triangle & wave arcs)
    while removing the exterior white backdrop cleanly for dark Windows Start Menus.
    """
    rgb = np.array(Image.open(src_path).convert("RGB"), dtype=np.float32)
    h, w, _ = rgb.shape

    r, g, b = rgb[:, :, 0], rgb[:, :, 1], rgb[:, :, 2]
    min_c = np.minimum(np.minimum(r, g), b)
    max_c = np.maximum(np.maximum(r, g), b)
    chroma = max_c - min_c

    # Candidate exterior white background pixels (handles minor JPEG artifacts)
    bg_candidate = (min_c >= 224.0) & (chroma <= 28.0)

    # 4-connected BFS flood fill seeded strictly from the outer image border
    bg_mask = np.zeros((h, w), dtype=bool)
    q = collections.deque()

    for x in range(w):
        if bg_candidate[0, x] and not bg_mask[0, x]:
            bg_mask[0, x] = True
            q.append((0, x))
        if bg_candidate[h - 1, x] and not bg_mask[h - 1, x]:
            bg_mask[h - 1, x] = True
            q.append((h - 1, x))
    for y in range(h):
        if bg_candidate[y, 0] and not bg_mask[y, 0]:
            bg_mask[y, 0] = True
            q.append((y, 0))
        if bg_candidate[y, w - 1] and not bg_mask[y, w - 1]:
            bg_mask[y, w - 1] = True
            q.append((y, w - 1))

    while q:
        cy, cx = q.popleft()
        if cy > 0 and bg_candidate[cy - 1, cx] and not bg_mask[cy - 1, cx]:
            bg_mask[cy - 1, cx] = True
            q.append((cy - 1, cx))
        if cy + 1 < h and bg_candidate[cy + 1, cx] and not bg_mask[cy + 1, cx]:
            bg_mask[cy + 1, cx] = True
            q.append((cy + 1, cx))
        if cx > 0 and bg_candidate[cy, cx - 1] and not bg_mask[cy, cx - 1]:
            bg_mask[cy, cx - 1] = True
            q.append((cy, cx - 1))
        if cx + 1 < w and bg_candidate[cy, cx + 1] and not bg_mask[cy, cx + 1]:
            bg_mask[cy, cx + 1] = True
            q.append((cy, cx + 1))

    # Build 2-pixel outer boundary band around exterior background for anti-aliased de-fringing
    dil1 = bg_mask.copy()
    dil1[1:, :] |= bg_mask[:-1, :]
    dil1[:-1, :] |= bg_mask[1:, :]
    dil1[:, 1:] |= bg_mask[:, :-1]
    dil1[:, :-1] |= bg_mask[:, 1:]

    dil2 = dil1.copy()
    dil2[1:, :] |= dil1[:-1, :]
    dil2[:-1, :] |= dil1[1:, :]
    dil2[:, 1:] |= dil1[:, :-1]
    dil2[:, :-1] |= dil1[:, 1:]

    edge_band = dil2 & (~bg_mask)

    alpha = np.where(bg_mask, 0.0, 255.0).astype(np.float32)
    out_rgb = rgb.copy()

    # Estimate alpha & decontaminate white matte strictly inside the 2px exterior boundary band
    dist_white = np.sqrt((255.0 - r) ** 2 + (255.0 - g) ** 2 + (255.0 - b) ** 2)
    fringe_pixels = edge_band & (min_c > 110.0) & (dist_white < 165.0)
    if np.any(fringe_pixels):
        a_norm = np.clip((dist_white[fringe_pixels] - 18.0) / 115.0, 0.0, 1.0)
        alpha[fringe_pixels] = a_norm * 255.0
        safe_a = np.maximum(a_norm, 0.08)[:, None]
        raw_p = out_rgb[fringe_pixels]
        decontam = (raw_p - (1.0 - a_norm[:, None]) * 255.0) / safe_a
        out_rgb[fringe_pixels] = np.clip(decontam, 0.0, 255.0)

    rgba = np.dstack([
        np.clip(out_rgb, 0, 255).astype(np.uint8),
        np.clip(alpha, 0, 255).astype(np.uint8),
    ])
    img = Image.fromarray(rgba, mode="RGBA")

    # Tightly crop to visible silhouette
    alpha_arr = np.array(img.getchannel("A"))
    ys, xs = np.where(alpha_arr > 12)
    if len(xs) == 0 or len(ys) == 0:
        raise RuntimeError("Flood-fill produced an empty mask — check source logo image.")
    pad = 2
    x0 = max(0, int(xs.min()) - pad)
    y0 = max(0, int(ys.min()) - pad)
    x1 = min(w, int(xs.max()) + pad + 1)
    y1 = min(h, int(ys.max()) + pad + 1)
    return img.crop((x0, y0, x1, y1))


def place_centered_rgba(fg: Image.Image, canvas_size: int, fill_ratio: float) -> Image.Image:
    """Place transparent foreground centered on a square transparent RGBA canvas."""
    max_dim = max(1, int(round(canvas_size * fill_ratio)))
    scale = min(max_dim / fg.width, max_dim / fg.height)
    new_w = max(1, int(round(fg.width * scale)))
    new_h = max(1, int(round(fg.height * scale)))
    resized = fg.resize((new_w, new_h), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    canvas.paste(resized, ((canvas_size - new_w) // 2, (canvas_size - new_h) // 2), resized)
    return canvas


def make_radial_gradient_rgb(
    size: int,
    center_rgb: Tuple[int, int, int],
    edge_rgb: Tuple[int, int, int],
) -> Image.Image:
    """Create a smooth square radial gradient RGB backdrop."""
    y, x = np.ogrid[:size, :size]
    cx = cy = (size - 1) / 2.0
    dist = np.sqrt((x - cx) ** 2 + (y - cy) ** 2) / (math.sqrt(2.0) * cx)
    t = np.clip(dist, 0.0, 1.0)[:, :, None]
    c0 = np.array(center_rgb, dtype=np.float32)
    c1 = np.array(edge_rgb, dtype=np.float32)
    arr = np.clip(c0 * (1.0 - t) + c1 * t, 0, 255).astype(np.uint8)
    return Image.fromarray(arr, mode="RGB")


def generate_desktop_icons(silhouette: Image.Image) -> None:
    """Generate Windows/Linux/macOS transparent shaped app_icon.png and multi-res app_icon.ico."""
    PC_VENDOR_DIR.mkdir(parents=True, exist_ok=True)
    desktop_png = place_centered_rgba(silhouette, 1024, fill_ratio=0.92)
    png_out = PC_VENDOR_DIR / "app_icon.png"
    ico_out = PC_VENDOR_DIR / "app_icon.ico"

    desktop_png.save(png_out, format="PNG")
    desktop_png.save(
        ico_out,
        format="ICO",
        sizes=[(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (24, 24), (16, 16)],
    )
    print(f"[OK] Desktop PNG: {png_out.relative_to(ROOT)} (1024x1024 RGBA)")
    print(f"[OK] Windows ICO: {ico_out.relative_to(ROOT)} (7 RGBA frames: 16..256)")


def generate_android_icons(silhouette: Image.Image) -> None:
    """Generate Android adaptive foreground PNG (drawable-nodpi) and legacy mipmaps."""
    # Remove old thin-stroke XML foreground so drawable-nodpi/ic_launcher_foreground.png takes precedence
    old_fg_xml = ANDROID_RES_DIR / "drawable" / "ic_launcher_foreground.xml"
    if old_fg_xml.exists():
        old_fg_xml.unlink()
        print(f"[OK] Removed legacy vector {old_fg_xml.relative_to(ROOT)}")

    nodpi_dir = ANDROID_RES_DIR / "drawable-nodpi"
    nodpi_dir.mkdir(parents=True, exist_ok=True)
    # 62% fill ratio keeps the full shaped badge comfortably inside Android's 66/108dp safe circle
    adaptive_fg = place_centered_rgba(silhouette, 432, fill_ratio=0.62)
    fg_out = nodpi_dir / "ic_launcher_foreground.png"
    adaptive_fg.save(fg_out, format="PNG")
    print(f"[OK] Android adaptive foreground: {fg_out.relative_to(ROOT)} (432x432 RGBA)")

    # Legacy raster mipmaps for API 24-25 launchers
    mipmaps = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, px in mipmaps.items():
        mdir = ANDROID_RES_DIR / folder
        mdir.mkdir(parents=True, exist_ok=True)

        # Standard shaped transparent icon
        std_icon = place_centered_rgba(silhouette, px, fill_ratio=0.88)
        std_icon.save(mdir / "ic_launcher.png", format="PNG")

        # Circular round badge with deep navy disc + centered shaped emblem
        disc = make_radial_gradient_rgb(px, (15, 45, 82), (7, 22, 46)).convert("RGBA")
        mask = Image.new("L", (px, px), 0)
        draw = ImageDraw.Draw(mask)
        draw.ellipse([0, 0, px - 1, px - 1], fill=255)
        disc.putalpha(mask)
        fg_round = place_centered_rgba(silhouette, px, fill_ratio=0.74)
        round_icon = Image.alpha_composite(disc, fg_round)
        round_icon.save(mdir / "ic_launcher_round.png", format="PNG")

    print("[OK] Android legacy mipmaps generated (mdpi..xxxhdpi, standard + round)")


def generate_ios_icons(silhouette: Image.Image) -> None:
    """Generate iOS 1024x1024 strictly opaque RGB PNGs (Light, Dark, Tinted)."""
    IOS_ICONSET_DIR.mkdir(parents=True, exist_ok=True)

    # 1. Default Light AppIcon.png (luminous soft sky-white radial backdrop)
    light_bg = make_radial_gradient_rgb(1024, (255, 255, 255), (226, 245, 255)).convert("RGBA")
    light_fg = place_centered_rgba(silhouette, 1024, fill_ratio=0.82)
    Image.alpha_composite(light_bg, light_fg).convert("RGB").save(
        IOS_ICONSET_DIR / "AppIcon.png", format="PNG"
    )

    # 2. Dark Mode AppIcon-Dark.png (deep midnight navy radial backdrop + subtle cyan glow)
    dark_bg = make_radial_gradient_rgb(1024, (16, 48, 86), (7, 15, 30)).convert("RGBA")
    glow = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glow)
    gdraw.ellipse([180, 180, 844, 844], fill=(0, 212, 255, 42))
    glow = glow.filter(ImageFilter.GaussianBlur(radius=90))
    dark_comp = Image.alpha_composite(dark_bg, glow)
    dark_fg = place_centered_rgba(silhouette, 1024, fill_ratio=0.82)
    Image.alpha_composite(dark_comp, dark_fg).convert("RGB").save(
        IOS_ICONSET_DIR / "AppIcon-Dark.png", format="PNG"
    )

    # 3. Tinted AppIcon-Tinted.png (high-contrast monochrome silver-white on dark neutral charcoal)
    tinted_bg = Image.new("RGBA", (1024, 1024), (18, 21, 28, 255))
    tinted_fg = place_centered_rgba(silhouette, 1024, fill_ratio=0.80)
    gray = tinted_fg.convert("L")
    gray_arr = np.array(gray, dtype=np.float32)
    # Boost tonal contrast for crisp iOS tinted rendering
    gray_boosted = np.clip((gray_arr - 40.0) * 1.35 + 90.0, 120.0, 255.0).astype(np.uint8)
    tinted_mono = Image.merge(
        "RGBA",
        (
            Image.fromarray(gray_boosted),
            Image.fromarray(gray_boosted),
            Image.fromarray(gray_boosted),
            tinted_fg.getchannel("A"),
        ),
    )
    Image.alpha_composite(tinted_bg, tinted_mono).convert("RGB").save(
        IOS_ICONSET_DIR / "AppIcon-Tinted.png", format="PNG"
    )

    print("[OK] iOS AppIcon.appiconset generated (AppIcon.png, AppIcon-Dark.png, AppIcon-Tinted.png — 1024x1024 RGB)")


def verify_outputs() -> None:
    """Assert every generated icon has the exact expected dimensions, mode, and transparency."""
    assert MASTER_PNG.exists(), f"Missing {MASTER_PNG}"
    assert not RAW_JPG.exists(), f"Expected {RAW_JPG} to be moved/deleted"

    # Desktop PNG checks
    dp = Image.open(PC_VENDOR_DIR / "app_icon.png")
    assert dp.size == (1024, 1024) and dp.mode == "RGBA", f"Invalid desktop PNG: {dp.size} {dp.mode}"
    for cx, cy in [(0, 0), (1023, 0), (0, 1023), (1023, 1023)]:
        assert dp.getpixel((cx, cy))[3] == 0, f"Desktop PNG corner ({cx},{cy}) is not transparent!"
    assert dp.getpixel((512, 512))[3] == 255, "Desktop PNG center pixel is unexpectedly transparent!"

    # Windows ICO check
    ico_path = PC_VENDOR_DIR / "app_icon.ico"
    assert ico_path.exists() and ico_path.stat().st_size > 10_000, "Windows ICO missing or too small"
    ico = Image.open(ico_path)
    assert ico.format == "ICO", f"Expected ICO format, got {ico.format}"

    # Android checks
    fg = Image.open(ANDROID_RES_DIR / "drawable-nodpi" / "ic_launcher_foreground.png")
    assert fg.size == (432, 432) and fg.mode == "RGBA", f"Invalid Android foreground: {fg.size} {fg.mode}"
    assert not (ANDROID_RES_DIR / "drawable" / "ic_launcher_foreground.xml").exists(), "Old foreground XML still exists"
    for folder, px in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
        for fname in ("ic_launcher.png", "ic_launcher_round.png"):
            im = Image.open(ANDROID_RES_DIR / folder / fname)
            assert im.size == (px, px) and im.mode == "RGBA", f"Invalid {folder}/{fname}: {im.size} {im.mode}"

    # iOS checks (strictly opaque RGB!)
    for fname in ("AppIcon.png", "AppIcon-Dark.png", "AppIcon-Tinted.png"):
        im = Image.open(IOS_ICONSET_DIR / fname)
        assert im.size == (1024, 1024) and im.mode == "RGB", f"iOS {fname} must be 1024x1024 RGB, got {im.size} {im.mode}"

    print("\n[SUCCESS] All icon verification assertions passed!")


def main() -> None:
    master = migrate_logo_to_assets()
    silhouette = extract_clean_rgba_silhouette(master)
    generate_desktop_icons(silhouette)
    generate_android_icons(silhouette)
    generate_ios_icons(silhouette)
    verify_outputs()


if __name__ == "__main__":
    main()
