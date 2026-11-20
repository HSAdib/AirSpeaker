"""
updater.py — Automatic background and manual GitHub release update checker for Air Speaker PC.
Zero extra dependencies (uses urllib.request + json).
"""

import json
import logging
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.request
import webbrowser
from typing import Any, Dict, Optional, Tuple

from version import APP_VERSION, BUILD_SHA, GITHUB_REPO

logger = logging.getLogger(__name__)

LATEST_RELEASE_API = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
LINUX_INSTALL_CMD = "curl -sSL https://raw.githubusercontent.com/HSAdib/AirSpeaker/main/install_linux.sh | bash"


def get_platform() -> str:
    if sys.platform.startswith("win"):
        return "windows"
    elif sys.platform == "darwin":
        return "macos"
    return "linux"


def is_windows_installed() -> bool:
    """Detect whether Windows is running as the Installed App vs Portable EXE."""
    if not sys.platform.startswith("win"):
        return False
    exe = sys.executable or ""
    exe_dir = os.path.dirname(os.path.abspath(exe))
    # Inno Setup creates unins000.exe in the installation folder
    if os.path.exists(os.path.join(exe_dir, "unins000.exe")):
        return True
    exe_lower = exe.lower()
    if "programs\\air speaker" in exe_lower or "programs/air speaker" in exe_lower:
        return True
    return False


def parse_semver(v_str: str) -> Tuple[int, int, int]:
    """Parse semver string (e.g. 'v1.0.1' -> (1, 0, 1))."""
    cleaned = v_str.strip().lstrip("vV")
    nums = []
    for part in cleaned.split("."):
        m = re.match(r"^(\d+)", part)
        if m:
            nums.append(int(m.group(1)))
        else:
            nums.append(0)
    while len(nums) < 3:
        nums.append(0)
    return tuple(nums[:3])  # type: ignore


def extract_build_sha_from_body(body: str) -> Optional[str]:
    """Extract <!-- build: <sha> --> marker from release notes body."""
    if not body:
        return None
    m = re.search(r"<!--\s*build:\s*([a-fA-F0-9]+)\s*-->", body, re.IGNORECASE)
    if m:
        return m.group(1).strip()
    return None


def fetch_latest_release(timeout: float = 5.0) -> Dict[str, Any]:
    """Fetch latest release metadata from GitHub API."""
    req = urllib.request.Request(
        LATEST_RELEASE_API,
        headers={
            "User-Agent": f"AirSpeaker/{APP_VERSION}",
            "Accept": "application/vnd.github.v3+json",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data


def select_download_asset(release_data: Dict[str, Any]) -> Tuple[str, str]:
    """
    Select appropriate download asset URL and asset name based on OS and installation mode.
    Returns (download_url, asset_name).
    """
    plat = get_platform()
    assets = release_data.get("assets", [])
    html_url = release_data.get("html_url", f"https://github.com/{GITHUB_REPO}/releases/latest")

    if plat == "windows":
        target_name = "AirSpeaker-v1-Setup.exe" if is_windows_installed() else "AirSpeaker-v1-Portable.exe"
        fallback_name = "AirSpeaker-v1-Portable.exe" if is_windows_installed() else "AirSpeaker-v1-Setup.exe"
        for a in assets:
            if a.get("name") == target_name:
                return a.get("browser_download_url", html_url), target_name
        for a in assets:
            if a.get("name") == fallback_name or a.get("name") == "AirSpeaker.v1.exe":
                return a.get("browser_download_url", html_url), a.get("name", "")
        return html_url, target_name

    elif plat == "macos":
        target_name = "AirSpeaker-v1-macOS.dmg"
        for a in assets:
            if a.get("name") == target_name or str(a.get("name", "")).endswith(".dmg"):
                return a.get("browser_download_url", html_url), a.get("name", target_name)
        return html_url, target_name

    else:  # linux
        target_name = "AirSpeaker-v1-Linux-x86_64.tar.gz"
        for a in assets:
            if a.get("name") == target_name or str(a.get("name", "")).endswith(".tar.gz"):
                return a.get("browser_download_url", html_url), a.get("name", target_name)
        return html_url, target_name


def check_for_updates(timeout: float = 5.0) -> Optional[Dict[str, Any]]:
    """
    Check if a new release or rebuild is available.
    Returns update info dict if available, otherwise None.
    """
    try:
        data = fetch_latest_release(timeout=timeout)
    except Exception as exc:
        logger.debug("Update check request failed: %s", exc)
        return None

    remote_tag = data.get("tag_name", "")
    if not remote_tag:
        return None

    remote_semver = parse_semver(remote_tag)
    local_semver = parse_semver(APP_VERSION)

    body = data.get("body", "") or ""
    remote_sha = extract_build_sha_from_body(body)
    local_sha = (BUILD_SHA or "dev").strip()

    is_newer_semver = remote_semver > local_semver
    is_newer_rebuild = False

    if remote_semver == local_semver and remote_sha:
        if local_sha != "dev" and remote_sha.lower() != local_sha.lower():
            is_newer_rebuild = True

    if not (is_newer_semver or is_newer_rebuild):
        return None

    # Construct unique update ID for skipping
    update_id = f"{remote_tag}_{remote_sha}" if remote_sha else remote_tag
    download_url, asset_name = select_download_asset(data)

    display_version = remote_tag
    if is_newer_rebuild and remote_sha:
        display_version = f"{remote_tag} ({remote_sha[:7]})"

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


def download_and_run_windows_installer(
    download_url: str,
    on_complete=None,
    on_error=None,
) -> None:
    """Download AirSpeaker-v1-Setup.exe to %TEMP% in background and launch it."""
    def _worker():
        try:
            target_path = os.path.join(tempfile.gettempdir(), "AirSpeaker-v1-Setup.exe")
            req = urllib.request.Request(
                download_url,
                headers={"User-Agent": f"AirSpeaker/{APP_VERSION}"},
            )
            with urllib.request.urlopen(req, timeout=30) as resp, open(target_path, "wb") as out_f:
                shutil.copyfileobj(resp, out_f)

            # Launch installer before notifying UI to quit
            subprocess.Popen([target_path, "/SILENT", "/CLOSEAPPLICATIONS"])

            if on_complete:
                on_complete(target_path)
        except Exception as exc:
            logger.error("Failed to download or run Windows installer: %s", exc)
            if on_error:
                on_error(exc)
            else:
                webbrowser.open(download_url)

    t = threading.Thread(target=_worker, daemon=True)
    t.start()
