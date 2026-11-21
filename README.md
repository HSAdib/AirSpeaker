# AirSpeaker

Ultra-low-latency wireless audio streaming studio across **Windows**, **macOS**, **Linux**, **Android**, and **iOS**.

## Features
- **Lossless 48 kHz PCM16 & Opus Audio:** Sub-35 ms low-latency streaming over local Wi-Fi, Hotspot, or USB tethering (`UDP 5004` audio, `UDP 5005` two-way sync).
- **Bidirectional PLAY & SERVE:** Use your phone as a wireless PC speaker, or stream internal system audio from Android 10+ (`AudioPlaybackCapture`) and iOS (`ReplayKit`) to any PC, Mac, Linux machine, or phone.
- **Multi-Device Party Mode:** Broadcast audio to multiple phones and PCs in sync, pair instantly with QR codes, and extend range via Personal Hotspot daisy-chain repeaters.
- **Real-Time 5-Band Studio Equalizer:** Independent per-tab EQ curves plus a Universal Master EQ with built-in and custom presets.
- **Automatic Bluetooth Routing & Live Delay Graphs:** Seamless Bluetooth A2DP/BLE playback with real-time latency, jitter, and packet telemetry graphs.
- **Built-In In-App Updater (Settings Tab):** Check for updates and download new releases at full speed directly inside every app with live speed and progress indicators. Files save automatically to `Downloads/AirSpeaker` with one-tap **Install Now** and **See in Files** actions.

## Download

### Windows
- **Installer (Recommended — Start Menu & Windows Apps):** [Download AirSpeaker-Setup.exe](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-Setup.exe)
- **Portable (Standalone — No Install):** [Download AirSpeaker-Portable.exe](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-Portable.exe)

### Android
Select the APK that matches your device architecture:
- **64-bit ARM (Recommended for modern phones):**  
  [Download AirSpeaker-arm64-v8a.apk](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-arm64-v8a.apk) *(Optimal performance & compact download)*
- **Universal (All Architectures Combined):**  
  [Download AirSpeaker-universal.apk](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-universal.apk) *(Guaranteed compatibility across any phone or tablet)*  
  *(Universal alias: [AirSpeaker.apk](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker.apk))*
- **32-bit ARM (Older Android phones):**  
  [Download AirSpeaker-armeabi-v7a.apk](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-armeabi-v7a.apk)
- **64-bit x86 (Tablets, ChromeOS & Android Emulators):**  
  [Download AirSpeaker-x86_64.apk](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-x86_64.apk)
- **32-bit x86 (Legacy Emulators):**  
  [Download AirSpeaker-x86.apk](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-x86.apk)

> [!TIP]
> **Android Installation Tip:** When installing a downloaded APK, Android may prompt to allow installs from AirSpeaker or your file manager. Tap **Settings** and enable **"Allow from this source"**. AirSpeaker supports **Android 7.0+ (API 24+)** for low-latency playback and **Android 10+ (API 29+)** for internal system audio capture.

### iOS
- **Sideloadable IPA (iOS 16.0+):** [Download AirSpeaker-iOS.ipa](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-iOS.ipa)

### macOS
- **Universal Disk Image:** [Download AirSpeaker-macOS.dmg](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-macOS.dmg) *(Open the DMG and drag **AirSpeaker** into **Applications**)*

### Linux
Install (Arch, Ubuntu, Debian, Mint, Fedora, openSUSE):
```bash
curl -sSL https://raw.githubusercontent.com/HSAdib/AirSpeaker/main/install_linux.sh | bash
```
Direct x86_64 tarball: [Download AirSpeaker-Linux-x86_64.tar.gz](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-Linux-x86_64.tar.gz)

Uninstall:
```bash
curl -sSL https://raw.githubusercontent.com/HSAdib/AirSpeaker/main/install_linux.sh | bash -s -- --uninstall
```

## Updating Inside the App
Open the **Settings** tab on Windows, macOS, Linux, Android, or iOS:
1. Tap **Check for Updates**.
2. Tap **Download Update** to download the latest build at full speed with live progress and download speed.
3. Once complete, the file is saved in `Downloads/AirSpeaker` (on iOS: `Files → On My iPhone → AirSpeaker → Downloads/AirSpeaker`) with two buttons:
   - **Install Now** — launches the installer or sideload share sheet immediately.
   - **See in Files** — opens the `Downloads/AirSpeaker` folder directly to the downloaded package.

## Quick Start & Network Ports
- Connect devices to the same Wi-Fi, Personal Hotspot, or USB tether.
- Ensure inbound **UDP 5004** (audio stream) and **UDP 5005** (auto-discovery & 2-way settings sync) are allowed in your firewall.

> [!NOTE]
> All official release packages across all 5 operating systems are dual-published to [GitHub Releases](https://github.com/HSAdib/AirSpeaker/releases/latest) and the GitHub Container Registry (`ghcr.io/hsadib/airspeaker`).
