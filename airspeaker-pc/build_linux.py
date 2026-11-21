#!/usr/bin/env python3
"""
build_linux.py — Build persistent standalone Linux application bundle and tarball for Air Speaker.

Output:
  - dist/AirSpeaker/ (Self-contained --onedir Linux application bundle)
  - ../AirSpeaker-v1-Linux-x86_64.tar.gz (Gzip tarball of the bundle for release)
"""

import ctypes.util
import os
import shutil
import stat
import subprocess
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT_DIR = Path(os.environ.get("AIRSPEAKER_OUT_DIR", str(ROOT.parent))).resolve()
OUT_DIR.mkdir(parents=True, exist_ok=True)
VENV = ROOT / ".venv-linux"
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
    return "1.2.0"


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
        f'APP_VERSION = "{app_ver}"\nBUILD_SHA = "{sha}"\n',
        encoding="utf-8",
    )
    print(f"Stamped APP_VERSION = '{app_ver}', BUILD_SHA = '{sha}' -> {build_info_path}", flush=True)
    return sha


def find_system_libopus() -> Path | None:
    candidates = [
        Path("/usr/lib/libopus.so.0"),
        Path("/usr/lib64/libopus.so.0"),
        Path("/usr/lib/x86_64-linux-gnu/libopus.so.0"),
        Path("/usr/lib/aarch64-linux-gnu/libopus.so.0"),
    ]
    for c in candidates:
        if c.exists():
            return c
    found = ctypes.util.find_library("opus")
    if found and Path(found).exists():
        return Path(found)
    return None


def main() -> None:
    os.chdir(ROOT)
    VENDOR.mkdir(parents=True, exist_ok=True)

    # 1. Stamp BUILD_SHA
    write_build_info()

    # 2. Virtual environment setup (when on Linux)
    python = sys.executable
    pip = None
    if sys.platform.startswith("linux"):
        if not VENV.exists():
            print("Creating Linux virtual environment...", flush=True)
            run([sys.executable, "-m", "venv", "--system-site-packages", str(VENV)])
        python = str(VENV / "bin" / "python")
        pip = str(VENV / "bin" / "pip")

        run([pip, "install", "--upgrade", "pip", "-q"])
        linux_pkgs = [
            "numpy>=1.26.0",
            "psutil>=5.9.8",
            "Pillow>=10.3.0",
            "pystray>=0.19.5",
            "pyinstaller>=6.6.0",
            "qrcode>=7.4.2",
        ]
        run([pip, "install"] + linux_pkgs)
        subprocess.run([pip, "install", "soxr", "pyaudio"], check=False)

    # 3. Locate system libopus
    opus_so = VENDOR / "libopus.so.0"
    if not opus_so.exists():
        sys_opus = find_system_libopus()
        if sys_opus:
            shutil.copy2(sys_opus, opus_so)
            print(f"Bundling system libopus: {sys_opus} -> {opus_so}")

    # 4. Icon generation
    icon_png = VENDOR / "app_icon.png"
    if not icon_png.exists():
        gen_script = (
            "import sys; "
            f"sys.path.insert(0, r'{ROOT / 'src'}'); "
            "from ui import _make_tray_image; "
            f"img = _make_tray_image(256); img.save(r'{icon_png}', format='PNG')"
        )
        subprocess.run([str(python), "-c", gen_script], check=False)

    # 5. Build onedir Linux application bundle with PyInstaller
    print("\n--- Building Linux Onedir Application Bundle (dist/AirSpeaker/) ---", flush=True)
    dist_dir = ROOT / "dist"
    app_dir = dist_dir / "AirSpeaker"
    if app_dir.exists():
        shutil.rmtree(app_dir)

    pyinstaller_args = [
        str(python), "-m", "PyInstaller",
        "--noconsole",
        "--onedir",
        "--name", "AirSpeaker",
        "--distpath", str(dist_dir),
        "--workpath", str(ROOT / "build-linux"),
        "--specpath", str(ROOT),
        "--paths", str(ROOT / "src"),
        "--hidden-import", "PIL._tkinter_finder",
    ]
    if opus_so.exists():
        pyinstaller_args += ["--add-binary", f"{opus_so}:."]
    if icon_png.exists():
        pyinstaller_args += ["--add-data", f"{icon_png}:vendor"]

    pyinstaller_args.append(str(SRC_MAIN))
    run(pyinstaller_args)

    if not app_dir.exists():
        print(f"ERROR: Linux application bundle was not created at {app_dir}")
        sys.exit(1)

    # Ensure icon is available inside the bundle
    if icon_png.exists() and not (app_dir / "app_icon.png").exists():
        shutil.copy2(icon_png, app_dir / "app_icon.png")

    binary = app_dir / "AirSpeaker"
    if binary.exists():
        binary.chmod(binary.stat().st_mode | stat.S_IEXEC | stat.S_IRWXU)

    # 6. Package dist/AirSpeaker into ../AirSpeaker-v1-Linux-x86_64.tar.gz
    tar_out = OUT_DIR / "AirSpeaker-v1-Linux-x86_64.tar.gz"
    print(f"\nPackaging application bundle into {tar_out}...", flush=True)
    if tar_out.exists():
        tar_out.unlink()

    with tarfile.open(tar_out, "w:gz") as tar:
        for item in app_dir.iterdir():
            tar.add(item, arcname=item.name)

    size_mb = tar_out.stat().st_size / (1024 * 1024)
    print(f"SUCCESS: Linux application tarball created at {tar_out} ({size_mb:.1f} MB)", flush=True)


if __name__ == "__main__":
    main()
