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
- [ ] Phase 3: bookmarks, rendered EPUB highlights, note editing, EPUB search,
  navigation and restart restoration verified.
- [ ] Phase 4: shelves and active foreground reading statistics verified.
- [ ] Phase 5: reliability, migrations, accessibility, security/license review,
  documentation, tests, CI and installable release artifact verified.

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

