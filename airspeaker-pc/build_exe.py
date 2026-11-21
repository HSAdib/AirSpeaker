"""
build_exe.py — Build Windows Portable EXE and Native Inno Setup Installer.

Produces:
  1. AirSpeaker-v1-Portable.exe (Standalone zero-install single-file executable)
  2. AirSpeaker-v1-Setup.exe (Native Inno Setup 6 installer for Start Menu / Windows Apps)
"""

import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT_DIR = Path(os.environ.get("AIRSPEAKER_OUT_DIR", str(ROOT.parent))).resolve()
OUT_DIR.mkdir(parents=True, exist_ok=True)
VENV = ROOT / ".venv"
VENDOR = ROOT / "vendor"
SRC_MAIN = ROOT / "src" / "main.py"
OPUS_DLL = VENDOR / "opus.dll"
REQUIREMENTS = ROOT / "requirements.txt"


def run(cmd: list, **kwargs) -> subprocess.CompletedProcess:
    print(f"$ {' '.join(str(c) for c in cmd)}", flush=True)
    result = subprocess.run(cmd, **kwargs)
    if result.returncode != 0:
        print(f"ERROR: command exited with code {result.returncode}", flush=True)
        sys.exit(result.returncode)
    return result


def venv_python() -> Path:
    if os.name == "nt":
        return VENV / "Scripts" / "python.exe"
    return VENV / "bin" / "python"


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


def main() -> None:
    os.chdir(ROOT)

    # 1. Stamping build SHA
    write_build_info()

    # 2. Virtual environment setup
    if not VENV.exists():
        print("Creating virtual environment...", flush=True)
        run([sys.executable, "-m", "venv", str(VENV)])
    else:
        print("Virtual environment already exists.", flush=True)

    python = venv_python()

    # 3. Upgrade pip and install requirements
    run([str(python), "-m", "pip", "install", "--upgrade", "pip", "-q"])
    print("Installing requirements...", flush=True)
    run([str(python), "-m", "pip", "install", "-r", str(REQUIREMENTS), "-q"])

    # 4. Acquire opus.dll if missing
    if not OPUS_DLL.exists():
        print("Acquiring opus.dll...", flush=True)
        run([str(python), str(VENDOR / "get_opus.py")])
    else:
        print(f"opus.dll already present: {OPUS_DLL}", flush=True)

    # 5. Icon generation
    icon_path = VENDOR / "app_icon.ico"
    if not icon_path.exists():
        gen_script = (
            "import sys; from pathlib import Path; "
            f"sys.path.insert(0, r'{ROOT / 'src'}'); "
            "from ui import _make_tray_image; "
            f"Path(r'{VENDOR}').mkdir(parents=True, exist_ok=True); "
            f"img = _make_tray_image(256); "
            f"img.save(r'{icon_path}', format='ICO', sizes=[(256, 256), (64, 64), (32, 32), (16, 16)]); "
            "print('Icon generated successfully')"
        )
        try:
            run([str(python), "-c", gen_script])
            print(f"Generated icon: {icon_path}", flush=True)
        except Exception as exc:
            print(f"Could not generate app_icon.ico: {exc}", flush=True)

    # =========================================================================
    # BUILD 1: Portable Standalone Single-File Executable
    # =========================================================================
    print("\n--- [1/2] Building Windows Portable EXE (AirSpeaker-v1-Portable.exe) ---", flush=True)
    portable_args = [
        str(python), "-m", "PyInstaller",
        "--noconsole",
        "--onefile",
        "-y",
        "--name", "AirSpeaker-v1-Portable",
        "--distpath", str(ROOT / "dist"),
        "--workpath", str(ROOT / "build-portable"),
        "--specpath", str(ROOT),
        "--paths", str(ROOT / "src"),
    ]
    if OPUS_DLL.exists():
        portable_args += ["--add-binary", f"{OPUS_DLL};."]
    if icon_path.exists():
        portable_args += ["--icon", str(icon_path)]
    portable_args.append(str(SRC_MAIN))
    run(portable_args)

    portable_exe = ROOT / "dist" / "AirSpeaker-v1-Portable.exe"
    if portable_exe.exists():
        target = OUT_DIR / "AirSpeaker-v1-Portable.exe"
        try:
            shutil.copy2(portable_exe, target)
            size_mb = target.stat().st_size / (1024 * 1024)
            print(f"SUCCESS: Portable EXE ready at {target} ({size_mb:.1f} MB)", flush=True)
        except Exception as exc:
            print(f"Warning: Could not copy portable EXE to {target}: {exc}", flush=True)

    # =========================================================================
    # BUILD 2: Installed App (--onedir) & Inno Setup 6 Installer
    # =========================================================================
    print("\n--- [2/2] Building Windows Installed App (--onedir dist/AirSpeaker/) ---", flush=True)
    onedir_args = [
        str(python), "-m", "PyInstaller",
        "--noconsole",
        "--onedir",
        "-y",
        "--name", "AirSpeaker",
        "--distpath", str(ROOT / "dist"),
        "--workpath", str(ROOT / "build-onedir"),
        "--specpath", str(ROOT),
        "--paths", str(ROOT / "src"),
    ]
    if OPUS_DLL.exists():
        onedir_args += ["--add-binary", f"{OPUS_DLL};."]
    if icon_path.exists():
        onedir_args += ["--icon", str(icon_path)]
    onedir_args.append(str(SRC_MAIN))
    run(onedir_args)

    # Search for Inno Setup compiler ISCC.exe
    iscc_candidates = [
        shutil.which("iscc"),
        shutil.which("ISCC"),
        Path(r"C:\Program Files (x86)\Inno Setup 6\ISCC.exe"),
        Path(r"C:\Program Files\Inno Setup 6\ISCC.exe"),
    ]
    iscc_bin = next((str(p) for p in iscc_candidates if p and Path(p).exists()), None)
    if iscc_bin:
        print(f"\nCompiling Inno Setup installer using: {iscc_bin}", flush=True)
        iss_file = ROOT / "installer_windows.iss"
        run([iscc_bin, str(iss_file)])
        setup_exe = ROOT.parent / "AirSpeaker-v1-Setup.exe"
        if setup_exe.exists():
            if OUT_DIR != ROOT.parent:
                try:
                    shutil.copy2(setup_exe, OUT_DIR / "AirSpeaker-v1-Setup.exe")
                    setup_exe = OUT_DIR / "AirSpeaker-v1-Setup.exe"
                except Exception as exc:
                    print(f"Warning: Could not copy setup installer to {OUT_DIR}: {exc}", flush=True)
            size_mb = setup_exe.stat().st_size / (1024 * 1024)
            print(f"SUCCESS: Windows Setup Installer ready at {setup_exe} ({size_mb:.1f} MB)", flush=True)
    else:
        print(
            "\nINFO: Inno Setup 6 (ISCC.exe) is not installed locally. "
            "installer_windows.iss is verified and will be automatically compiled on GitHub Actions.",
            flush=True,
        )

    # Clean up specs
    for s in [ROOT / "AirSpeaker-v1-Portable.spec", ROOT / "AirSpeaker.spec"]:
        if s.exists():
            try:
                s.unlink()
            except Exception:
                pass


if __name__ == "__main__":
    main()
