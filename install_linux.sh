#!/usr/bin/env bash
# install_linux.sh — One-line instant installer for Air Speaker v1 Studio (Arch, Ubuntu, Mint, Debian, Fedora)
set -e

if [ "$1" = "--uninstall" ] || [ "$1" = "-u" ] || [ "$1" = "uninstall" ]; then
    echo "=========================================================="
    echo "  Air Speaker v1 Studio — Uninstalling..."
    echo "=========================================================="
    pkill -f "airspeaker-pc/src/main.py" 2>/dev/null || true
    pkill -f "AirSpeaker-v1" 2>/dev/null || true
    pkill -f "AirSpeaker-v2" 2>/dev/null || true
    pkill -f "phonespeaker-pc/src/main.py" 2>/dev/null || true
    pkill -f "PhoneSpeaker-v2" 2>/dev/null || true
    rm -rf "$HOME/.local/opt/airspeaker" "$HOME/.local/bin/airspeaker" \
           "$HOME/.local/share/applications/airspeaker.desktop" \
           "$HOME/.local/share/icons/airspeaker.png" "$HOME/.config/AirSpeaker" \
           "$HOME/.local/opt/phonespeaker" "$HOME/.local/bin/phonespeaker" \
           "$HOME/.local/share/applications/phonespeaker.desktop" \
           "$HOME/.local/share/icons/phonespeaker.png" "$HOME/.config/PhoneSpeaker"
    sudo rm -f /usr/local/bin/airspeaker /usr/local/bin/phonespeaker 2>/dev/null || true
    update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true
    echo "Air Speaker v1 Studio has been successfully uninstalled."
    exit 0
fi

echo "=========================================================="
echo "  Air Speaker v1 Studio — Linux Installer"
echo "=========================================================="

INSTALLER="/tmp/AirSpeaker-v1-Linux-Installer.run"
rm -f "$INSTALLER"

# Check if we have the installer locally or need to download from GitHub
if [ -f "AirSpeaker-v1-Linux-Installer.run" ]; then
    echo "Using local AirSpeaker-v1-Linux-Installer.run..."
    cp -f "AirSpeaker-v1-Linux-Installer.run" "$INSTALLER"
else
    echo "Downloading Air Speaker v1 Linux installer from GitHub..."
    CACHE_BUST="?t=$(date +%s)"
    PRIMARY_URL="https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-v1-Linux-Installer.run"
    FALLBACK_URL="https://github.com/HSAdib/AirSpeaker/releases/download/v1.0.0/AirSpeaker-v1-Linux-Installer.run"
    RAW_URL="https://raw.githubusercontent.com/HSAdib/AirSpeaker/main/AirSpeaker-v1-Linux-Installer.run"

    if command -v curl >/dev/null 2>&1; then
        curl -sSL -f -o "$INSTALLER" "${PRIMARY_URL}${CACHE_BUST}" || curl -sSL -f -o "$INSTALLER" "${FALLBACK_URL}${CACHE_BUST}" || curl -sSL -f -o "$INSTALLER" "${RAW_URL}${CACHE_BUST}"
    elif command -v wget >/dev/null 2>&1; then
        wget -q --show-progress -O "$INSTALLER" "${PRIMARY_URL}${CACHE_BUST}" || wget -q --show-progress -O "$INSTALLER" "${FALLBACK_URL}${CACHE_BUST}" || wget -q --show-progress -O "$INSTALLER" "${RAW_URL}${CACHE_BUST}"
    else
        echo "Error: neither curl nor wget was found. Please install curl or wget."
        exit 1
    fi
fi

if [ ! -s "$INSTALLER" ]; then
    echo "ERROR: Failed to download AirSpeaker-v1-Linux-Installer.run"
    exit 1
fi

chmod +x "$INSTALLER"
echo "Executing installer..."
"$INSTALLER"
rm -f "$INSTALLER"
