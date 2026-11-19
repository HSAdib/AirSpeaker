"""
version.py — Application version and GitHub repository identifiers.
"""

import os
import sys

APP_VERSION = "1.0.0"
GITHUB_REPO = "HSAdib/AirSpeaker"

try:
    from _build_info import BUILD_SHA
except ImportError:
    try:
        from src._build_info import BUILD_SHA
    except ImportError:
        BUILD_SHA = "dev"
