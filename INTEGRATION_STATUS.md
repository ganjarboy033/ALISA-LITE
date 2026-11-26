# Alisa Lite — integration status

## Implemented in this foundation
- Rebranded WebView interface to Alisa Lite and included supplied Alisa image as the app icon and in-app brand mark.
- Gradle Android project now declares `com.kruzenlab.alisalite` as namespace and applicationId.
- New six-section navigation: Beranda, Unduhan, Pustaka, Pemutar, Tools, Setelan.
- Existing Nimiyo–Nexora downloader/scraper, OTA bundle mechanism, history, HTML media player, mini-player, and Theme Studio retained.
- Local file picker can add audio/video to the existing playback queue and preview selected images in-app.
- GitHub Actions now builds the native Gradle project rather than patching the old prebuilt APK.

## Not yet integrated / not to be advertised as working
- Mori's Android native implementation is preserved under `third_party/Mori/` but has not been merged into `nx.B` / `nx.M`.
- Full MediaStore indexing and persistent gallery/folder browser.
- SAF folder selection and long-running background download service.
- Mori share overlay/floating bubble.
- Mori biometric/PIN lock and individual advanced tools (PDF/image/audio converters, lookup tools, etc.).
- Unified Mori + Nexora download engine and deduplicated platform adapters.
- Release signing with a stable private keystore. The workflow's debug signing is for CI testing only.

## Required validation after CI build
1. Install the generated APK on a test Android device and verify the installed package is `com.kruzenlab.alisalite`.
2. Verify share-to-app, clipboard, downloads, playback, back navigation, and theme persistence.
3. Integrate and test Mori native components one by one, including permission flows and Android-version behavior.
4. Configure a private release keystore in GitHub Secrets before distributing updateable builds.
5. Run license compliance review before publishing the combined application.
