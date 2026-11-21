#!/usr/bin/env python3
"""
build_macos.py — Build persistent standalone macOS Application bundle (.app) and DMG for Air Speaker.

Output:
  - dist/Air Speaker.app (macOS Application bundle)
  - ../AirSpeaker-v1-macOS.dmg (Drag-and-Drop Disk Image with /Applications symlink)
"""

import ctypes.util
import os
import plistlib
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT_DIR = Path(os.environ.get("AIRSPEAKER_OUT_DIR", str(ROOT.parent))).resolve()
OUT_DIR.mkdir(parents=True, exist_ok=True)
VENV = ROOT / ".venv-macos"
VENDOR = ROOT / "vendor"
SRC_MAIN = ROOT / "src" / "main.py"


def run(cmd: list, **kwargs) -> subprocess.CompletedProcess:
    print(f"$ {' '.join(str(c) for c in cmd)}", flush=True)
    result = subprocess.run(cmd, **kwargs)
    if result.returncode != 0:
        print(f"ERROR: command exited with code {result.returncode}", flush=True)
        sys.exit(result.returncode)
    return result


def resolve_app_version() -> str:
    env_ver = (os.environ.get("AIRSPEAKER_VERSION") or "").strip().lstrip("vV")
    if env_ver:
        return env_ver
    ref_name = (os.environ.get("GITHUB_REF_NAME") or "").strip()
    if ref_name.startswith(("v", "V")) and len(ref_name) > 1:
        return ref_name[1:]
    try:
        tag = subprocess.check_output(
            ["git", "describe", "--tags", "--exact-match"], stderr=subprocess.DEVNULL
        ).decode("utf-8").strip()
        if tag.startswith(("v", "V")):
            return tag[1:]
    except Exception:
        pass
    return "1.0.2"


def write_build_info() -> str:
    env_sha = os.environ.get("GITHUB_SHA")
    sha = "dev"
    if env_sha:
        sha = env_sha[:7]
    else:
        try:
            out = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], stderr=subprocess.DEVNULL)
            sha = out.decode("utf-8").strip()
        except Exception:
            sha = "dev"
    app_ver = resolve_app_version()
    build_info_path = ROOT / "src" / "_build_info.py"
    build_info_path.write_text(
        f'APP_VERSION = "{app_ver}"
BUILD_SHA = "{sha}"
',
        encoding="utf-8",
    )
    print(f"Stamped APP_VERSION = '{app_ver}', BUILD_SHA = '{sha}' -> {build_info_path}", flush=True)
    return sha


def find_system_libopus() -> Path | None:
    candidates = [
        Path("/opt/homebrew/lib/libopus.0.dylib"),
        Path("/opt/homebrew/lib/libopus.dylib"),
        Path("/usr/local/lib/libopus.0.dylib"),
        Path("/usr/local/lib/libopus.dylib"),
    ]
    for c in candidates:
        if c.exists():
            return c
    found = ctypes.util.find_library("opus")
    if found and Path(found).exists():
        return Path(found)
    return None


def generate_icns(icon_png: Path, icns_path: Path) -> None:
    """Generate macOS .icns file using sips and iconutil if available."""
    if icns_path.exists():
        return
    if shutil.which("sips") and shutil.which("iconutil"):
        iconset = VENDOR / "app_icon.iconset"
        iconset.mkdir(parents=True, exist_ok=True)
        sizes = [16, 32, 64, 128, 256, 512]
        for sz in sizes:
            subprocess.run(["sips", "-z", str(sz), str(sz), str(icon_png), "--out", str(iconset / f"icon_{sz}x{sz}.png")], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            subprocess.run(["sips", "-z", str(sz * 2), str(sz * 2), str(icon_png), "--out", str(iconset / f"icon_{sz}x{sz}@2x.png")], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["iconutil", "-c", "icns", str(iconset), "-o", str(icns_path)], check=False)
        shutil.rmtree(iconset, ignore_errors=True)
        if icns_path.exists():
            print(f"Generated macOS icns icon: {icns_path}", flush=True)


def main() -> None:
    os.chdir(ROOT)
    VENDOR.mkdir(parents=True, exist_ok=True)

    # 1. Stamp BUILD_SHA
    write_build_info()

    # 2. Virtual environment setup (when on macOS)
    python = sys.executable
    pip = None
    if sys.platform == "darwin":
        if not VENV.exists():
            print("Creating macOS virtual environment...", flush=True)
            run([sys.executable, "-m", "venv", "--system-site-packages", str(VENV)])
        python = str(VENV / "bin" / "python")
        pip = str(VENV / "bin" / "pip")

        run([pip, "install", "--upgrade", "pip", "-q"])
        mac_pkgs = [
            "numpy>=1.26.0",
            "psutil>=5.9.8",
            "Pillow>=10.3.0",
            "pystray>=0.19.5",
            "pyinstaller>=6.6.0",
            "qrcode>=7.4.2",
        ]
        run([pip, "install"] + mac_pkgs)

        build_env = os.environ.copy()
        brew_prefix = ""
        if shutil.which("brew"):
            try:
                brew_prefix = subprocess.check_output(
                    ["brew", "--prefix", "portaudio"], text=True, stderr=subprocess.DEVNULL
                ).strip()
            except Exception:
                brew_prefix = ""
        if not brew_prefix:
            for candidate in ("/opt/homebrew", "/usr/local"):
                if Path(candidate, "include", "portaudio.h").exists():
                    brew_prefix = candidate
                    break
        if brew_prefix:
            inc = str(Path(brew_prefix) / "include")
            lib = str(Path(brew_prefix) / "lib")
            build_env["CFLAGS"] = f"-I{inc} {build_env.get('CFLAGS', '')}".strip()
            build_env["LDFLAGS"] = f"-L{lib} {build_env.get('LDFLAGS', '')}".strip()
            print(f"Using PortAudio headers/libs from: {brew_prefix}", flush=True)

        run([pip, "install", "pyaudio"], env=build_env)
        subprocess.run([pip, "install", "soxr"], env=build_env, check=False)

    # 3. Locate system libopus
    opus_dylib = VENDOR / "libopus.dylib"
    if not opus_dylib.exists():
        sys_opus = find_system_libopus()
        if sys_opus:
            shutil.copy2(sys_opus, opus_dylib)
            print(f"Bundling system libopus: {sys_opus} -> {opus_dylib}")

    # 4. Generate icons
    icon_png = VENDOR / "app_icon.png"
    if not icon_png.exists():
        gen_script = (
            "import sys; "
            f"sys.path.insert(0, r'{ROOT / 'src'}'); "
            "from ui import _make_tray_image; "
            f"img = _make_tray_image(256); img.save(r'{icon_png}', format='PNG')"
        )
        subprocess.run([str(python), "-c", gen_script], check=False)

    icns_path = VENDOR / "app_icon.icns"
    if icon_png.exists():
        generate_icns(icon_png, icns_path)

    # 5. Build clean self-contained --onedir --windowed bundle named "Air Speaker.app"
    print("\n--- Building macOS Application Bundle (dist/Air Speaker.app) ---", flush=True)
    app_path = ROOT / "dist" / "Air Speaker.app"
    if app_path.exists():
        shutil.rmtree(app_path)

    pyinstaller_args = [
        str(python), "-m", "PyInstaller",
        "--windowed",
        "--noconsole",
        "--onedir",
        "--name", "Air Speaker",
        "--osx-bundle-identifier", "com.hasan.airspeaker",
        "--distpath", str(ROOT / "dist"),
        "--workpath", str(ROOT / "build-macos"),
        "--specpath", str(ROOT),
        "--paths", str(ROOT / "src"),
        "--hidden-import", "PIL._tkinter_finder",
    ]
    if opus_dylib.exists():
        pyinstaller_args += ["--add-binary", f"{opus_dylib}:."]
    if icns_path.exists():
        pyinstaller_args += ["--icon", str(icns_path)]
    elif icon_png.exists():
        pyinstaller_args += ["--icon", str(icon_png)]

    pyinstaller_args.append(str(SRC_MAIN))
    run(pyinstaller_args)

    if not app_path.exists():
        print(f"ERROR: Application bundle was not created at {app_path}")
        sys.exit(1)

    # 6. Patch Air Speaker.app/Contents/Info.plist
    plist_path = app_path / "Contents" / "Info.plist"
    if plist_path.exists():
        try:
            with open(plist_path, "rb") as fp:
                pl = plistlib.load(fp)
            pl["CFBundleDisplayName"] = "Air Speaker"
            pl["CFBundleName"] = "Air Speaker"
            pl["CFBundleShortVersionString"] = resolve_app_version()
            pl["NSHighResolutionCapable"] = True
            pl["NSLocalNetworkUsageDescription"] = (
                "Air Speaker uses your local network (UDP 5004, 5005) for ultra-low-latency audio streaming."
            )
            pl["NSMicrophoneUsageDescription"] = (
                "Air Speaker accesses audio devices to stream system or microphone audio."
            )
            with open(plist_path, "wb") as fp:
                plistlib.dump(pl, fp)
            print("Patched Air Speaker.app/Contents/Info.plist successfully.", flush=True)
        except Exception as exc:
            print(f"Warning: Could not patch Info.plist: {exc}", flush=True)

    # 7. Ad-hoc code-sign the bundle so macOS accepts it persistently across restarts
    if shutil.which("codesign"):
        print("Ad-hoc code-signing bundle for persistent macOS execution...", flush=True)
        subprocess.run(["codesign", "--force", "--deep", "--sign", "-", str(app_path)], check=False)

    # 8. Build DMG with /Applications drag-and-drop symlink
    hdiutil = shutil.which("hdiutil")
    dmg_out = OUT_DIR / "AirSpeaker-v1-macOS.dmg"
    if hdiutil:
        print("\nCreating Drag-and-Drop DMG with /Applications symlink...", flush=True)
        staging_dir = ROOT / "dist" / "dmg_staging"
        if staging_dir.exists():
            shutil.rmtree(staging_dir)
        staging_dir.mkdir(parents=True, exist_ok=True)

        shutil.copytree(app_path, staging_dir / "Air Speaker.app", symlinks=True)
        try:
            os.symlink("/Applications", staging_dir / "Applications")
        except Exception as exc:
            print(f"Warning: Could not create /Applications symlink: {exc}")

        if dmg_out.exists():
            dmg_out.unlink()

        run([
            "hdiutil", "create",
            "-volname", "Air Speaker",
            "-srcfolder", str(staging_dir),
            "-ov",
            "-format", "UDZO",
            str(dmg_out)
        ])
        shutil.rmtree(staging_dir, ignore_errors=True)

        if dmg_out.exists():
            size_mb = dmg_out.stat().st_size / (1024 * 1024)
            print(f"SUCCESS: Persistent macOS DMG installer ready at {dmg_out} ({size_mb:.1f} MB)", flush=True)
    else:
        print("Notice: 'hdiutil' is not available on this platform. Application bundle is at dist/Air Speaker.app")


if __name__ == "__main__":
    main()
