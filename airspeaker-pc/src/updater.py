"""
updater.py — GitHub release update checker & streaming downloader with live
progress, download speed, and OS Downloads folder storage for AirSpeaker PC.
"""

import json
import logging
import os
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Callable, Dict, Optional, Tuple

from config import clean_subprocess_env
from version import APP_VERSION, BUILD_SHA, GITHUB_REPO

logger = logging.getLogger(__name__)

LATEST_RELEASE_API = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
LINUX_INSTALL_CMD = "curl -sSL https://raw.githubusercontent.com/HSAdib/AirSpeaker/main/install_linux.sh | bash"

_cached_etag: Optional[str] = None
_cached_release_data: Optional[Dict[str, Any]] = None


def get_platform() -> str:
    if sys.platform.startswith("win"):
        return "windows"
    elif sys.platform == "darwin":
        return "macos"
    return "linux"


def get_downloads_dir() -> Path:
    """Return the AirSpeaker downloads directory (<OS Downloads>/AirSpeaker)."""
    base = None
    if sys.platform.startswith("linux") and shutil.which("xdg-user-dir"):
        try:
            out = subprocess.check_output(["xdg-user-dir", "DOWNLOAD"], text=True, timeout=2, env=clean_subprocess_env()).strip()
            if out:
                base = Path(out)
        except Exception:
            pass
    if base is None:
        base = Path.home() / "Downloads"
    p = base / "AirSpeaker"
    p.mkdir(parents=True, exist_ok=True)
    return p

def format_bytes(num_bytes: float) -> str:
    val = max(0.0, float(num_bytes))
    for unit in ("B", "KB", "MB", "GB"):
        if val < 1024.0 or unit == "GB":
            return f"{val:.1f} {unit}" if unit != "B" else f"{int(val)} B"
        val /= 1024.0
    return f"{val:.1f} GB"


def format_speed(bytes_per_sec: float) -> str:
    return f"{format_bytes(bytes_per_sec)}/s"


def is_windows_installed() -> bool:
    if not sys.platform.startswith("win"):
        return False
    exe = sys.executable or ""
    exe_dir = os.path.dirname(os.path.abspath(exe))
    if os.path.exists(os.path.join(exe_dir, "unins000.exe")):
        return True
    exe_lower = exe.lower()
    if "programs\\airspeaker" in exe_lower or "programs/airspeaker" in exe_lower or "programs\\air speaker" in exe_lower or "programs/air speaker" in exe_lower:
        return True
    return False


def parse_semver(v_str: str) -> Tuple[int, int, int]:
    cleaned = v_str.strip().lstrip("vV")
    nums = []
    for part in cleaned.split("."):
        m = re.match(r"^(\d+)", part)
        nums.append(int(m.group(1)) if m else 0)
    while len(nums) < 3:
        nums.append(0)
    return tuple(nums[:3])  # type: ignore


def extract_build_sha_from_body(body: str) -> Optional[str]:
    if not body:
        return None
    m = re.search(r"<!--\s*build:\s*([a-fA-F0-9]+)\s*-->", body, re.IGNORECASE)
    return m.group(1).strip() if m else None


def fetch_latest_release(timeout: float = 6.0) -> Dict[str, Any]:
    global _cached_etag, _cached_release_data
    headers = {
        "User-Agent": f"AirSpeaker/{APP_VERSION}",
        "Accept": "application/vnd.github.v3+json",
    }
    if _cached_etag and _cached_release_data is not None:
        headers["If-None-Match"] = _cached_etag

    req = urllib.request.Request(LATEST_RELEASE_API, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            etag = resp.headers.get("ETag")
            data = json.loads(resp.read().decode("utf-8"))
            if etag:
                _cached_etag = etag
                _cached_release_data = data
            return data
    except urllib.error.HTTPError as e:
        if e.code == 304 and _cached_release_data is not None:
            return _cached_release_data
        raise


def select_download_asset(release_data: Dict[str, Any]) -> Tuple[str, str]:
    plat = get_platform()
    assets = release_data.get("assets", [])
    html_url = release_data.get("html_url", f"https://github.com/{GITHUB_REPO}/releases/latest")

    if plat == "windows":
        target_name = "AirSpeaker-Setup.exe" if is_windows_installed() else "AirSpeaker-Portable.exe"
        fallback_name = "AirSpeaker-Portable.exe" if is_windows_installed() else "AirSpeaker-Setup.exe"
        for a in assets:
            if a.get("name") == target_name:
                return a.get("browser_download_url", html_url), target_name
        for a in assets:
            if a.get("name") == fallback_name or a.get("name") == "AirSpeaker-v1-Portable.exe" or a.get("name") == "AirSpeaker-v1-Setup.exe" or a.get("name") == "AirSpeaker.v1.exe":
                return a.get("browser_download_url", html_url), a.get("name", target_name)
        return html_url, target_name
    elif plat == "macos":
        mach = platform.machine().lower()
        is_arm = mach in ("arm64", "aarch64")
        if is_arm:
            # Prefer Apple Silicon arm64 build
            preferred = ["AirSpeaker-macOS-arm64.dmg", "AirSpeaker-macOS.dmg"]
            for target in preferred:
                for a in assets:
                    if a.get("name") == target:
                        return a.get("browser_download_url", html_url), target
            for a in assets:
                name = str(a.get("name", ""))
                if name.endswith(".dmg") and "x86_64" not in name.lower() and "intel" not in name.lower():
                    return a.get("browser_download_url", html_url), name
            for a in assets:
                if str(a.get("name", "")).endswith(".dmg"):
                    return a.get("browser_download_url", html_url), a.get("name", "AirSpeaker-macOS-arm64.dmg")
            return html_url, "AirSpeaker-macOS-arm64.dmg"
        else:
            # Intel Mac (x86_64)
            for a in assets:
                if a.get("name") == "AirSpeaker-macOS-x86_64.dmg":
                    return a.get("browser_download_url", html_url), "AirSpeaker-macOS-x86_64.dmg"
            for a in assets:
                name = str(a.get("name", ""))
                if name.endswith(".dmg") and ("x86_64" in name.lower() or "intel" in name.lower()):
                    return a.get("browser_download_url", html_url), name
            for a in assets:
                if str(a.get("name", "")).endswith(".dmg"):
                    return a.get("browser_download_url", html_url), a.get("name", "AirSpeaker-macOS-x86_64.dmg")
            return html_url, "AirSpeaker-macOS-x86_64.dmg"
    else:
        target_name = "AirSpeaker-Linux-x86_64.tar.gz"
        for a in assets:
            if a.get("name") == target_name or str(a.get("name", "")).endswith(".tar.gz"):
                return a.get("browser_download_url", html_url), a.get("name", target_name)
        return html_url, target_name


def check_for_updates(timeout: float = 6.0, raise_on_error: bool = False) -> Optional[Dict[str, Any]]:
    try:
        data = fetch_latest_release(timeout=timeout)
    except Exception as exc:
        logger.debug("Update check request failed: %s", exc)
        if raise_on_error:
            raise
        return None

    remote_tag = data.get("tag_name", "")
    if not remote_tag:
        return None

    remote_semver = parse_semver(remote_tag)
    local_semver = parse_semver(APP_VERSION)

    body = data.get("body", "") or ""
    remote_sha = extract_build_sha_from_body(body)
    local_sha = (BUILD_SHA or "dev").strip()

    # Exact commit match -> already running this exact release build!
    if remote_sha and local_sha != "dev" and remote_sha[:7].lower() == local_sha[:7].lower():
        return None

    is_newer_semver = remote_semver > local_semver
    is_newer_rebuild = (
        remote_semver == local_semver
        and bool(remote_sha)
        and local_sha != "dev"
        and remote_sha[:7].lower() != local_sha[:7].lower()
    )

    if not (is_newer_semver or is_newer_rebuild):
        return None

    update_id = f"{remote_tag}_{remote_sha[:7]}" if remote_sha else remote_tag
    download_url, asset_name = select_download_asset(data)
    display_version = f"{remote_tag} ({remote_sha[:7]})" if (is_newer_rebuild and remote_sha) else remote_tag

    return {
        "available": True,
        "tag_name": remote_tag,
        "display_version": display_version,
        "remote_sha": remote_sha,
        "local_sha": local_sha,
        "update_id": update_id,
        "download_url": download_url,
        "asset_name": asset_name,
        "html_url": data.get("html_url", f"https://github.com/{GITHUB_REPO}/releases/latest"),
        "release_body": body,
        "platform": get_platform(),
        "is_windows_installed": is_windows_installed(),
    }


def open_downloads_folder(file_path: Optional[str] = None) -> None:
    """Open Downloads/AirSpeaker and highlight the downloaded update file when supported."""
    downloads = str(get_downloads_dir())
    try:
        if sys.platform.startswith("win"):
            if file_path and os.path.exists(file_path):
                subprocess.Popen(["explorer", "/select,", os.path.normpath(file_path)])
            else:
                os.startfile(downloads)
        elif sys.platform == "darwin":
            if file_path and os.path.exists(file_path):
                subprocess.Popen(["open", "-R", file_path])
            else:
                subprocess.Popen(["open", downloads])
        else:
            if file_path and os.path.exists(file_path):
                if shutil.which("dbus-send"):
                    try:
                        uri = Path(file_path).resolve().as_uri()
                        res = subprocess.run(
                            [
                                "dbus-send", "--session",
                                "--dest=org.freedesktop.FileManager1",
                                "--type=method_call",
                                "/org/freedesktop/FileManager1",
                                "org.freedesktop.FileManager1.ShowItems",
                                f"array:string:{uri}", "string:",
                            ],
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL,
                            timeout=2,
                            env=clean_subprocess_env(),
                        )
                        if res.returncode == 0:
                            return
                    except Exception:
                        pass
                subprocess.Popen(["xdg-open", str(Path(file_path).resolve().parent)], env=clean_subprocess_env())
            else:
                subprocess.Popen(["xdg-open", downloads], env=clean_subprocess_env())
    except Exception as exc:
        logger.debug("Could not open Downloads/AirSpeaker folder: %s", exc)


def download_update_to_downloads(
    download_url: str,
    filename: str,
    on_progress: Optional[Callable[[int, int, float], None]] = None,
    on_complete: Optional[Callable[[str], None]] = None,
    on_error: Optional[Callable[[Exception], None]] = None,
    cancel_event: Optional[threading.Event] = None,
) -> threading.Thread:
    """Stream update package into OS Downloads folder with live byte count & speed callbacks."""
    def _worker():
        dest_dir = get_downloads_dir()
        safe_name = os.path.basename(filename or "AirSpeaker-Update.bin")
        final_path = dest_dir / safe_name
        part_path = dest_dir / f"{safe_name}.part"
        try:
            req = urllib.request.Request(
                download_url,
                headers={"User-Agent": f"AirSpeaker/{APP_VERSION}"},
            )
            with urllib.request.urlopen(req, timeout=30) as resp:
                total_hdr = resp.headers.get("Content-Length")
                total_bytes = int(total_hdr) if (total_hdr and total_hdr.isdigit()) else 0
                downloaded = 0
                start_t = time.monotonic()
                last_cb_t = start_t
                last_cb_bytes = 0
                ema_speed = 0.0

                with open(part_path, "wb") as out_f:
                    while True:
                        if cancel_event is not None and cancel_event.is_set():
                            raise InterruptedError("Download cancelled by user")
                        chunk = resp.read(262144)
                        if not chunk:
                            break
                        out_f.write(chunk)
                        downloaded += len(chunk)

                        now = time.monotonic()
                        dt = now - last_cb_t
                        if dt >= 0.10 or (total_bytes > 0 and downloaded >= total_bytes):
                            inst_speed = (downloaded - last_cb_bytes) / max(0.001, dt)
                            ema_speed = inst_speed if ema_speed <= 0.0 else (0.7 * ema_speed + 0.3 * inst_speed)
                            last_cb_t = now
                            last_cb_bytes = downloaded
                            if on_progress:
                                on_progress(downloaded, total_bytes, ema_speed)

            os.replace(part_path, final_path)
            if on_complete:
                on_complete(str(final_path))
        except Exception as exc:
            try:
                if part_path.exists():
                    part_path.unlink()
            except Exception:
                pass
            if on_error:
                on_error(exc)
            else:
                logger.error("Update download failed: %s", exc)

    t = threading.Thread(target=_worker, name="UpdateDownloader", daemon=True)
    t.start()
    return t


def launch_downloaded_windows_installer(installer_path: str) -> None:
    subprocess.Popen([installer_path, "/SILENT", "/CLOSEAPPLICATIONS"])


def install_downloaded_update(saved_path: str, is_win_installed: bool = False) -> Tuple[bool, str, bool]:
    """
    Install or launch the downloaded update package.
    Returns (success, status_message, should_quit_current_app).
    """
    if not saved_path or not os.path.exists(saved_path):
        return False, "Downloaded update file not found.", False

    plat = get_platform()
    try:
        if plat == "windows":
            if is_win_installed or "setup" in os.path.basename(saved_path).lower():
                launch_downloaded_windows_installer(saved_path)
                return True, "Launching Windows Setup installer...", True
            subprocess.Popen([saved_path])
            return True, "Launching updated portable executable...", True

        if plat == "macos":
            subprocess.Popen(["open", saved_path])
            return True, "Opened macOS installer DMG. Drag AirSpeaker into Applications.", False

        # Linux: install directly from downloaded tarball into ~/.local/share/AirSpeaker/app
        app_dir = Path.home() / ".local" / "share" / "AirSpeaker" / "app"
        if saved_path.endswith(".tar.gz"):
            staging = Path(tempfile.mkdtemp(prefix="airspeaker_upd_"))
            try:
                with tarfile.open(saved_path, "r:gz") as tf:
                    staging_resolved = staging.resolve()
                    for member in tf.getmembers():
                        member_dest = (staging / member.name).resolve()
                        if not str(member_dest).startswith(str(staging_resolved)):
                            raise RuntimeError("Unsafe path in update archive")
                    tf.extractall(staging)
                if not (staging / "AirSpeaker").exists():
                    raise RuntimeError("AirSpeaker binary missing in archive")
                app_dir.parent.mkdir(parents=True, exist_ok=True)
                backup_dir = app_dir.with_name("app.old")
                if backup_dir.exists():
                    shutil.rmtree(backup_dir, ignore_errors=True)
                if app_dir.exists():
                    app_dir.rename(backup_dir)
                shutil.move(str(staging), str(app_dir))
                shutil.rmtree(backup_dir, ignore_errors=True)
                new_bin = app_dir / "AirSpeaker"
                new_bin.chmod(new_bin.stat().st_mode | 0o755)
                subprocess.Popen([str(new_bin)], start_new_session=True, env=clean_subprocess_env())
                return True, "Update installed! Restarting AirSpeaker...", True
            except Exception as exc:
                shutil.rmtree(staging, ignore_errors=True)
                logger.warning("Direct Linux tarball install failed (%s); falling back to terminal updater", exc)

        cmd = f"{LINUX_INSTALL_CMD} && echo 'Update complete! Press Enter.' && read"
        for term in (
            ["x-terminal-emulator", "-e", "bash", "-c", cmd],
            ["gnome-terminal", "--", "bash", "-c", cmd],
            ["konsole", "-e", "bash", "-c", cmd],
        ):
            try:
                subprocess.Popen(term, env=clean_subprocess_env())
                return True, "Launched Linux updater in terminal.", False
            except Exception:
                continue
        return False, f"Run in terminal:\n{LINUX_INSTALL_CMD}", False
    except Exception as exc:
        return False, f"Failed to launch installer: {exc}", False

