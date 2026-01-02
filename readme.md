![LANflix Logo](app/src/main/res/drawable/ic_lanflix_logo.png)

# LANflix

LANflix is a local media streaming solution comprised of two main components: a Server for hosting and streaming media, and a Client for consuming content across the local network.

## Features

### Server
*   **Media Hosting**: Easily select a local folder to host video files (MP4, MKV, AVI, etc.).
*   **Auto-Discovery**: Automatically broadcasts its presence on the local network using UDP, allowing clients to find it without manual configuration.
*   **Thumbnail Generation**: Generates video thumbnails for a rich visual experience.
*   **Management Dashboard**: Organize files, scan subfolders, and manage server settings.

### Client
*   **Automatic Connection**: Scans and lists available LANflix servers on the network.
*   **Video Resume**: Automatically saves your playback position. Resume exactly where you left off, with configurable history retention (defaults to 10 days).
*   **Secure & Private**: Direct LAN connection without external internet dependency.
*   **Modern Player**: Built on ExoPlayer for robust playback support.

## Getting Started

### Prerequisites
*   Android Device (Android 8.0 Oreo or higher recommended)
*   Local Wi-Fi Network

### Installation
1.  Build the project using Android Studio.
2.  Install the APK on two devices (or use one device for both roles).
3.  Grant the necessary permissions (Storage, Notification).

### Usage

#### Setting up the Server
1.  Launch LANflix and select "Server Mode".
2.  Grant storage permissions when prompted.
3.  Tap "Select Folder" and choose the directory containing your movies or shows.
4.  The server will start automatically. You can monitor connected clients from the dashboard.

#### Connecting as a Client
1.  Launch LANflix on a second device and select "Client Mode".
2.  The app will automatically search for servers.
3.  Tap on your server name when it appears in the list.
4.  Browse the library and tap any video to start streaming.

## Technical Details

*   **Architecture**: MVVM (Model-View-ViewModel)
*   **Language**: Kotlin
*   **Video Player**: androidx.media3 (ExoPlayer)
*   **Database**: Room (SQLite) for history tracking
*   **Networking**: Ktor (Server) & UDP Multicast (Discovery)
*   **Dependency Injection**: Koin 

## Video Resume Feature
The application includes a robust video resume capability:
*   **Smart Saving**: Progress is saved only after 5 seconds of playback and ignored if the video is nearly finished (95%).
*   **Database Storage**: Uses a local optimized database to store playback history efficienty.
*   **Configurable**: Go to App Settings to change how long history is kept (5, 10, 20, 30 Days, or Never).

## License
This project is licensed under the MIT License - see the LICENSE file for details.
