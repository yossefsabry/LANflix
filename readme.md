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

- **Local Streaming**: No accounts, no cloud, just your local network.
- **Dual Role**: One app works as both a Media Server and a Streaming Client.
- **Subtitles & Casting**: Support for external subtitle tracks and Chromecast streaming.
- **Fast Indexing**: Quick library loading with metadata caching and progressive indexing.
- **Security**: Optional Server PIN for access control across your LAN.
- **Playback Resume**: Remembers where you left off with configurable history.

## What's New

- **Subtitles Support**: On-the-fly subtitle uploading and selection (SRT/VTT).
- **Chromecast Casting**: Stream your local library to any Chromecast-enabled device.
- **Server Dashboard**: Real-time stats for uptime, speed, and active devices.
- **Live Indexing**: Watch your library build with a real-time progress indicator.

## Data Flow: How It Works

LANflix uses a simple Server-Client architecture designed for high performance on local networks:

1.  **Discovery**: The **Server** broadcasts its presence on the local network using NSD (Network Service Discovery). The **Client** automatically scans for these broadcasts, allowing you to connect without typing IP addresses.
2.  **Indexing**: When you select a media folder, the Server indexes your files into a local **Room Database** and generates a fast-loading **Metadata Cache**.
3.  **Streaming**: The Server hosts an internal **Ktor web server**. The Client requests media via HTTP. High-bitrate video is streamed using **ExoPlayer (Media3)**, supporting features like range requests for smooth seeking.
4.  **Sync**: Playback progress, subtitles, and server settings are managed locally on each device, ensuring your viewing history is always up to date.

## Getting Started

### Prerequisites
- Android device (Android 8.0+)
- Devices must be on the same Wi-Fi or LAN
- Storage permission for media access
- Notifications enabled for the background server

### Installation
1. Build the project using Android Studio or Gradle.
2. Install the APK on your host device (Server) and playback devices (Clients).

### Quick Start
1. **Host**: Open LANflix, choose **Server Mode**, select your folder, and tap **Start**.
2. **Watch**: Open LANflix on another device, choose **Client Mode**, select your server, and start playing!

## Key Features

### Server
- Local library hosting with easy folder selection.
- Metadata caching for near-instant library loads.
- Detailed dashboard: see active clients and streaming speed.
- Optional Server PIN and custom server name for privacy.

### Client
- Automatic server discovery (no IP typing required).
- **Subtitles**: Load and switch between multiple subtitle tracks.
- **Casting**: Direct integration with Chromecast for big-screen viewing.
- Advanced playback with resume support and history management.
- Built-in diagnostics for quick troubleshooting.

## License
This project is licensed under the MIT License - see the LICENSE file for details.
