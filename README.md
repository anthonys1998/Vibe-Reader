# Vibe Reader

Vibe Reader is an Android ebook library and reader. Books are imported into app-private storage, and the library, reading progress, notes, and settings are stored locally. No account or cloud sync is required for reading.

## Features

- Read EPUB, PDF, TXT, MOBI, AZW/AZW3, CBZ, and CBR books. DRM-protected books and KFX are not supported.
- Organize books in named libraries; search, sort, filter, and track reading progress.
- Use EPUB contents, bookmarks, highlights, notes, text search, text-to-speech, adjustable reading controls, and accessibility settings. PDF and comics support page navigation and zoom.
- Discover books from Standard Ebooks, optional Project Gutenberg, and configurable OPDS or HTML sources. Review discovered HTML books in the website-to-EPUB converter before importing.
- Queue website-to-EPUB conversions, pause/resume/cancel them, and recover from interruptions using saved chapter checkpoints. Android may delay background work.
- Back up and restore books, reading data, settings, discovery sources, and conversion checkpoints. Export a book in its original format or share its original file. Including the Groq API key in a backup requires a password and encrypts the key.
- Generate note summaries with Groq, then edit and save them locally. Summary generation uses the network; saved summaries can be read and edited offline. Regenerating after manual edits requires confirmation.

## Build locally

Open this repository in Android Studio and allow Gradle to sync. The project uses Kotlin, Jetpack Compose, Room, and Android WebView; it is configured for Android SDK 36, JDK 17, NDK 28.2.13676358, and CMake 3.22.1. There is no Gradle wrapper in the repository, so use Android Studio's configured Gradle installation or an installed Gradle 9.1.0.

To run the unit tests and build a debug APK with Gradle installed:

    gradle :app:testDebugUnitTest :app:assembleDebug

The debug APK is written to app/build/outputs/apk/debug/app-debug.apk. Local builds use the app's default version code unless you pass -PappVersionCode=NUMBER.

## Download an APK from GitHub Actions

Open the **Actions** tab, select **Build Android APK**, and choose a run on the `anthonys1998-Groq` branch. After the run finishes, download its artifact from the run's **Artifacts** section:

- **Paperlight-debug** is uploaded for pull requests, other branches, and trusted builds where signing credentials are not configured. It uses the runner's debug key, which may differ between runs.
- **Paperlight-release** is uploaded only when a trusted push or explicit release dispatch builds a signed APK and its package, version code, and signer checks pass.

The earlier run 45302865490 has no APK artifact: its workflow built and tested the debug APK but only uploaded a signed artifact, and signing credentials were unavailable. Runs using this updated workflow upload Paperlight-debug when a signed build is not produced.

Both variants use the same application ID, `com.paperlight.reader`. Android will not install an APK over an existing app if the signing keys differ. Before switching to a differently signed debug APK, export a backup from the app; replacing the installed app may require uninstalling it, which removes its private app data.

For a signed release, configure the four Android keystore secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`) and `ANDROID_EXPECTED_CERT_SHA256`. Trusted pushes to `main` or `anthonys1998-Groq` can produce a release when those values are present. A manual signed-release request must be dispatched from one of those branches; it requires the same values and fails clearly when they are missing or invalid. The APK signer must match the configured expected fingerprint. Release version codes must be greater than 106 and greater than the version code of any installed APK you intend to update. The workflow does not pin the signer to the older run 66 debug artifact. Pull requests do not receive signing secrets.

The latest recorded CI run passed unit tests and the debug build, but APK installation, signed-release production, background scheduling, and behavior on physical devices have not been verified here.

## Data and privacy

- Imported books and reader data are kept in app-private storage. Uninstalling the app removes them; make a backup first if you need to keep them.
- Reading and library management work locally. Network access is used for Discover, requested website-to-EPUB conversion, and AI summary generation when configured.
- See the licenses and author notices in `app/src/main/cpp/libmobi` for the bundled libmobi source. Its encryption support is disabled in this build.
