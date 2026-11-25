#!/usr/bin/env python3
"""
generate_icons.py — Master Vector and Multi-Platform Asset Generator for AirSpeaker v1.0.0.

Produces:
1. Android Adaptive & Themed Icons:
   - ic_launcher_background.xml (solid #0B2027)
   - ic_launcher_foreground.xml (108dp x 108dp VectorDrawable, #A8E6F0)
   - ic_launcher_monochrome.xml (108dp x 108dp VectorDrawable, #FFFFFF)
   - ic_stat_airspeaker.xml (24dp x 24dp notification VectorDrawable)
   - mipmap-anydpi-v26/ic_launcher.xml & ic_launcher_round.xml
   - Legacy launcher PNGs for mdpi (48px), hdpi (72px), xhdpi (96px), xxhdpi (144px), xxxhdpi (192px)
2. PC (Windows, macOS & Linux):
   - app_icon.png (512x512 rounded default icon with soft ice-blue glow)
   - app_icon.ico (multi-resolution 256, 128, 64, 48, 32, 16px)
3. Apple iOS (Asset Catalog):
   - Assets.xcassets/Contents.json
   - Assets.xcassets/AppIcon.appiconset/Contents.json
   - AppIcon.png (1024x1024 default: #0B2027 + #A8E6F0)
   - AppIcon-Dark.png (1024x1024 dark: #08161B + #A8E6F0)
   - AppIcon-Tinted.png (1024x1024 tinted: #000000 + #FFFFFF)
"""

import math
import os
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
VENDOR_DIR = REPO_ROOT / "airspeaker-pc" / "vendor"
ANDROID_RES = REPO_ROOT / "AirSpeakerAndroid" / "app" / "src" / "main" / "res"
IOS_ASSETS = REPO_ROOT / "AirSpeakeriOS" / "AirSpeaker" / "Assets.xcassets"

BG_COLOR_DEFAULT = (11, 32, 39, 255)      # #0B2027
BG_COLOR_DARK    = (8, 22, 27, 255)       # #08161B
BG_COLOR_TINTED  = (0, 0, 0, 255)         # #000000

FG_COLOR_DEFAULT = (168, 230, 240, 255)   # #A8E6F0
FG_COLOR_WHITE   = (255, 255, 255, 255)   # #FFFFFF


def render_artwork_layer(size: int, fg_color, add_glow: bool = False) -> Image.Image:
    """Render the vector artwork on a transparent canvas at the given size with 4x supersampling."""
    ss = 4
    w = size * ss
    h = size * ss
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    # Base scale maps 108dp -> w with 0.94 safe-zone factor
    s = (w / 108.0) * 0.94
    ox = (w / 2.0) - (2.8 * (w / 108.0))
    oy = (h / 2.0) - (0.5 * (h / 108.0))

    lw = int(round(2.4 * s))

    # 1. Desktop Monitor Screen
    mon_x0 = -28.0 * s + ox
    mon_y0 = -14.0 * s + oy
    mon_x1 = -5.0 * s + ox
    mon_y1 = 8.0 * s + oy
    r_mon = int(round(3.5 * s))
    d.rounded_rectangle([mon_x0, mon_y0, mon_x1, mon_y1], radius=r_mon, outline=fg_color, width=lw)

    # Monitor chin camera/dot
    dot_cx = (-16.5) * s + ox
    dot_cy = 5.5 * s + oy
    r_dot = 0.9 * s
    d.ellipse([dot_cx - r_dot, dot_cy - r_dot, dot_cx + r_dot, dot_cy + r_dot], fill=fg_color)

    # Monitor neck
    neck_x = dot_cx
    neck_bottom_y = mon_y1 + 2.8 * s
    d.line([(neck_x, mon_y1), (neck_x, neck_bottom_y)], fill=fg_color, width=lw)

    # Monitor trapezoid base
    stand_base_y = neck_bottom_y + 2.5 * s
    d.line([(neck_x - 6.0 * s, stand_base_y), (neck_x + 6.0 * s, stand_base_y)], fill=fg_color, width=lw)
    d.line([(neck_x - 2.5 * s, neck_bottom_y), (neck_x - 6.0 * s, stand_base_y)], fill=fg_color, width=lw)
    d.line([(neck_x + 2.5 * s, neck_bottom_y), (neck_x + 6.0 * s, stand_base_y)], fill=fg_color, width=lw)

    # 2. Smartphone Outline
    phone_x0 = 10.0 * s + ox
    phone_y0 = -16.0 * s + oy
    phone_x1 = 23.0 * s + ox
    phone_y1 = 15.0 * s + oy
    r_phone = int(round(3.2 * s))
    d.rounded_rectangle([phone_x0, phone_y0, phone_x1, phone_y1], radius=r_phone, outline=fg_color, width=lw)

    # Phone speaker notch
    phone_cx = (phone_x0 + phone_x1) / 2.0
    notch_y = phone_y0 + 3.5 * s
    notch_w = 2.4 * s
    d.line([(phone_cx - notch_w, notch_y), (phone_cx + notch_w, notch_y)], fill=fg_color, width=int(round(lw * 0.8)))

    # 3. Waveform Bridge
    wave_y = -3.0 * s + oy
    pts = [
        ((-13.0) * s + ox, wave_y),
        ((-5.0) * s + ox, wave_y),
        ((-3.5) * s + ox, wave_y - 4.0 * s),
        ((-1.5) * s + ox, wave_y + 5.0 * s),
        ((0.5) * s + ox, wave_y - 10.0 * s),
        ((2.5) * s + ox, wave_y + 11.0 * s),
        ((4.5) * s + ox, wave_y - 6.0 * s),
        ((6.5) * s + ox, wave_y + 4.0 * s),
        ((8.0) * s + ox, wave_y),
        ((16.5) * s + ox, wave_y),
    ]
    for i in range(len(pts) - 1):
        d.line([pts[i], pts[i+1]], fill=fg_color, width=lw, joint="round")

    # 4. Sound Arcs radiating from right edge of phone
    arc_cx = phone_x1
    arc_cy = wave_y
    for r_arc, sweep in [(3.8 * s, 35), (7.1 * s, 40), (10.4 * s, 45)]:
        d.arc([arc_cx - r_arc, arc_cy - r_arc, arc_cx + r_arc, arc_cy + r_arc], -sweep, sweep, fill=fg_color, width=lw)

    # Optional soft glow for hero icons
    if add_glow:
        # Create a blurred glow layer
        glow = layer.filter(ImageFilter.GaussianBlur(radius=int(round(6.0 * ss))))
        # Tint glow slightly with alpha
        glow_r, glow_g, glow_b, glow_a = glow.split()
        glow_a = glow_a.point(lambda p: int(p * 0.45))
        glow.putalpha(glow_a)
        # Composite layer onto glow
        comp = Image.alpha_composite(glow, layer)
        res = comp.resize((size, size), Image.Resampling.LANCZOS)
    else:
        res = layer.resize((size, size), Image.Resampling.LANCZOS)

    return res


def create_composed_icon(size: int, bg_color, fg_color, is_round: bool = False, corner_radius: int = 0, add_glow: bool = False) -> Image.Image:
    """Generate a complete composed icon with background and artwork."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(canvas)

    if is_round:
        d.ellipse([0, 0, size - 1, size - 1], fill=bg_color)
    elif corner_radius > 0:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=corner_radius, fill=bg_color)
    else:
        d.rectangle([0, 0, size, size], fill=bg_color)

    artwork = render_artwork_layer(size, fg_color, add_glow=add_glow)
    return Image.alpha_composite(canvas, artwork)


def generate_android_vector_drawables():
    """Write Android VectorDrawable XML files."""
    drawable_dir = ANDROID_RES / "drawable"
    drawable_dir.mkdir(parents=True, exist_ok=True)

    # 1. Background
    bg_xml = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#0B2027"
        android:pathData="M0,0h108v108h-108z" />
</vector>
"""
    (drawable_dir / "ic_launcher_background.xml").write_text(bg_xml, encoding="utf-8")

    # 2. Foreground (#A8E6F0)
    fg_xml = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Monitor Screen -->
    <path
        android:pathData="M 28.17,40.34 H 43.21 A 3.29,3.29 0 0 1 46.50,43.63 V 57.73 A 3.29,3.29 0 0 1 43.21,61.02 H 28.17 A 3.29,3.29 0 0 1 24.88,57.73 V 43.63 A 3.29,3.29 0 0 1 28.17,40.34 Z"
        android:strokeColor="#A8E6F0"
        android:strokeWidth="2.25"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <!-- Monitor Chin Dot -->
    <path
        android:pathData="M 35.69,57.82 A 0.85,0.85 0 1 0 35.69,59.52 A 0.85,0.85 0 1 0 35.69,57.82 Z"
        android:fillColor="#A8E6F0" />
    <!-- Monitor Neck and Base -->
    <path
        android:pathData="M 35.69,61.02 V 63.65 M 33.34,63.65 L 30.05,66.00 H 41.33 L 38.04,63.65"
        android:strokeColor="#A8E6F0"
        android:strokeWidth="2.25"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <!-- Smartphone Outline -->
    <path
        android:pathData="M 63.61,38.46 H 69.81 A 3.01,3.01 0 0 1 72.82,41.47 V 64.59 A 3.01,3.01 0 0 1 69.81,67.60 H 63.61 A 3.01,3.01 0 0 1 60.60,64.59 V 41.47 A 3.01,3.01 0 0 1 63.61,38.46 Z"
        android:strokeColor="#A8E6F0"
        android:strokeWidth="2.25"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <!-- Smartphone Speaker Notch -->
    <path
        android:pathData="M 64.36,41.75 H 69.06"
        android:strokeColor="#A8E6F0"
        android:strokeWidth="1.8"
        android:strokeLineCap="round" />
    <!-- Audio Waveform Bridge -->
    <path
        android:pathData="M 38.98,50.68 L 46.50,50.68 L 47.91,46.92 L 49.79,55.38 L 51.67,41.28 L 53.55,61.02 L 55.43,45.04 L 57.31,54.44 L 58.72,50.68 L 66.71,50.68"
        android:strokeColor="#A8E6F0"
        android:strokeWidth="2.25"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <!-- Sound Waves / Arcs -->
    <path
        android:pathData="M 75.90,48.52 A 3.76,3.76 0 0 1 75.90,52.84 M 78.22,46.15 A 7.05,7.05 0 0 1 78.22,55.21 M 80.13,43.37 A 10.34,10.34 0 0 1 80.13,57.99"
        android:strokeColor="#A8E6F0"
        android:strokeWidth="2.25"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
"""
    (drawable_dir / "ic_launcher_foreground.xml").write_text(fg_xml, encoding="utf-8")

    # 3. Monochrome (#FFFFFF for Android 13+ Material You themed icons)
    mono_xml = fg_xml.replace("#A8E6F0", "#FFFFFF")
    (drawable_dir / "ic_launcher_monochrome.xml").write_text(mono_xml, encoding="utf-8")

    # 4. Status Bar Notification Icon (24dp x 24dp, scaled by 24/108 = 0.22222)
    stat_xml = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!-- Monitor Screen -->
    <path
        android:pathData="M 6.26,8.96 H 9.60 A 0.73,0.73 0 0 1 10.33,9.70 V 12.83 A 0.73,0.73 0 0 1 9.60,13.56 H 6.26 A 0.73,0.73 0 0 1 5.53,12.83 V 9.70 A 0.73,0.73 0 0 1 6.26,8.96 Z"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.1"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <!-- Monitor Chin Dot -->
    <path
        android:pathData="M 7.93,12.85 A 0.19,0.19 0 1 0 7.93,13.23 A 0.19,0.19 0 1 0 7.93,12.85 Z"
        android:fillColor="#FFFFFF" />
    <!-- Monitor Neck and Base -->
    <path
        android:pathData="M 7.93,13.56 V 14.14 M 7.41,14.14 L 6.68,14.67 H 9.18 L 8.45,14.14"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.1"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <!-- Smartphone Outline -->
    <path
        android:pathData="M 14.14,8.55 H 15.51 A 0.67,0.67 0 0 1 16.18,9.22 V 14.35 A 0.67,0.67 0 0 1 15.51,15.02 H 14.14 A 0.67,0.67 0 0 1 13.47,14.35 V 9.22 A 0.67,0.67 0 0 1 14.14,8.55 Z"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.1"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <!-- Audio Waveform Bridge -->
    <path
        android:pathData="M 8.66,11.26 L 10.33,11.26 L 10.65,10.43 L 11.06,12.31 L 11.48,9.17 L 11.90,13.56 L 12.32,10.01 L 12.74,12.10 L 13.05,11.26 L 14.82,11.26"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.1"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <!-- Sound Waves / Arcs -->
    <path
        android:pathData="M 16.87,10.78 A 0.84,0.84 0 0 1 16.87,11.74 M 17.38,10.26 A 1.57,1.57 0 0 1 17.38,12.27 M 17.81,9.64 A 2.30,2.30 0 0 1 17.81,12.89"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="1.1"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
"""
    (drawable_dir / "ic_stat_airspeaker.xml").write_text(stat_xml, encoding="utf-8")

    # 5. Adaptive icon XML (mipmap-anydpi-v26)
    anydpi_dir = ANDROID_RES / "mipmap-anydpi-v26"
    anydpi_dir.mkdir(parents=True, exist_ok=True)
    adaptive_xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""
    (anydpi_dir / "ic_launcher.xml").write_text(adaptive_xml, encoding="utf-8")
    (anydpi_dir / "ic_launcher_round.xml").write_text(adaptive_xml, encoding="utf-8")
    print("  -> Generated Android XML vector drawables & adaptive icon manifests")


def generate_android_legacy_pngs():
    """Generate legacy launcher PNGs for all densities."""
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for folder, px in densities.items():
        out_dir = ANDROID_RES / folder
        out_dir.mkdir(parents=True, exist_ok=True)

        # Standard squircle/rounded icon (corner radius ~22%)
        cr = int(round(px * 0.22))
        sq_icon = create_composed_icon(px, BG_COLOR_DEFAULT, FG_COLOR_DEFAULT, corner_radius=cr)
        sq_icon.save(out_dir / "ic_launcher.png", format="PNG")

        # Round icon (circle mask)
        rd_icon = create_composed_icon(px, BG_COLOR_DEFAULT, FG_COLOR_DEFAULT, is_round=True)
        rd_icon.save(out_dir / "ic_launcher_round.png", format="PNG")

    print("  -> Generated Android legacy mipmap PNGs (mdpi through xxxhdpi)")


def generate_pc_icons():
    """Generate Windows, macOS & Linux icon deliverables."""
    VENDOR_DIR.mkdir(parents=True, exist_ok=True)

    # 1. app_icon.png (512x512, rounded-square with subtle glow)
    cr = int(round(512 * 0.22))
    png_512 = create_composed_icon(512, BG_COLOR_DEFAULT, FG_COLOR_DEFAULT, corner_radius=cr, add_glow=True)
    png_512.save(VENDOR_DIR / "app_icon.png", format="PNG")

    # 2. app_icon.ico (multi-resolution)
    ico_sizes = [(256, 256), (128, 128), (64, 64), (48, 48), (32, 32), (16, 16)]
    ico_images = []
    for s_w, s_h in ico_sizes:
        cr_s = int(round(s_w * 0.22))
        ico_images.append(create_composed_icon(s_w, BG_COLOR_DEFAULT, FG_COLOR_DEFAULT, corner_radius=cr_s, add_glow=(s_w >= 64)))
    ico_images[0].save(
        VENDOR_DIR / "app_icon.ico",
        format="ICO",
        sizes=ico_sizes,
        append_images=ico_images[1:]
    )
    print("  -> Generated PC app_icon.png (512x512) and app_icon.ico (16-256px)")


def generate_ios_asset_catalog():
    """Generate Apple iOS Asset Catalog AppIcon.appiconset."""
    appicon_dir = IOS_ASSETS / "AppIcon.appiconset"
    appicon_dir.mkdir(parents=True, exist_ok=True)

    # 1. Assets.xcassets/Contents.json
    root_contents = """{
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
"""
    (IOS_ASSETS / "Contents.json").write_text(root_contents, encoding="utf-8")

    # 2. AppIcon.png (Default: #0B2027 + #A8E6F0)
    app_icon = create_composed_icon(1024, BG_COLOR_DEFAULT, FG_COLOR_DEFAULT, add_glow=True)
    app_icon.save(appicon_dir / "AppIcon.png", format="PNG")

    # 3. AppIcon-Dark.png (Dark appearance: #08161B + #A8E6F0)
    app_icon_dark = create_composed_icon(1024, BG_COLOR_DARK, FG_COLOR_DEFAULT, add_glow=True)
    app_icon_dark.save(appicon_dir / "AppIcon-Dark.png", format="PNG")

    # 4. AppIcon-Tinted.png (iOS 18+ Tinted: #000000 + #FFFFFF)
    app_icon_tinted = create_composed_icon(1024, BG_COLOR_TINTED, FG_COLOR_WHITE, add_glow=False)
    app_icon_tinted.save(appicon_dir / "AppIcon-Tinted.png", format="PNG")

    # 5. AppIcon.appiconset/Contents.json
    appicon_contents = """{
  "images" : [
    {
      "filename" : "AppIcon.png",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    },
    {
      "appearances" : [
        {
          "appearance" : "luminosity",
          "value" : "dark"
        }
      ],
      "filename" : "AppIcon-Dark.png",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    },
    {
      "appearances" : [
        {
          "appearance" : "luminosity",
          "value" : "tinted"
        }
      ],
      "filename" : "AppIcon-Tinted.png",
      "idiom" : "universal",
      "platform" : "ios",
      "size" : "1024x1024"
    }
  ],
  "info" : {
    "author" : "xcode",
    "version" : 1
  }
}
"""
    (appicon_dir / "Contents.json").write_text(appicon_contents, encoding="utf-8")
    print("  -> Generated iOS AppIcon.appiconset (Default, Dark, and Tinted 1024x1024)")


def main():
    print("=== Generating AirSpeaker v1.0.0 Multi-Platform Icons ===")
    generate_android_vector_drawables()
    generate_android_legacy_pngs()
    generate_pc_icons()
    generate_ios_asset_catalog()
    print("=== All AirSpeaker v1.0.0 Icon Assets Generated Successfully! ===")


if __name__ == "__main__":
    main()
