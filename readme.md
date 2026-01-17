<div align="center">
  <img src="app/src/main/res/drawable/ic_lanflix_logo.png" width="80" height="80" alt="LANflix Logo"/>
  <h1>LANflix</h1>
  <p>
    <strong>An all-in-one local media app: run a Server to host your library and a Client to stream it across your LAN.</strong>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Language-Kotlin-7f52ff?style=flat-square" alt="Kotlin" />
    <img src="https://img.shields.io/badge/Platform-Android-3ddc84?style=flat-square" alt="Android" />
    <img src="https://img.shields.io/badge/License-MIT-fabf4a?style=flat-square" alt="License" />
    <img src="https://img.shields.io/badge/Status-Active-success?style=flat-square" alt="Status" />
  </p>
</div>

---

## Highlights

- Local-only streaming with no accounts or cloud services
- One app, two roles: Server mode and Client mode
- Fast library loading with metadata caching and progressive indexing
- Optional Server PIN for LAN access control
- Playback resume with configurable history retention

## What's New (Client + Server)

### Server Updates
- **Live indexing with progress**: scan status is visible while your library builds
- **Metadata cache**: instant list loads using a cached JSON index
- **Server dashboard**: uptime, speed, active devices, and streaming stats
- **Subfolder scanning toggle**: include or exclude subfolders on demand
- **Server PIN + name**: set a custom server name and optional PIN
- **Foreground service**: keeps the server running (notifications required)

### Client Updates
- **LAN discovery with health checks**: find servers automatically and verify readiness
- **PIN-aware connections**: prompts when a server is protected and stores per-host PINs
- **Strict host enforcement**: blocks external redirects for safety
- **Auto-reconnect**: remembers the last server and reconnects when available
- **Diagnostics panel**: quick logs you can copy for troubleshooting
- **Theme-aware UI**: server UI matches light/dark theme

## How It Works

1. **Server mode** hosts your local media folder using an embedded Ktor server (default port 8888).
2. The server **indexes files and builds a cache**, then publishes itself for LAN discovery.
3. **Client mode** discovers servers, checks readiness, and loads the server UI in-app.
4. Playback uses **Media3 (ExoPlayer)** with local resume history stored on-device.

## Getting Started

### Prerequisites
- Android device (Android 8.0+ recommended)
- Devices on the same Wi-Fi or local network
- Storage permission for media access
- Notifications enabled (required to keep the server running)

### Installation
1. Build the project with Android Studio or Gradle.
2. Install the APK on one device (server) and another device (client).
3. Grant requested permissions when prompted.

### Run the Server
1. Open LANflix and choose **Server Mode**.
2. Select your media folder (the app will remember it).
3. Tap **Start** to bring the server online.
4. Optionally set a **Server PIN** in App Settings.

### Run the Client
1. Open LANflix and choose **Client Mode**.
2. Select a server from the discovered list.
3. Enter the server PIN if required.
4. Browse the library and start streaming.

## Features

### Server
- Local library hosting with folder selection
- Progressive indexing with live status
- Metadata caching for faster load times
- Device stats: uptime, speed, active/streaming clients
- Subfolder scanning toggle and media management view
- Optional Server PIN and custom server name

### Client
- Automatic LAN server discovery
- PIN-protected connections
- In-app server UI with strict host enforcement
- ExoPlayer-based streaming with resume support
- Diagnostics log and auto-reconnect

### Playback Resume
- Saves progress after a short playback threshold
- Ignores near-finished videos for a cleaner history
- History retention is configurable (5/10/20/30 days or never)

## Technical Details

- **Architecture**: MVVM
- **Language**: Kotlin
- **Video Player**: androidx.media3 (ExoPlayer)
- **Database**: Room (SQLite)
- **Networking**: Ktor + LAN discovery
- **Dependency Injection**: Koin

## Releases and F-Droid
See `docs/RELEASING.md` for release tags and `docs/FDROID.md` for F-Droid submission notes.

## License
This project is licensed under the MIT License - see the LICENSE file for details.
