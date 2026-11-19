# OctoPipe
Independent dark-first Android video client by Octopus.

## Milestone 0.2.0
- Independent Gradle project and isolated APK workflow
- Dark Minimal UI with adaptive phone/tablet navigation
- Temporary generated octopus launcher icon
- YouTube search and stream resolution through public Invidious-compatible instances
- PeerTube public API search and HLS/file playback resolution
- Media3 ExoPlayer owned by a MediaSessionService for background playback
- Picture-in-Picture support, including automatic entry during active playback
- SponsorBlock read-only segment lookup and automatic seeking over returned segments
- Provider caption tracks exposed as selectable subtitles
- Android DownloadManager direct-stream downloads into app-specific Downloads storage
- Local favorites and watch history

## Providers and limitations
YouTube uses community-operated Invidious instances, which can be rate-limited, blocked, or unavailable. PeerTube currently targets the public peertube.tv instance. Playback depends on public compatible streams. YouTube adaptive audio/video merging, robust instance discovery, and configurable provider instances are not implemented yet. HLS downloads are saved as playlist URLs, not complete offline media. Channel subscriptions/feed sync and download queue management remain future work. No DRM or private/paid content is bypassed.

SponsorBlock is queried read-only; OctoPipe does not automate submissions.

## Build
```
cd OctoPipe
gradle :app:assembleDebug
```
APK: `OctoPipe/app/build/outputs/apk/debug/app-debug.apk`

The Build OctoPipe APK workflow watches only `OctoPipe/**` and its own workflow file. It does not build OctoPDF.
Minimum Android: 8.0 (API 26). Debug application ID: `com.octopus.octopipe.debug`.
