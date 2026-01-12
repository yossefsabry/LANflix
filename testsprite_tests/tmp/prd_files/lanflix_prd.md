# LANflix PRD (Derived from README + Code)

## Summary
LANflix is an Android app that provides local network media streaming. It runs in two modes: Server (host local media on-device via an embedded HTTP server) and Client (discover/connect to servers and play videos). Streaming is local-only; no external services.

## Goals
- Allow a device to host a folder of video files for streaming over LAN.
- Allow clients to discover or manually connect to a server and stream videos.
- Provide a modern playback experience with resume history.
- Keep all data local and private.

## Non-Goals
- No external cloud streaming or remote access.
- No user accounts or authentication in the current scope.

## Core User Flows
1. First launch -> set username -> home screen.
2. Server mode:
   - Pick media folder (SAF).
   - Start server (HTTP on port 8888).
   - View server status, uptime, bandwidth, active connections.
3. Client mode:
   - Discover servers (NSD) or enter IP.
   - Open server web UI in WebView.
   - Tap a video to launch native player.
4. Playback:
   - Stream video using ExoPlayer.
   - Save resume position and restore on next play.

## Features
- Server
  - Media hosting from a selected folder.
  - NSD discovery broadcast (_lanflix._tcp).
  - Ktor server routes for library listing, tree browsing, status, and streaming.
  - Metadata cache to speed library queries.
  - Thumbnail generation.
- Client
  - NSD discovery list.
  - Manual IP connect.
  - WebView-based library UI from server.
  - Strict host enforcement for security.
  - Native player handoff for video URLs.
- Playback
  - ExoPlayer-based playback.
  - Resume history with retention settings.
- Settings
  - Theme selection.
  - Server name.
  - Privacy/security info.
  - Network info.

## Platforms and Constraints
- Android 8.0+ recommended.
- Local Wi-Fi network required.

## Data & Persistence
- Room database for media library entries and playback history.
- DataStore for user preferences (username, theme, retention, server name, selected folder).

## Success Criteria
- Client can discover and connect to server in LAN.
- Videos list loads quickly via cache.
- Playback resumes reliably.
- Server remains alive during background streaming via foreground service.
