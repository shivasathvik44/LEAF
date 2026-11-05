# Leaf implementation checklist

## Initial assessment — 2026-11-01

The workspace was inspected before changes. It contained only empty, protected
`.git`, `.agents`, `.aws`, and `.codex` directories. There were no source files,
Gradle files, tests, Git commits, branches, or user changes to preserve. Git was
initialized on `main` with the environment's approved elevated execution.

JDK 17, Gradle 8.7, and Android SDK 34 are available locally. The system Java is
25 and must not be used with the selected Android toolchain. Dependency network
access requires elevated execution; public Maven access has been verified.
Readium EPUB/PDF integration, dependency compatibility, PDF licensing, device
availability, and release signing are the principal verification risks.

## Acceptance gates

Only mark an item verified after the associated behavior/test passes. Compilation
alone does not verify rendering, annotation restoration, or daily usability.

- [x] Phase 0 local acceptance: compatible pinned toolchain, Compose/Hilt shell,
  privacy manifest, CI configuration, unit tests, build, lint and device navigation.
- [x] Phase 1: Room schema, safe managed-file import/quarantine, metadata/covers,
  duplicate handling, library/details/filter/sort/removal verified.
- [x] Phase 2 local reader gate: offline EPUB/PDF Fragment rendering, full Locator
  persistence, configuration restoration, chapters and appearance controls.
  Process-death and full immersive-UI checks remain release verification tasks.
- [x] Phase 3 local gate: bookmarks, rendered reflowable-EPUB highlights, note
  editing, EPUB search, saved-passage navigation, database/fresh-navigator reopen
  and configuration recreation verified. OS process-death remains a release check.
- [ ] Phase 4: shelves and active foreground reading statistics verified.
- [ ] Phase 5: reliability, migrations, accessibility, security/license review,
  documentation, tests, CI and installable release artifact verified.

### Phase 4 implementation — in progress, 2026-11-05

- Preserve the existing Room schema and all prior import, reader and annotation behavior.
- Connect custom shelves, descriptions, membership management and book-details selection.
- Count observed foreground reading only after native content renders; pause for
  background, window-focus loss, device lock, modal screens and idle readers.
- Persist closed checkpoints with stable retry IDs; display daily activity,
  calendar, reading history, started/completed books and streaks.
- Verify core accounting, database safety, real screens and native reader lifecycle
  integration before marking this milestone complete.

## Release constraints

No signing credentials were supplied. A signed release cannot be produced using
user credentials until the user supplies them through a private local mechanism.
Never invent credentials, commit a keystore, or report unexecuted CI/device tests
as passing. The owner supplied `https://github.com/shivasathvik44/LEAF` and will
push the repository themselves. Local `origin` is configured; no push was made.

## Verification log

Updated as commands execute; see `docs/testing.md` for the test strategy.

### Foundation build

- Selected Gradle 9.8.2, AGP 9.3.1, Kotlin/Compose compiler 2.4.20, JDK 17,
  compile/target SDK 37, build tools 37.0.0 and minimum SDK 24. Readium 3.4.0
  compatibility was checked against its tagged official source and published POM.
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: passed. All 35
  JVM tests passed; lint had zero errors and 10 dependency-update warnings.
- `:app:assembleDebugAndroidTest`: passed. Device execution is pending.
- Official Android 35 emulator image checksum verified. SDK/AVD use isolated,
  ignored workspace storage to avoid memory-backed temporary-storage pressure.
- API 35 foundation launch and navigation subsequently passed on official emulator
  36.1.9 using host graphics. Remote CI and production signing remain unverified.

### Library/import — verified 2026-11-03

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug
  :app:connectedDebugAndroidTest`: passed on API 35, x86_64, 4 KB pages.
- 54 JVM tests and 27 Android tests passed with zero failures/errors/skips.
  Lint: zero errors, 15 warnings. Tests include the real system document picker,
  10 Room tests and 15 real import/storage integration tests.
- Valid EPUB/PDF imports extract actual Readium metadata/covers, persist across
  database reopen, preserve original sources, repair missing/corrupt managed copies
  under the existing UUID, and retain annotations/progress during repair.
- Real SQLite insertion abort verifies promotion rollback/quarantine without
  destroying existing books or source documents. Quarantined files are preserved;
  a user-facing recovery vault remains a reliability-phase task.
- Enforced import limits: physical file 1 GiB; text resource 8 MiB; binary resource
  256 MiB; total expanded ZIP 4 GiB; 100,000 ZIP entries; central directory 16 MiB;
  metadata/navigation markup budget 25,000 tokens; UTF-8 title 16 KiB, author
  64 KiB, description 512 KiB. Rejections are explicit, without silent truncation.
- Logs are retained locally under ignored `.leaf-tools/verification/` and reports
  under `app/build/`. No production rendering/annotation claim follows from this
  import milestone; those are separate acceptance gates.

### Reader engine and navigation — verified 2026-11-03

- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug
  :app:connectedDebugAndroidTest`: passed on API 35, x86_64, 4 KB pages.
- 54 JVM tests and 35 Android tests passed with zero failures, errors, or skipped tests.
  Lint: zero errors, 15 warnings.
- This original run verified native standard-EPUB text and ordinary-PDF ink rendering.
  Image-heavy, RTL, fixed-layout and scanned-PDF fixtures were opened and their
  positions inspected; native rendering for those variants was added in the review below.
- EPUB WebView text display verified for body content and chapter traversal.
- Full Locator JSON round-trip retains exact text fragments, hrefs, progression, and positions.
- Activity recreation and orientation changes verified saved-chapter restoration
  without native-fragment constructor errors. These tests do not kill the OS process
  and do not establish general leak freedom.
- ReaderViewModel integration test confirms asynchronous conflated position writer
  persisting exact locators to Room across the tested rapid close/reopen sequence.
- Bookmark creation with optional labels, listing, deletion, and full-locator navigation verified.
- A combined font-size, line-spacing, margin and scroll change retained the chapter.
  Individual visual styling and immersive system-bar transitions were not asserted.

### Phase 2 review and fixes — verified 2026-11-03

- Kept the existing reader architecture; no full rewrite was necessary.
- Cancel pending seeks when closing/retrying the reader, ignore stale navigation
  results, and retain the publication until any suspended locator operation finishes
  or is cancelled. Two tests wrap the real Readium locator service with delayed
  delivery and verify actual container-close timing and cancellation propagation.
- Fixed-layout EPUBs preserve publisher typography and no longer offer reflow-only
  font/margin/scroll controls. PDFs show their current page and total page count,
  while keeping the original full Locator/progression as the saved source of truth.
- Added real native image-heavy, Arabic RTL and fixed-layout EPUB rendering tests,
  scanned-PDF artwork/page-restoration checks, and computed EPUB font-size/family
  assertions. Original assertions were preserved.
- Full gate passed: **54 JVM tests, 41 Android tests**, no failures/errors/skips,
  debug APK assembly, and lint with zero errors (16 warnings). Device: API 35,
  x86_64, 4 KB pages. Log: `.leaf-tools/verification/phase2-review-check-1.log`.
- Actual process death, production reader-chrome/system-bar behavior, complete
  theme/appearance visual QA, API 24/37 and 16 KB devices remain unverified.

### Annotations and search — verified locally 2026-11-04

- Integrated Readium's native reflowable-EPUB selection menu with persisted full
  selection locators, four highlight colors, rendered decorations and personal
  notes. Readers can browse annotations, return to the passage, edit notes/colors,
  and delete a highlight with its note. Saved decorations are reapplied to a new
  navigator. Fixed-layout EPUB selection is unavailable in the selected toolkit;
  its annotation panel explains this limit. PDF highlighting/search are excluded.
- Added actual EPUB publication search with contextual results, navigation,
  loading/empty/error states, cancellation and stale-result guards. Search work
  runs off the main thread and iterators close before publication leases release.
- Corrected Readium 3.4's backward-resource jump behavior: wait for a fresh public
  pagination callback for the target resource, then reapply the complete locator
  after its resource-page callback. Revisions and navigator generations handle
  rapid A → B → A requests without relying on debounced locator values. Saved
  annotations/bookmarks, chapters, progress and search share the same cancellable,
  publication-leased path.
- Added explicit input bounds: selection and note 64 KiB each, full locator
  256 KiB, bookmark label 4 KiB, measured as UTF-8 bytes without truncation.
  Search limits are 200 query characters in the UI, 512 in the integration,
  1,000 matches per resource, 5,000 overall and 4,096 characters of unbroken
  context extension. Retained results have a cumulative 8 MiB UTF-8 payload limit
  across full locator JSON, display titles and before/match/after context.
  Limit failures ask for a narrower phrase; reading stays open.
- Added isolated file-backed Room tests for full-locator/note restoration, stable
  IDs, cascading deletion, oversize rejection and real SQLite transaction aborts.
  Production reader UI coverage exercises real selection, color/note editing,
  recreation, search navigation and retry after a database failure. Strict native
  viewport/color assertions verify actual visible decorations, backward navigation
  from saved annotations, decoration activation and rapid chapter requests.
- Full gate passed: **57 JVM tests and 61 Android tests**, zero failures/errors/skips,
  debug APK assembly and lint with zero errors (16 warnings). Device: API 35,
  x86_64, 4 KB pages. Log: `.leaf-tools/verification/phase3-check-3.log`.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`;
  SHA-256 `b802488a61eafadea4fc8a4a5b21a423de06b1890385b2b064dcab10bbe79419`.
- Database reopening, fresh native navigator creation and configuration recreation
  do not establish OS process-death recovery. API 24/37, 16 KB devices, full
  accessibility, immersive system bars, complete appearance QA, remote CI and
  production release signing remain separate gates.

