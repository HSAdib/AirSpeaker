"""
main.py — Entry point for AirSpeaker PC.

Sets high process priority and 1ms timer resolution on Windows, then starts the UI.
Handles graceful shutdown on exit.
"""

import ctypes
import logging
import os
import sys

# Ensure src/ is on the path when running directly
_SRC_DIR = os.path.dirname(os.path.abspath(__file__))
if _SRC_DIR not in sys.path:
    sys.path.insert(0, _SRC_DIR)

if "--version" in sys.argv or "-V" in sys.argv:
    try:
        from version import APP_VERSION, BUILD_SHA
        sha_str = f" ({BUILD_SHA})" if BUILD_SHA and BUILD_SHA != "dev" else ""
        print(f"AirSpeaker v{APP_VERSION}{sha_str}")
    except Exception:
        print("AirSpeaker v1.2.2")
    sys.exit(0)

import config as cfg_module

# Set up logging first
cfg_module.setup_logging()
logger = logging.getLogger(__name__)


def _elevate_priority() -> None:
    """Set this process to HIGH_PRIORITY_CLASS on Windows."""
    if not sys.platform.startswith("win"):
        return
    try:
        import psutil
        p = psutil.Process()
        p.nice(psutil.HIGH_PRIORITY_CLASS)
        logger.info("Process priority set to HIGH_PRIORITY_CLASS")
    except Exception as exc:
        logger.warning("Could not set process priority: %s", exc)


def _set_timer_resolution(period_ms: int = 1) -> None:
    """Call timeBeginPeriod(1) for precise sleep intervals on Windows."""
    if not sys.platform.startswith("win"):
        return
    try:
        ctypes.windll.winmm.timeBeginPeriod(period_ms)
        logger.info("Windows timer resolution set to %dms", period_ms)
    except Exception as exc:
        logger.warning("Could not set timer resolution: %s", exc)


def _clear_timer_resolution(period_ms: int = 1) -> None:
    if not sys.platform.startswith("win"):
        return
    try:
        ctypes.windll.winmm.timeEndPeriod(period_ms)
    except Exception:
        pass


def main() -> None:
    logger.info("Air Speaker PC starting")

    _set_timer_resolution(1)
    _elevate_priority()

    app_cfg = cfg_module.load()

    try:
        from ui import run_ui
        run_ui(app_cfg)
    except Exception as exc:
        logger.critical("Fatal UI error: %s", exc, exc_info=True)
    finally:
        _clear_timer_resolution(1)
        logger.info("Air Speaker PC exiting")


if __name__ == "__main__":
    main()
