"""
version.py — Application version and GitHub repository identifiers.
"""

GITHUB_REPO = "HSAdib/AirSpeaker"
APP_VERSION = "1.0.0"
BUILD_SHA = "dev"

try:
    import _build_info as _bi
    APP_VERSION = getattr(_bi, "APP_VERSION", APP_VERSION)
    BUILD_SHA = getattr(_bi, "BUILD_SHA", BUILD_SHA)
except ImportError:
    try:
        from src import _build_info as _bi
        APP_VERSION = getattr(_bi, "APP_VERSION", APP_VERSION)
        BUILD_SHA = getattr(_bi, "BUILD_SHA", BUILD_SHA)
    except ImportError:
        pass
