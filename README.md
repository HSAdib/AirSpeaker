# Air Speaker

Low-latency wireless audio streaming across Windows, Android, iOS, macOS, and Linux.

## Download

### Windows
- **Installer (Recommended — Start Menu & Windows Apps):** [Download AirSpeaker-v1-Setup.exe](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-v1-Setup.exe)
- **Portable (Standalone — No Install):** [Download AirSpeaker-v1-Portable.exe](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-v1-Portable.exe)

### Android
Select the APK that matches your device architecture:
- **64-bit ARM (Recommended for modern phones):**  
  [Download AirSpeaker-v1-arm64-v8a.apk](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-v1-arm64-v8a.apk) *(Optimal performance & compact download)*
- **Universal (All Architectures Combined):**  
  [Download AirSpeaker-v1-universal.apk](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-v1-universal.apk) *(Guaranteed compatibility across any phone or tablet)*  
  *(Universal alias: [AirSpeaker-v1.apk](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-v1.apk))*
- **32-bit ARM (Older Android phones):**  
  [Download AirSpeaker-v1-armeabi-v7a.apk](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-v1-armeabi-v7a.apk)
- **64-bit x86 (Tablets, ChromeOS & Android Emulators):**  
  [Download AirSpeaker-v1-x86_64.apk](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-v1-x86_64.apk)
- **32-bit x86 (Legacy Emulators):**  
  [Download AirSpeaker-v1-x86.apk](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-v1-x86.apk)

> [!TIP]
> **Android Installation Tip:** When opening the downloaded APK, Android may ask to allow installs from your browser or file manager. Tap **Settings** and toggle **"Allow from this source"**. Air Speaker supports **Android 7.0+ (API 24+)** for low-latency AAudio/Oboe playback, and internal audio capture on **Android 10+ (API 29+)**.

> [!NOTE]
> All official release packages across all 5 operating systems are dual-published to [GitHub Releases](https://github.com/HSAdib/AirSpeaker/releases/latest) and the GitHub Container Registry (`ghcr.io/hsadib/airspeaker`).

### iOS
- [Download AirSpeaker-v1-iOS.ipa](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-v1-iOS.ipa)

### macOS
- [Download AirSpeaker-v1-macOS.dmg](https://github.com/HSAdib/AirSpeaker/releases/latest/download/AirSpeaker-v1-macOS.dmg) *(Open the DMG and drag **Air Speaker** into **Applications**)*

### Linux
Install:
```bash
curl -sSL https://raw.githubusercontent.com/HSAdib/AirSpeaker/main/install_linux.sh | bash
```
Uninstall:
```bash
curl -sSL https://raw.githubusercontent.com/HSAdib/AirSpeaker/main/install_linux.sh | bash -s -- --uninstall
```
