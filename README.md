# AereNAS

**Turn your Android phone into a wireless NAS drive for Windows — zero cloud, zero subscriptions.**

*Aere* (Latin: air) — because it works over the air.

---

## What it does

- Runs a **WebDAV server** on your Android phone
- Maps your phone storage as a **drive letter (Z:)** on Windows — shows up in File Explorer
- **Auto-moves files** from a laptop folder to your phone on a schedule
- Works over **Wi-Fi or hotspot** — no USB, no cables, no third-party cloud
- Survives phone screen-off via foreground service

---

## Components

| Component | Description |
|---|---|
| `android/` | Kotlin Android app — WebDAV server |
| `windows/AereNASClient/` | C# .NET 8 tray app — drive mapper + sync |
| `windows/AereNAS-Sync.ps1` | Standalone PowerShell sync script |

---

## Quick Start

### Android
1. Download `AereNAS.apk` from [Releases](../../releases)
2. Enable "Install from unknown sources" and install
3. Grant storage and notification permissions
4. Tap the power button — server starts on port 8080
5. Note the IP address shown

### Windows
1. Download `AereNASClient.exe` from [Releases](../../releases)
2. Run as Administrator (needed once to fix WebClient registry)
3. Right-click tray icon → **Scan QR Code** or **Settings**
4. Enter your phone's IP, port, username, password
5. Click **Connect** — Z: drive appears in File Explorer

### Auto-sync Setup
In AereNASClient Settings:
- **Sync Source**: folder on your laptop to offload (e.g. `C:\Users\You\Downloads\ToOffload`)
- **Sync Dest**: destination on phone drive (e.g. `Z:\Backup`)
- **Interval**: how often to sync (default 15 min)
- Enable **Auto-sync**

Files are **moved** (not copied) — verified by size before source deletion.

---

## Build from Source

### Android
```bash
cd android
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### Windows Client
```bash
cd windows/AereNASClient
dotnet publish -c Release -r win-x64 --self-contained true /p:PublishSingleFile=true
# EXE at: publish/AereNASClient.exe
```

---

## Permissions (Android)

| Permission | Reason |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | Serve files from AereNAS folder |
| `FOREGROUND_SERVICE` | Keep server alive in background |
| `WAKE_LOCK` | Prevent sleep during active transfer |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot |
| `POST_NOTIFICATIONS` | Show server status notification |

---

## Known Limitations

- Transfer speed: 5–15 MB/s over Wi-Fi (hotspot similar)
- Windows WebClient service must be running (client handles this automatically)
- On Realme UI / OPPO: disable battery optimization for AereNAS (app prompts on first launch)
- HTTPS not supported in v1 — use on trusted networks only

---

## Roadmap (v2)

- [ ] Custom folder picker
- [ ] HTTPS with self-signed cert
- [ ] Transfer progress in notification
- [ ] Multiple sync profiles
- [ ] Android TV support

---

## License

MIT — do whatever you want with it.

---

*Built by [@Ishu1519](https://github.com/Ishu1519)*
