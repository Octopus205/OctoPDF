# OctoPDF

**Offline-first PDF reader and editor for Android phones and tablets.**

OctoPDF is built with Kotlin, Jetpack Compose and Android's native PDF renderer. The project targets Android 8.0 (API 26) and supports 32-bit and 64-bit ARM devices.

## Current development status

This repository is being bootstrapped from a clean start. The first milestone establishes a buildable Android app, adaptive Material 3 shell, document picker, recent-document persistence, and native PDF viewing. Editing, page operations, multi-document tabs, and split view will be added in subsequent milestones.

## Principles

- Fully offline: no INTERNET permission and no network dependency.
- Android Storage Access Framework for user-selected documents.
- Native `PdfRenderer` for rendering, avoiding an AGPL viewer dependency.
- Kotlin, Jetpack Compose, Coroutines, Flow, and DataStore.
- Phone/tablet adaptive UI and Arabic RTL support.
- Keep memory usage controlled on older devices.

## Build

Open the project in Android Studio and run the `app` configuration, or use the GitHub Actions workflow to build a debug APK.

## Planned milestones

1. [x] Repository and initial project scaffold
2. [ ] PDF document picker and viewer
3. [ ] Recent files, bookmarks, and reading position
4. [ ] Search, thumbnails, and reading themes
5. [ ] Annotation tools and save-as-copy
6. [ ] Page management and PDF utilities
7. [ ] Multi-tab and split-screen experience
8. [ ] Automated tests and release hardening

## License

Project licensing will be finalized after reviewing all dependencies. Native Android framework components are used where practical.
