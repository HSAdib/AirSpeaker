#!/usr/bin/env bash
# ==============================================================================
# AirSpeaker — Real Persistent Linux Application Installer
# Supports Arch Linux, Ubuntu, Debian, Linux Mint, Fedora, and openSUSE
# ==============================================================================
set -e

INSTALL_BASE="$HOME/.local/share/AirSpeaker"
BIN_DIR="$HOME/.local/bin"
APP_DIR="$INSTALL_BASE/app"
SRC_DIR="$INSTALL_BASE/src"
ICON_DIR="$HOME/.local/share/icons/hicolor/256x256/apps"
DESKTOP_DIR="$HOME/.local/share/applications"

do_uninstall() {
    echo "=========================================================="
    echo "  AirSpeaker — Uninstalling Persistent Desktop App..."
    echo "=========================================================="
    pkill -f "AirSpeaker" 2>/dev/null || true
    pkill -f "airspeaker-pc/src/main.py" 2>/dev/null || true

    rm -rf "$INSTALL_BASE"
    rm -f "$BIN_DIR/airspeaker"
    rm -f "$BIN_DIR/airspeaker-uninstall"
    rm -f "$DESKTOP_DIR/airspeaker.desktop"
    rm -f "$ICON_DIR/airspeaker.png"
    rm -f "$HOME/.local/share/icons/airspeaker.png"

    if [ -w /usr/local/bin/airspeaker ]; then
        rm -f /usr/local/bin/airspeaker 2>/dev/null || true
    elif command -v sudo >/dev/null 2>&1; then
        sudo rm -f /usr/local/bin/airspeaker 2>/dev/null || true
    fi

    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
    fi
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then
        gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
    fi

    echo "AirSpeaker has been completely and cleanly uninstalled."
    exit 0
}

if [ "$1" = "--uninstall" ] || [ "$1" = "-u" ] || [ "$1" = "uninstall" ]; then
    do_uninstall
fi

echo "=========================================================="
echo "  AirSpeaker — Linux Desktop Application Installer"
echo "=========================================================="

mkdir -p "$BIN_DIR" "$INSTALL_BASE" "$ICON_DIR" "$DESKTOP_DIR"

# 1. Install Runtime Dependencies via Package Manager
echo "Checking and installing audio & runtime system dependencies..."
if command -v pacman >/dev/null 2>&1; then
    echo "[Arch Linux detected] Installing dependencies via pacman..."
    sudo pacman -S --needed --noconfirm opus libpulse portaudio pulseaudio-utils python tk python-pip python-pillow python-numpy 2>/dev/null || true
elif command -v apt-get >/dev/null 2>&1; then
    echo "[Debian/Ubuntu/Mint detected] Installing dependencies via apt..."
    sudo apt-get update -qq 2>/dev/null || true
    sudo apt-get install -y libopus0 pulseaudio-utils libpulse0 portaudio19-dev python3 python3-tk libtk8.6 python3-venv python3-pip 2>/dev/null || true
elif command -v dnf >/dev/null 2>&1; then
    echo "[Fedora detected] Installing dependencies via dnf..."
    sudo dnf install -y opus pulseaudio-utils pulseaudio-libs portaudio python3 python3-tkinter tk python3-pip 2>/dev/null || true
elif command -v zypper >/dev/null 2>&1; then
    echo "[openSUSE detected] Installing dependencies via zypper..."
    sudo zypper install -y libopus0 pulseaudio-utils libpulse0 python3 python3-tk tk 2>/dev/null || true
fi

ARCH=$(uname -m)
INSTALL_SUCCESS=0

# 2. Try Binary Release Package for x86_64
if [ "$ARCH" = "x86_64" ]; then
    echo "Downloading pre-compiled AirSpeaker Linux x86_64 bundle..."
    TAR_URL="https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-Linux-x86_64.tar.gz"
    TEMP_TAR="/tmp/AirSpeaker-Linux-x86_64.tar.gz"
    rm -f "$TEMP_TAR"

    if command -v curl >/dev/null 2>&1; then
        curl -sSL -f -o "$TEMP_TAR" "$TAR_URL" || true
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$TEMP_TAR" "$TAR_URL" || true
    fi

    if [ -s "$TEMP_TAR" ]; then
        echo "Extracting binary bundle to $APP_DIR..."
        rm -rf "$APP_DIR"
        mkdir -p "$APP_DIR"
        tar -xzf "$TEMP_TAR" -C "$APP_DIR"
        rm -f "$TEMP_TAR"

        if [ -f "$APP_DIR/AirSpeaker" ]; then
            chmod +x "$APP_DIR/AirSpeaker"
            # Self-check verification
            if "$APP_DIR/AirSpeaker" --version >/dev/null 2>&1; then
                echo "Pre-compiled application binary verified successfully."
                INSTALL_SUCCESS=1
                if [ -f "$APP_DIR/app_icon.png" ]; then
                    cp -f "$APP_DIR/app_icon.png" "$ICON_DIR/airspeaker.png"
                fi
            fi
        fi
    fi
fi

# 3. Source & Virtualenv Fallback (for ARM64, non-glibc, or if binary check failed)
if [ "$INSTALL_SUCCESS" -ne 1 ]; then
    echo "Setting up permanent self-contained Python source environment in $SRC_DIR..."
    rm -rf "$SRC_DIR"
    mkdir -p "$SRC_DIR"

    SRC_TAR_URL="https://github.com/HSAdib/AirSpeaker/archive/refs/heads/main.tar.gz"
    TEMP_SRC_TAR="/tmp/airspeaker-src.tar.gz"
    if command -v curl >/dev/null 2>&1; then
        curl -sSL -f -o "$TEMP_SRC_TAR" "$SRC_TAR_URL" || true
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$TEMP_SRC_TAR" "$SRC_TAR_URL" || true
    fi

    if [ -s "$TEMP_SRC_TAR" ]; then
        tar -xzf "$TEMP_SRC_TAR" -C "$SRC_DIR" --strip-components=1
        rm -f "$TEMP_SRC_TAR"

        cd "$SRC_DIR/airspeaker-pc"
        python3 -m venv --system-site-packages .venv
        ./.venv/bin/pip install --upgrade pip -q 2>/dev/null || true
        ./.venv/bin/pip install -r requirements.txt -q 2>/dev/null || true
        ./.venv/bin/pip install numpy psutil Pillow qrcode pystray -q 2>/dev/null || true

        if [ -f "$SRC_DIR/airspeaker-pc/vendor/app_icon.png" ]; then
            cp -f "$SRC_DIR/airspeaker-pc/vendor/app_icon.png" "$ICON_DIR/airspeaker.png"
        fi
        INSTALL_SUCCESS=1
    else
        echo "ERROR: Failed to download source package. Please check network connection."
        exit 1
    fi
fi

# 4. Generate Icon if missing
if [ ! -f "$ICON_DIR/airspeaker.png" ]; then
    cat << 'EOF' > /tmp/gen_icon.py
import sys
try:
    from PIL import Image, ImageDraw
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([16, 24, 232, 240], fill=(0, 180, 235, 255))
    d.ellipse([56, 48, 232, 216], fill=(18, 92, 200, 255))
    d.polygon([(84, 92), (84, 172), (148, 132)], fill=(220, 252, 255, 255))
    d.arc([96, 76, 192, 180], -60, 35, fill=(205, 250, 255, 255), width=12)
    d.arc([76, 52, 220, 204], -65, 35, fill=(205, 250, 255, 255), width=12)
    img.save(sys.argv[1])
except Exception:
    pass
EOF
    python3 /tmp/gen_icon.py "$ICON_DIR/airspeaker.png" 2>/dev/null || true
    rm -f /tmp/gen_icon.py
fi

if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -f -t "$HOME/.local/share/icons/hicolor" 2>/dev/null || true
fi

# 5. Create Permanent Launcher Wrapper ($HOME/.local/bin/airspeaker)
cat << 'EOF' > "$BIN_DIR/airspeaker"
#!/usr/bin/env bash
if [ -x "$HOME/.local/share/AirSpeaker/app/AirSpeaker" ]; then
    exec "$HOME/.local/share/AirSpeaker/app/AirSpeaker" "$@"
elif [ -x "$HOME/.local/share/AirSpeaker/src/airspeaker-pc/.venv/bin/python" ]; then
    exec "$HOME/.local/share/AirSpeaker/src/airspeaker-pc/.venv/bin/python" "$HOME/.local/share/AirSpeaker/src/airspeaker-pc/src/main.py" "$@"
else
    echo "AirSpeaker installation not found or damaged. Please re-run install_linux.sh" >&2
    exit 1
fi
EOF
chmod +x "$BIN_DIR/airspeaker"

# Optional symlink to /usr/local/bin
if [ -w /usr/local/bin ]; then
    ln -sf "$BIN_DIR/airspeaker" /usr/local/bin/airspeaker 2>/dev/null || true
elif command -v sudo >/dev/null 2>&1; then
    sudo ln -sf "$BIN_DIR/airspeaker" /usr/local/bin/airspeaker 2>/dev/null || true
fi

# 6. Create Permanent Uninstaller Script ($HOME/.local/bin/airspeaker-uninstall)
cat << 'EOF' > "$BIN_DIR/airspeaker-uninstall"
#!/usr/bin/env bash
curl -sSL https://raw.githubusercontent.com/HSAdib/AirSpeaker/main/install_linux.sh | bash -s -- --uninstall
EOF
chmod +x "$BIN_DIR/airspeaker-uninstall"

# 7. Create Permanent Desktop Entry ($HOME/.local/share/applications/airspeaker.desktop)
cat << EOF > "$DESKTOP_DIR/airspeaker.desktop"
[Desktop Entry]
Type=Application
Name=AirSpeaker
Comment=Low-latency wireless audio streaming studio
Exec=$BIN_DIR/airspeaker
Icon=airspeaker
Terminal=false
StartupWMClass=Tk
Categories=AudioVideo;Audio;Network;
Keywords=audio;sound;streaming;speaker;pulseaudio;pipewire;
EOF
chmod +x "$DESKTOP_DIR/airspeaker.desktop"

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
fi

echo "=========================================================="
echo " SUCCESS! AirSpeaker is permanently installed on your system!"
echo " Location:      $INSTALL_BASE"
echo " CLI Command:   airspeaker"
echo " Desktop App:   AirSpeaker (Available in Application Menu)"
echo " Uninstall:     airspeaker-uninstall"
echo "=========================================================="
