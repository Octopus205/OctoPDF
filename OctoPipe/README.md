# OctoPipe

**An independent, dark-first Android video client by Octopus.**

OctoPipe is a separate Android application inside the OctoPDF repository. It has its own Gradle build, application ID, dependencies, and GitHub Actions workflow.

## Project status

Early foundation milestone (0.1.0):
- [x] Independent Android Gradle project
- [x] Kotlin and Jetpack Compose
- [x] Dark Minimal visual foundation
- [x] Adaptive navigation: bottom navigation on phones, navigation rail on wider screens
- [x] Home, Explore, Library, and Settings shells
- [x] Media3 playback dependencies
- [ ] Service provider / extractor implementation
- [ ] Search and video details backed by a real provider
- [ ] Playback, background audio, and Picture-in-Picture
- [ ] Downloads, subtitles, and SponsorBlock
- [ ] Local subscriptions, history, and playlists
- [ ] Additional service providers

## Build

From the repository root:

```bash
cd OctoPipe
gradle :app:assembleDebug
```

The debug APK is generated at:

`OctoPipe/app/build/outputs/apk/debug/app-debug.apk`

GitHub Actions workflow: **Build OctoPipe APK**. It only watches `OctoPipe/**` and its own workflow file. It does not build OctoPDF.

## Technical foundation

- Kotlin, Android Gradle Plugin, and Jetpack Compose
- AndroidX Media3 (ExoPlayer, UI, and MediaSession)
- Minimum Android version: 8.0 (API 26)
- Application ID: `com.octopus.octopipe.debug` for debug builds

## Important

This initial milestone is a UI and architecture foundation, not yet a functional video streaming client. Provider integrations and playback flows will be implemented and tested in subsequent milestones.
