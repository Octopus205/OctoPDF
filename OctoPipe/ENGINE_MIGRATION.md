# OctoPipe engine migration plan

## Decision

Keep the existing OctoPipe application and replace its provider-specific extraction implementation behind an engine boundary. Do **not** embed the complete NewPipe Android application as the first migration step.

The first integration target is **NewPipe Extractor**, used as an independently consumable library. This preserves OctoPipe's own Compose UI, Media3 playback service, local library, navigation, branding, and download-management decisions while replacing the fragile Invidious-based YouTube extraction path.

This is an implementation choice, not a distribution commitment. GitHub Releases, F-Droid, and any store route remain undecided until licensing, packaging, and store-policy review is complete.

## Repository safety

- `app/` (OctoPDF) is out of scope and must not be edited.
- `.github/workflows/build-apk.yml` (OctoPDF) is out of scope and must not be edited.
- OctoPipe work is isolated on `feature/octopipe-newpipe-engine`.
- The pre-migration state is preserved on `backup/octopipe-pre-newpipe`.
- Do not create a Release as part of this migration. Use the OctoPipe debug artifact workflow only after a coherent batch is ready.

## Current OctoPipe baseline

The existing `OctoPipe/app` is a standalone Android application (application ID `com.octopus.octopipe`, minSdk 26, compileSdk 36, Java 17, Kotlin 2.3.0, AGP 8.13.2). It currently has:

- Compose-based dark/adaptive UI.
- Media3 player and a `MediaSessionService`.
- A `VideoRepository` that queries a small hard-coded list of Invidious instances for YouTube and the public PeerTube API.
- SponsorBlock lookup, captions, download helper, favorites, and history.

The current repository implementation couples provider HTTP/JSON details to the app data layer. That is the main migration seam.

## Why Extractor first, not the complete NewPipe app

The official NewPipe Extractor README explicitly says that the library can be used independently and documents a Gradle dependency. It supports YouTube, SoundCloud, media.ccc.de, PeerTube, and Bandcamp. The library's current source declares release version `v0.27.1` and Java 17 toolchains.

Using the extractor avoids importing NewPipe's full Android UI, navigation, settings, database, and application lifecycle into OctoPipe. It is therefore the smaller and more maintainable route to the requested independent OctoPipe identity.

The complete NewPipe application remains a reference implementation for service behavior and UX ideas, not the initial app foundation. Revisit a full fork only if later requirements cannot reasonably be met through the extractor and independently implemented OctoPipe features.

## Target architecture

```
OctoPipe app / Compose UI
        |
        v
  VideoCatalogRepository
        |
        v
    EngineRegistry
        |
        +---- NewPipeExtractorEngine   (first engine)
        |
        +---- AlternativeEngine       (reserved extension point)
        |
        v
  normalized OctoPipe domain models
        |
        +---- Media3 playback
        +---- downloads
        +---- captions
        +---- favorites / history
```

The UI must never import NewPipe Extractor classes. Extractor-specific models and exceptions are converted to OctoPipe-owned models at the adapter boundary.

Initial contracts should cover:
- Search by service and query.
- Resolve a video URL into stream variants, audio variants, captions, and metadata.
- Fetch channel/playlist data later without forcing those features into the first integration.
- A capability descriptor so unsupported features are explicit rather than silently failing.

The initial rollout should route YouTube through NewPipe Extractor while retaining the current PeerTube implementation until the new PeerTube adapter is verified. This allows staged migration and a rollback path.

## Compatibility requirements

- Keep minSdk 26 unless a concrete dependency makes that impossible.
- The Extractor README notes that Android apps with minSdk below 33 need core-library desugaring with the `desugar_jdk_libs_nio` artifact.
- Use the published Extractor release documented by its README; do not track a moving development branch in production builds.
- Implement NewPipe's `Downloader` interface using a maintained HTTP client, preserving redirects, request headers, POST bodies, response headers, status code, response body, and final URL.
- Initialize the Extractor once in the application process with the app-owned Downloader and explicit localization.
- Run extraction off the main thread. Do not block Compose or Media3 callbacks.
- Verify stream type/quality selection, adaptive audio/video handling, captions, and live streams independently. Do not assume that a returned URL is always a progressive MP4.

## Licensing and distribution gate

NewPipe and NewPipe Extractor are GPL-3.0-or-later projects. Before distributing an APK containing the Extractor, prepare the required license notices and corresponding source/revision information and review the applicable GPL obligations for the actual package.

The NewPipe README separately warns that placing NewPipe or a fork of it on Google Play violates Google's terms and conditions. That warning must not be casually generalized into a definitive legal conclusion about every possible Extractor-only integration; the exact app composition and current store policies need separate review. No store/distribution route is selected in this document.

## Migration stages

1. Preserve baseline and establish this isolated working branch. (Done.)
2. Add the engine contracts and a NewPipe Extractor adapter without changing the UI. (Next.)
3. Add the Extractor dependency, desugaring, and Downloader implementation; compile and test the adapter.
4. Route YouTube search and stream resolution through the adapter; keep PeerTube fallback unchanged.
5. Verify stream selection, audio/video pairing, captions, localization, and error mapping.
6. Migrate PeerTube to the Extractor only after feature parity is confirmed.
7. Rebuild UI-facing models and remove old Invidious code only after successful device testing.
8. Audit GPL notices/source delivery and decide distribution separately.

## Verification policy

A successful GitHub Actions build proves compilation only. It does not prove that YouTube extraction or playback works on a physical device. Keep those as separate checks, and do not mark the migration complete until both are tested.
