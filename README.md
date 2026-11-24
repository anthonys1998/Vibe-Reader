# Vibe Reader (Android)

Vibe Reader is an offline-first Android ebook reader project. Imported files are copied into the app's private storage; no account or cloud sync is used.

## Current milestone: 1.0

### Changes in 1.0

- Swipe left from the library to browse device folders and supported books directly on the second page. Grant file access once on first use, then tap a book to read it without importing.
- Selected books are copied to a temporary file for reading and removed when you leave the reader; no folder grant or library entry is needed.
- Add E-Ink display mode for reflowable books and PDF/comics: high-contrast black-on-white reader surfaces, no paper texture, and no page-turn animation. The option is available in Accessibility and reader settings.
- Extend EPUB and image-reader content beneath the transparent status bar so its background follows the page during turns; EPUB text has extra space at the top.
- Set the Android app version to 1.0.

### Changes in 0.11.5

- Add optional Gemini API support for website scraper selector suggestions and summaries of written reader notes. The app asks for a key only when an AI feature is used, and also provides Gemini key settings in the library menu. The key is encrypted in Android Keystore. Gemini receives sanitized samples for scraper suggestions and written notes for summaries; highlighted book passages are excluded. API quota and data handling follow the user's Google AI project settings.
- Swipe left from the library to browse supported books in a selected device folder and open a temporary copy without importing it into a library.

### Changes in 0.11.4
- The library title opens a dropdown for All books and each named library.
- Added Accessibility settings for app interface text size, default book text size and reduced-motion page turns.

### Changes in 0.11.2
- Large reflowable sections retain finger-tracked curl. Their sheet contains only measured visible text runs and media, avoiding a cloned full chapter.
- New chapters use saved typography on their first layout. Nearby chapter HTML is cached, and chapter snapshots use a bounded bitmap fold after target pagination has reported.
- PDF pages reuse a serialized renderer, bounded bitmap cache and adjacent-page prefetch. Controls overlay the page and provide thumbnails, a scrubber and explicit zoom reset.
- A single gesture handler distinguishes pinch/pan from a page swipe. While zoomed, dragging pans the page.
- Navigation regression harness passes, including the large-section no-clone case. Chromium visual checks are blocked by socket restrictions in this environment. Android compilation, actual MOBI performance and PDF gestures still require device validation.

### Changes in the previous milestone
- Shelf rows contain exactly three equal-sized covers. Each row owns its backdrop and bottom plank and scrolls as one layout item.
- The hamburger menu provides named libraries, import, recursive folder scanning, EPUB creation, reading statistics and local backup/restore.
- Create, rename and delete libraries. Long-press books for multiple selection, then move or delete them. A successful move opens its destination library.
- Sort by recent, title, author, progress or import date. Switch between shelves and a metadata list, and filter unread, reading or finished books.
- Imported EPUB metadata supplies title and author. Older EPUB entries with missing author metadata are refreshed.
- PDF/comic controls include hidden menus, page notes, bookmarks, jump-to-page, fit-to-width, fit-to-screen and continuous scrolling. Android 15+ also provides PDF page-text selection and page-linked highlights.
- Version 2 local backups include original books, libraries, reading progress, notes, bookmarks, supported app settings, reading statistics, saved note summaries and custom fonts. Version 1 backups remain restorable. Existing books are skipped; existing settings are preserved unless the user explicitly opts to replace them. Including the Groq API key requires a password-protected backup. Conversion jobs/checkpoints and custom discovery sources are not yet part of backup.
- Reading time counts only while the book screen is active and the app is resumed. Historical reading time is unavailable for earlier versions.
- EPUB creation displays chapter progress and can be cancelled.
- Turning page sheets stay opaque.

### Implemented in this source snapshot
- Native Jetpack Compose bookshelf with equal-sized covers, title search, a last-read marker, warm light and dark palettes, and per-book progress.
- Folder scanning lists supported files directly inside the selected folder, then imports only the checked files.
- EPUB Pages and Scroll modes share an approximate position. The selected mode is remembered. The reader and bookshelf show overall progress as a percentage. Pages mode also shows the current chapter’s page number and total, which change with the viewport and font size.
- Import of EPUB, PDF, TXT, MOBI, AZW/AZW3, CBZ and CBR files into app-private storage.
- Room-backed local library metadata.
- Initial DRM-free EPUB reader: reads EPUB 2/3 package metadata, follows spine order, loads XHTML chapters with local relative assets, and uses a horizontally paginated reading layout.
- EPUB 3 navigation document and EPUB 2 NCX table-of-contents parsing, with a chapter jump menu.
- Per-book EPUB bookmarks that can be added, reopened, and removed from the reader drawer.
- Long-press EPUB text to highlight it, attach a note, or look it up in Wiktionary in the browser. Highlights and notes are local to each book.
- The reader tools menu opens highlights and notes, searches the current EPUB, and reads the current chapter aloud using an installed Android offline voice.
- Reading controls start hidden. Double tap the text to toggle them, or choose Single tap from Reader settings.
- Adjustable font size, line spacing, margins and alignment. Choose a generic typeface or import a TTF/OTF font.
- Nested EPUB 2/3 contents and links to specific sections, with text-based position restoration during reflow.
- A minimal top bar, labeled bottom actions, and a dedicated highlights and notes screen.
- Light and dark reading palettes with optional paper textures.
- Accessibility options for larger interface and book text and reduced-motion page turns.
- Text-size adjustment and previous/next page/chapter navigation.
- Coarse reading progress persistence and restore (chapter plus approximate page fraction).
- Basic archive safety checks (path traversal rejection, entry-count and expanded-size limits) and stripping of publication scripts/inline event handlers before WebView rendering.
- More consistent page-width calculations and an attempt to preserve the reader's relative position during device rotation.
- App follows the system light/dark theme in the library.

- PDF page rendering, pinch zoom, panning and position restore.
- EPUB cover art and first-page PDF thumbnails on the bookshelf.

- TXT reading with UTF-8, BOM-marked UTF-16 and a Windows-1252 fallback.
- CBZ and CBR reading with naturally sorted pages, swipe page transitions, pinch zoom, light/dark backgrounds and paper textures. CBR supports standalone, unencrypted RAR4/5 archives within the decoder limits.
- DRM-free MOBI, AZW and AZW3 reflowable reading using local libmobi reconstruction. Navigation lists reconstructed sections and precision varies by original book structure. Print Replica and KFX are outside this reader’s scope.

### Not implemented yet
- Full fixed-layout EPUB rendering and a physically modeled page curl. The current turn uses a finger-tracked diagonal corner fold with shading.
- Broader accessibility and unusual-publication compatibility need device validation.

## Build

Open the `VibeReader` folder in Android Studio. The project uses Kotlin, Jetpack Compose, Room and Android WebView. Android SDK 36, JDK 17, NDK 28.2.13676358 and CMake 3.22.1 are configured. Let Android Studio sync Gradle and install any missing SDK components.

This source snapshot has not been compiled or device-tested in this environment. Treat it as an in-progress development build, not a release APK. The project currently does not include a Gradle wrapper; Android Studio can open it using its configured Gradle installation.

## Data and privacy notes

- Imported books are stored under the app's private files directory.
- Removing a book deletes its imported file and EPUB extraction cache.
- Uninstalling the app removes app-private imports. Export a local backup from the hamburger menu to retain books and annotations.
- Internet access is used only for user-requested website-to-EPUB downloads. Core reading and library management work locally.

## Build an APK on GitHub

GitHub Actions runs debug unit tests and compiles the debug variant on pushes and
pull requests. It does not upload that debug APK as an installable update.

Trusted direct pushes to main or anthonys1998-Groq can produce a
Paperlight-update artifact. The workflow signs it with the existing secrets
ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and
ANDROID_KEY_PASSWORD, then checks the package ID, version code, and signer
against the latest artifact baseline before upload. Pull requests and other
branches never receive the signing secrets. If the signing credentials are
missing, CI still runs tests and compiles debug, reports that no compatible APK
was published, and uploads no installable APK. A mismatched signer fails closed.

The latest successful Actions run when this baseline was recorded was run #66.
Its Paperlight-debug APK had package ID com.paperlight.reader, version code 106,
and signer SHA-256
c62965fc3065cd32b7b6f84c2bddbc12f1e667a7951fe298f3882343409419fe
(Android Debug). The artifact contains the certificate, not its private key.
An update to that APK requires signing credentials whose private key matches
that exact certificate; the workflow will not substitute another key.

For a manually requested release, use Actions → Build Android APK → Run
workflow on main or anthonys1998-Groq and enable Build a signed release APK.
Supply an explicit versionCode greater than 106. The same pinned certificate
check applies; there is no user-supplied certificate or version baseline that
can override it. The temporary keystore file is removed after signing.
Installing an update on a device has not been verified.

The bundled libmobi 0.12 source is in `app/src/main/cpp/libmobi`. Its license and author notices are included there. The build leaves libmobi’s encryption support disabled.
