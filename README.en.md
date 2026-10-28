# CamNexusRTSP

**Turn your phone into an RTSP camera on your local network.** Open the app, tap start,
and any computer, tablet, or NVR on the same network can pull the live feed like a
regular IP camera.

English | [简体中文](README.md)

---

## Why this exists

Phone cameras are far better than most home security cameras, but the system offers no
standard way to broadcast the camera feed. CamNexusRTSP runs an RTSP server on the phone
itself, encodes the camera image, and streams it out — any RTSP-capable player can watch
it directly, with no PC relay and nothing uploaded to the cloud.

## Features

### Video

- Full-screen live preview with resolution, frame rate, and viewer count on the status bar
- Switch camera, resolution, frame rate, and bitrate at any time — changes take effect in about a second
- H.264 and H.265, with hardware encoding preferred and a software fallback
- Multi-camera phones are detected automatically and labeled as ultra-wide / main / telephoto;
  duplicate logical cameras are merged

### Network and access

- Standard RTSP, works with VLC, ffplay, PotPlayer, and most NVRs
- Multiple viewers at the same time
- Optional authentication (username and password), enabled by default
- Configurable port, default 8554

### Running unattended

- Runs as a foreground service, keeps streaming when backgrounded or with the screen off
- Holds a high-performance Wi-Fi lock so the connection survives screen-off
- Power saving: drops to a lower resolution automatically when nobody is watching
- Built-in background-keep-alive shortcut tailored to common phone brands

### Getting started

- Four-page onboarding that explains the UI and checks camera, microphone, and notification permissions
- Device check page reads the phone's encoders, camera, and network capabilities and recommends settings

## Quick start

1. Download the APK from the repository's **Releases** page and install it
2. Open the app, tap **Start service**, and grant camera and microphone permissions
3. The app shows a stream URL, for example `rtsp://192.168.1.23:8554/`
4. Play it from any device on the same network:

```bash
# ffplay
ffplay -rtsp_transport tcp rtsp://192.168.1.23:8554/

# VLC
vlc rtsp://192.168.1.23:8554/
```

The default username and password are both `camnexus`; change them in Settings.

## Settings

| Setting | Description |
|---|---|
| Codec | H.264 / H.265, depending on player support |
| Encoder | Auto / hardware / software |
| Power saving | Lower quality automatically when nobody is watching |
| RTSP port | 1024–65535, default 8554 |
| Authentication | Toggle, username, password |
| Background keep-alive | Check battery optimization status and jump to system settings |
| Device check | Read hardware capabilities and recommend settings |

## Requirements

- Android 5.0 (API 21) or later
- Camera permission; microphone permission for audio; notification permission on Android 13+

## Build from source

Requires JDK 17 and the Android SDK (compileSdk 37).

```bash
./gradlew assembleDebug     # debug build
./gradlew assembleRelease   # release build (bring your own signing config)
```

> The first `gradlew` run downloads the Gradle distribution, which can be slow in some regions.
> You can point `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` at a mirror.

## License

Source code is released under the [MIT](LICENSE) license.

Runtime dependencies RootEncoder, RTSP-Server, and AndroidX are Apache-2.0;
see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for details.

The app name and icons are not covered by the MIT license — replace them if you redistribute.

