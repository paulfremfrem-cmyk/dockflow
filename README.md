# DockFlow

DockFlow is an Android companion app and LSPosed module for customizing social-app navigation. It provides a floating navigation dock for Instagram and Threads, plus a custom Reels scrubber with tap-to-seek and drag support.

## Features

- Floating navigation dock with a distinct dark, mint, and coral visual identity.
- Reels slider that tracks the active reel and supports tap-to-seek and dragging.
- Reels video fill and compositor blur treatments.
- Separate Instagram and Threads navigation preferences.
- A YouTube build flavor is also available.

Download hooks for Reels, Stories, and posts are not included.

## Build

Requirements: JDK 17 and Android SDK 35.

```sh
./gradlew :app:assembleInstagramDebug
./gradlew :app:assembleYoutubeDebug
```

The Instagram APK is written to `app/build/outputs/apk/instagram/debug/app-instagram-debug.apk`. The YouTube APK is written under `app/build/outputs/apk/youtube/debug/`.

## Install and activate

1. Build and install the APK for the target app flavor.
2. In LSPosed, enable **DockFlow** and select the target app in its scope:
   - Instagram: `com.instagram.android`
   - Threads: `com.instagram.barcelona`
   - YouTube (YouTube flavor): `com.google.android.youtube`
3. Force-stop and relaunch the target app.

The Instagram/Threads module application ID is `com.kinko.dockflow`; the YouTube flavor adds the `.youtube` suffix. If migrating from an older package ID, disable the old module to avoid both modules applying hooks at once.

## Inspirations and acknowledgements

- DockFlow is a heavily modified project inspired by the public **[SmitroniX/insta-](https://github.com/SmitroniX/insta-)** project. The original MIT attribution is retained in [`LICENSE`](LICENSE).
- Reels video-fill, UI-placement, and compositor-blur techniques are adapted from **[maciasdav/igglass](https://github.com/maciasdav/igglass)** under MIT.
- AGSL shader work is credited to **Kyant0's Backdrop / AndroidLiquidGlass** ([source](https://github.com/Kyant0/AndroidLiquidGlass)) under Apache-2.0.
- DockFlow was developed with assistance from **GitHub Copilot, Gemini, and DeepSeek**.

Third-party license notices are collected in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

DockFlow is an unofficial community project and is not affiliated with Meta, Instagram, Threads, Google, or YouTube.
