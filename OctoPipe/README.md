# OctoPipe

Independent dark-first Android video client by Octopus. Built with Kotlin, Jetpack Compose, OkHttp and Media3.

## 0.3.0 — First-party engine rebuild

- Provider-neutral engine contract and central registry inside the app data layer.
- YouTube search through dynamic discovery of public Invidious-compatible API instances, health-sorted where available, with retries and fallback hosts.
- Real result cards with thumbnail, title, channel, duration and view count.
- YouTube playback resolution chooses the best available direct muxed MP4, falling back to HLS for playback.
- PeerTube public API search and playback.
- Media3 playback, background media session and Picture-in-Picture.
- Captions, SponsorBlock lookup, local favorites and history.
- Direct complete-file downloads through Android DownloadManager. The app explicitly disables offline download for HLS-only streams rather than saving a playlist as if it were a video.

## Architecture

Compose UI → VideoRepository → first-party provider adapters → OctoPipe domain models → Media3 / downloads / library.

No NewPipe or NewPipe Extractor dependency remains. Provider adapters can be replaced independently of the UI.

## Provider caveats

Invidious servers are community-operated public services and may be rate-limited, unavailable, or blocked. Instance discovery and retry reduce single-host failures but cannot guarantee third-party uptime. Some YouTube videos expose only adaptive/HLS streams; those can be played when supported, but cannot be offered as a single direct offline file by Android DownloadManager. No DRM, private, paid, or access-controlled content is bypassed.

PeerTube currently queries peertube.tv. Configurable PeerTube instances, adaptive stream selection, download queue management and more robust quality selection are follow-up work.

## Build

`cd OctoPipe && gradle :app:assembleDebug`

APK: `OctoPipe/app/build/outputs/apk/debug/app-debug.apk`

Minimum Android 8.0 (API 26). Debug application ID: `com.octopus.octopipe.debug`.
