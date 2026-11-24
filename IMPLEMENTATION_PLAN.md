# Vibe Reader implementation plan

Statuses: **Complete** means implemented and checked; **Partial** means the existing feature covers some of the request or an external compatibility check remains; **Pending** means work remains. Backup/restore redesign is excluded. Extending its existing format to preserve new durable job/source data is included as a compatibility task.

**CI validation (2026-11-24):** [GitHub Actions run 45306202634](https://github.com/anthonys1998/Vibe-Reader/actions/runs/45306202634) succeeded on commit 85d6e59dfcbc063cd80eca5796514103f9986fcb. Unit tests and debug APK assembly passed, and the updated workflow uploaded [Paperlight-debug artifact 14880753430](https://github.com/anthonys1998/Vibe-Reader/actions/runs/45306202634/artifacts/14880753430) (51,632,502 bytes; SHA-256 `b9e6c0382ae9105997c17ee3b33f4312646dc26e6689f04d2fec8e0ffbfd5f9e`). Earlier run 45302865490 used the previous workflow and produced no APK artifact. Java and Gradle remain unavailable locally; signed-release and device verification are pending.

## 1. APK update continuity and release hardening — Partial

**Existing facts:** The application ID is com.paperlight.reader; the signing inputs are ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD, and ANDROID_EXPECTED_CERT_SHA256. Local builds default to version code 41; CI builds use workflow run number plus 40. The latest available APK artifact came from Actions run #66; it was a debug APK with version code 106 and Android Debug signer SHA-256 c62965fc3065cd32b7b6f84c2bddbc12f1e667a7951fe298f3882343409419fe. The user waived continuity with that old debug key. Version code 106 remains the minimum historical floor for signed releases; the old signer is not pinned.

**Changes in Phase 1:** Pushes and pull requests test and assemble debug. The workflow uploads a clearly named Paperlight-debug artifact when no signed build is produced; it retains the same application ID and may use a per-run debug key, so it may not update an installation signed with another key. On trusted direct pushes or an explicit signed-release dispatch from a trusted branch, a signed APK is uploaded only after package, version, and signer checks against ANDROID_EXPECTED_CERT_SHA256. Missing signing values on an ordinary push result in the debug artifact; an explicit signed-release request fails clearly if values are missing or the expected fingerprint is malformed. A key whose actual signer differs from the configured fingerprint fails closed. Manual releases require a version code greater than 106 and any installed version intended to be updated. The temporary keystore is removed by a shell trap. No key is generated or rotated, and secrets are not changed.

**Still unverified:** The run #66 signer certificate was extracted; the downloaded artifact ZIP matched GitHub's recorded artifact digest. The APK signature was not independently validated. The old artifact does not contain its private key, and continuity with it is no longer required. Stable keystore availability, signed artifact production, and all on-device install/update behavior remain unverified. The new debug artifact upload passed in run 45306202634.

**Checklist:**
- [x] Inspect the latest available Actions APK artifact identity and record its package, version code, and signer fingerprint.
- [x] Keep the application ID unchanged and upload a clearly named debug APK when no signed APK is produced.
- [x] Require signed APKs to match the configured expected certificate and use a version code above 106; remove caller-supplied signer/baseline overrides.
- [x] Make an explicit manual signed-release request fail clearly when signing credentials or a valid expected certificate are missing.
- [x] Check `git diff --check`, shell syntax for release scripts, embedded Python syntax, and validator accept/reject behavior.
- [x] Run CI unit tests and debug build; the workflow passed in run 45306202634.
- [x] Add a Paperlight-debug artifact fallback when a signed build is not produced; verify the step conditions and failure behavior statically.
- [x] Confirm the debug artifact was uploaded: [run 45306202634, artifact 14880753430](https://github.com/anthonys1998/Vibe-Reader/actions/runs/45306202634/artifacts/14880753430).
- [ ] Run the signed-release path with configured credentials and verify package, version, and signer.
- [ ] Verify APK installation/update behavior on devices; a debug APK with a different key may require uninstalling the existing app.

**Status:** Partial: CI tests, debug assembly, and the `Paperlight-debug` artifact upload passed in run 45306202634. Configured-signer release production and device installation remain unverified.

## 2. Durable website-to-EPUB conversion engine — Partial

**Existing behavior to preserve:** `WebNovelExportService` is a direct `dataSync` foreground service with progress/cancel notification and `START_REDELIVER_INTENT`. `WebNovelEpubUtility` processes chapters sequentially, writes each completed XHTML and URL atomically into app-private checkpoints keyed by URL and scrape options, retries transient errors and 429 responses, parses both numeric and HTTP-date `Retry-After`, and persists per-host cooldowns. Repository import uses a stable URL/options-derived ID to avoid duplicate generated books.

**Implemented in this slice:** Room v6 stores a stable job ID, serialized scrape options and destination, status, progress, error, cooldown deadline, checkpoint key, result book ID, book title, and timestamps; Room v7 adds the discovery source registry through migration 6→7. Room v8 adds runner kind, durable automatic-run intent for jobs already started by the user, and a backup-restore transaction marker. Claims register a process-local runner token under the same repository lock as the Room claim; startup reconciles only tokens absent from the live-process registry. Conditional progress/finalization keeps stale claims from publishing. Queued jobs run sequentially through the existing direct foreground service. A regular WorkManager CoroutineWorker also runs due automatic jobs and deferred 429 retries without starting a foreground service. It shares the Room lease and EPUB/checkpoint utility, yields after a newly downloaded chapter in a short slice, preserves cached chapters, and reschedules its next slice. User pause/cancel clears automatic intent and cancels tagged WorkManager work; restore is rejected while a conversion owns a runner lease and restored jobs are kept inactive.

**Backup and restore:** The root backup remains version 2 and is unchanged. An optional version 1 `webNovel` extension carries custom Discover source definitions and configuration, built-in source enabled flags, cached source results, durable conversion jobs, validated manifests and downloaded chapter checkpoints, and persisted host cooldowns. Existing version 1 and version 2 backups without this extension remain accepted. Restore merges rows additively with existing device rows taking precedence; settings and recognized built-in enabled flags follow the existing `replaceExistingSettings` option. Restored jobs are inactive (`autoRun=false`). A checkpoint collision preserves the local files and leaves the restored job visibly failed until the user confirms a reset. Restore journal version 3 records a transaction ID matched against a Room marker persisted atomically with restored data, distinguishing a committed extension-only restore from rollback even when the backup contains no books; staged files remain until the journal is cleared.

The checkpoint directory holds a bounded, versioned chapter manifest with the resolved TOC origin, title, cover URL, and ordered selected chapter URLs/labels. Retries use that snapshot and do not refetch the TOC. A corrupt/mismatched manifest fails visibly; the manager offers a confirmed reset that removes only the manifest, preserves per-chapter files, resets saved progress, and refuses while another job can share the checkpoint. Cooldowns longer than 60 seconds are synchronously committed before a job is deferred. WorkManager remains subject to Android background scheduling and quota limits, so start times can be delayed by the OS; no exact alarm or background-started foreground service is used. Migration, recovery, and Worker tests passed in CI; actual Android service/Worker execution, process-death behavior, and OS quota behavior remain unverified on device.

**Checklist:**
- [x] Preserve foreground execution, sequential chapter processing, bounded retry handling, per-host cooldowns, atomic checkpoints, and stable-ID import.
- [x] Persist job states, support pause/resume, and reconcile after process/service restart.
- [x] Add deterministic tests for exclusive claims, stale-lease rejection, pause/cancel recovery, and v4→v8 migrations; passed in CI run 45302865490.
- [x] Freeze and validate the chapter manifest, preserve order across changed TOCs, and provide a safe explicit reset route; tests passed in CI run 45302865490.
- [x] Add automatic deferred execution through bounded WorkManager slices with a shared lease, durable cooldowns, checkpoint reuse, and pause/cancel-safe intent; tests passed in CI run 45302865490.
- [ ] Verify cooldown, checkpoint retry, cancellation, and final-import behavior on Android devices.
- [x] Add backward-compatible source, settings, cache, job, checkpoint, and cooldown archive sections with collision-safe additive restore and marker-backed rollback; archive tests passed in CI run 45302865490.

**Status:** Partial: durable jobs, direct-service execution, frozen-manifest recovery, automatic WorkManager execution, and backward-compatible backup/restore passed automated CI tests; device and OS behavior remain unverified.

## 3. Persistent conversion job manager — Partial

Phase 2 created the Room job entity/DAO and flow. Phase 3 now provides a dedicated page with title/source, status, current chapter, completed/total/percentage, cooldown and errors, and start, pause, resume/retry, cancel, and confirmed manifest-reset actions. The page follows app-level Back navigation and leaves reader and file-manager routes intact. Foreground-service and Worker runners share an exclusive persisted lease, and startup reconciles orphaned tokens without invalidating a live Worker. Automatic work remains enabled only after a user starts or resumes a job; pause, cancel, terminal completion, and restored jobs clear that intent.

The Room schema is now version 8 with explicit migrations and no destructive fallback. Migrations 4→5 add jobs; 5→6 add nullable book titles and backfill completed jobs from existing books; 6→7 adds the discovery source registry; 7→8 adds runner type, automatic-run intent, and restore markers. Robolectric tests cover job claims, Worker/foreground lease exclusivity, pause/cancel transitions, orphan recovery, migrations, source lifecycle, and WorkManager scheduling; they passed in CI run 45302865490. A targeted SQLite smoke check of the Room migration SQL also passed. Repository publication uses the existing library-operation serialization without holding it during network reads. Restore refuses to begin while a runner lease is active.

**Backup compatibility:** The unchanged root format remains version 2 and carries an optional version 1 `webNovel` extension. Existing root version 1 or version 2 backups without the extension remain valid. The extension includes custom Discover source definitions/configuration and cached results, built-in source enabled flags, durable jobs, chapter manifests and downloaded checkpoints, and host cooldowns. Restore merges rows additively with existing device rows winning; settings and recognized built-in enabled flags follow the existing `replaceExistingSettings` option. Newly restored jobs always have automatic execution disabled. A job whose checkpoint key collides with local files is restored as a visible failure without overwriting those files, and can be reset only after confirmation. Restore journal version 3 records a transaction ID matched against a Room marker inserted atomically with the restored data, so extension-only restores with zero books can be committed or rolled back unambiguously; staged resources remain until journal cleanup completes. Archive round-trip, legacy, corrupt/path/hash, collision, and marker rollback tests passed in CI run 45302865490.

**Status:** Partial: the dedicated manager page, durable controls, and backup/restore integration passed automated CI tests; Android device/runtime validation remains.

**Checklist:**
- [x] Add the v4→v5 jobs-table and v5→v6 title migrations with deterministic job state storage.
- [x] Queue jobs sequentially by default and persist waiting, interrupted, paused, cancelled, failed, and completed states.
- [x] Add a basic manager with counts/status/progress and pause/resume/retry/cancel controls.
- [x] Replace the temporary dialog with a dedicated, back-navigable manager page and persist job titles from the frozen manifest.
- [x] Reconcile stale execution leases and reject restore during an active runner lease.
- [x] Register jobs with existing backup/restore compatibility and add merge, recovery, and job/source consistency tests; passed in CI run 45302865490.

## 4. Discover sources, website detection, and converter handoff — Partial

**Implemented:** Standard Ebooks is the default public HTML catalog with Latest, Popular, Browse, paging, and title/author search. Project Gutenberg remains optional. Room migration 6→7 creates a source registry and seeds built-ins only for new databases or existing installs at migration time; custom sources can be detected, added, edited, enabled/disabled, refreshed, and deleted. The catalog client parses Standard Ebooks cards, Atom OPDS acquisitions/navigation/next links, and configurable HTML card selectors; it keeps one bounded cache per source, validates HTTP(S) URLs/redirects, bounds responses and images, and rejects invalid EPUB downloads before import. Custom source purpose controls whether Add downloads an EPUB or opens the website-to-EPUB converter; built-in catalogs keep direct download behavior. Fixture tests cover Standard metadata/paging, namespaced OPDS navigation/acquisitions, custom HTML cards, cache bounds/full URL round-trip, and EPUB MIME entry bounds; migration and source lifecycle fixtures are also present.

**Additional Phase 4 implementation:** Public HTML detection now scores bounded repeated card containers, skips navigation/pagination/comment/social regions, infers title/detail/author/lazy-cover selectors, and previews up to four parsed titles. Detected selectors and optional search, Latest, and Popular same-origin URLs remain editable; a Preview action runs the current saved-in-editor configuration. HTML sources can explicitly opt into the existing `RenderedNovelPage` for JavaScript listings and details. Discovery snapshots are capped at 1 MiB in UTF-8 and JavaScript characters, run for at most 25 seconds, validate the final HTTP(S) URL, and are destroyed on cancellation. HTTP 401/403 and recognized sign-in/browser challenge pages fail visibly; the WebView is not used as a retry to bypass those responses. The converter's default 8-million-character renderer limit is preserved. Old `configJson` values decode with JavaScript rendering off, no custom mode URLs, and no inferred-region filtering, preserving manual selector behavior; the discovery registry is still created by migration 6→7, while Room v8 adds the job and recovery fields.

**Backup compatibility:** Source definitions/configuration, cached results, and built-in enabled flags are included in the optional `webNovel` backup extension described in Phase 3. Older backups without that extension remain supported; additive restore preserves existing source rows, while settings and recognized built-in enabled flags follow the existing `replaceExistingSettings` option. Archive tests passed in CI run 45302865490.

**Remaining work:** OPDS 2 JSON, credentials, protected catalogs, and access-control bypass are unsupported. Heuristic detection and WebView behavior still need Android device verification; this detector is best-effort and custom selectors remain editable for unsupported page structures.

**Checklist:**
- [x] Make Standard Ebooks the default and retain optional Project Gutenberg.
- [x] Add source migration/initial seeding, source CRUD, enable/disable, refresh, and Browse.
- [x] Add public Standard latest/popular/search/paging, custom Atom OPDS and editable HTML selectors, bounded result cache, validation, and purpose-based direct-download/converter Add routing.
- [x] Add parser, cache, EPUB validation, migration, and source lifecycle fixture tests (passed in CI run 45302865490).
- [x] Add backward-compatible backup/restore for source definitions, cached results, and built-in enabled flags (tests passed in CI run 45302865490).
- [x] Add best-effort repeated HTML card/title/link/cover/search/Latest/Popular detection, editable selector/mode configuration, parsed-title preview, and explicit bounded reuse of the rendered WebView fallback (fixtures passed in CI run 45302865490).
- [ ] Verify Discover network errors, custom source lifecycle, WebView fallback, and converter handoff on Android devices.

**Status:** Partial: source management, built-in feeds, parsers, caching, EPUB validation, converter handoff, HTML detection/preview, the explicit bounded renderer fallback, and backward-compatible backup integration are implemented; Android device execution remains.

## 5. Export/share and editable AI summaries — Partial

### Book export/share — Partial

Single-book export uses Android's document picker with the stored original filename as the suggested name; the selected destination can be renamed there. Export copies the original file bytes without conversion. The one-book selection overflow and a new book-details dialog both expose Export and Share. The details dialog shows title, author, format, filename, and reading progress. Sharing uses the actual library file through `FileProvider`, adds read-grant `ClipData` labeled with the original filename, and includes the title/format in the chooser title. MIME types are shared between export and share; EPUB, PDF, TXT, CBZ, and CBR have specific types, while MOBI/AZW3 and unknown formats use `application/octet-stream`. Export looks up the selected book from Room under the existing library-operation lock, validates and opens the source before opening the destination, and reports errors through the existing message/snackbar path. No multi-selection export/share requirement is part of this plan.

**Checklist:**
- [x] Existing single-book export/share flow.
- [x] Add a one-book options menu and book-details surface with Export and Share actions.
- [x] Preserve the original filename in the document-picker suggestion, use actual source bytes, centralize supported MIME types, add FileProvider read grants/ClipData, and handle missing source/write failures.
- [x] Add Robolectric checks for byte-for-byte export and for missing/deleted sources leaving an existing destination untouched (tests passed in CI run 45302865490).
- [ ] Verify document picker rename/destination, supported formats, URI receiver access, and failure feedback on Android devices.

### Editable AI note summaries — Complete implementation, validation pending

The notes screen already allows editing and saving a generated summary locally. A manually edited summary triggers a confirmation before regeneration replaces it. Summary metadata includes the model, timestamp, edit flag, and source note IDs; existing backup preferences include saved summaries. Preserve that behavior and test save, cancel, confirm-replace, and backup round-trip; do not build a duplicate editor.

**Checklist:**
- [x] Editable local summary and confirmation before replacing manual edits.
- [ ] Verify save, cancel, confirm-replace, and existing backup round-trip behavior.

## Verification checklist

- [x] CI run 45306202634 passed unit tests and debug assembly and uploaded [Paperlight-debug artifact 14880753430](https://github.com/anthonys1998/Vibe-Reader/actions/runs/45306202634/artifacts/14880753430).
- [ ] Run the signed release path with configured credentials; validate package/version/signer and verify installation behavior on device.
- [x] Phases 2–3: add tests for exclusive claims, stale-token progress/finalization, process-death pause reconciliation, v4→v8 migrations, shared Worker/foreground leases, pause/cancel intent, cooldown persistence, Work scheduling and cached Worker import, changed-TOC manifest reuse, redirect origin validation, corrupt-manifest reset, cache preservation, and backup round-trip/legacy/path/hash/collision/rollback behavior (backup tests passed in CI run 45302865490).
- [ ] Verify numeric/date 429 cooldowns, atomic checkpoint retry, cancel/retry, service process-death recovery, duplicate prevention/idempotent import, and backup/restore job/source compatibility on Android devices.
- [x] Phase 4: add fixtures for Standard Ebooks, custom OPDS/HTML parsing, cache bounds, EPUB validation, source lifecycle/migrations, repeated-card selector inference, both heading/link layouts, lazy covers, mode/search configuration, challenge rejection, and rendered-document URL/size bounds (passed in CI run 45302865490).
- [ ] Phase 4: verify rendered catalog fallback, selector detection, cancellation, unsafe URLs, and converter handoff on Android devices.
- [ ] Phase 5: run Android checks for details/export/share, document picker rename/destination, MIME and URI access for each format; test summary edits and regeneration confirmation plus existing backup compatibility.

## Discover follow-up fixes — Partial

- [x] Standard Ebooks downloads use the `?source=download` response instead of its HTML interstitial; a live download was verified as an EPUB file.
- [x] Custom source purpose is independent of HTML/OPDS transport and defaults legacy sources to direct EPUB download. Add follows the saved purpose; Groq can suggest HTML listing selectors, and converter scraper settings are saved per website.
- [x] Cache discovery cover thumbnails and local book artwork in bounded memory; library rows can use cached local artwork.
- [x] Unit tests and debug build passed, and `Paperlight-debug` was uploaded in [Actions run 45333320154](https://github.com/anthonys1998/Vibe-Reader/actions/runs/45333320154).
- [ ] Verify purpose-based Add routing, saved converter profiles, and cover display on an Android device.

**Status:** Partial: implementation, live Standard Ebooks file download verification, and follow-up CI are complete. Device checks remain.
