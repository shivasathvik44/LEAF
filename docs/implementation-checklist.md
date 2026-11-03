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
- [x] Phase 2: offline EPUB/PDF Fragment rendering, complete Locator persistence,
  restoration, chapters, appearance, immersive controls verified.
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
- Real native Readium fragment rendering verified for standard EPUBs, image-heavy EPUBs,
  RTL EPUBs, fixed-layout EPUBs, ordinary vector PDFs, and scanned image PDFs.
- Native PDF ink rendering verified via pixel inspection on Pdfium surface; page navigation
  and state recreation verified across configuration changes.
- EPUB WebView text display verified for body content and chapter traversal.
- Full Locator JSON round-trip retains exact text fragments, hrefs, progression, and positions.
- Process death and configuration change restoration verified using `ReaderRestorationFactory`,
  preventing duplicate native fragment construction and publication resource leaks.
- ReaderViewModel integration test confirms asynchronous conflated position writer
  persisting exact locations to Room and DataStore without race conditions across rapid re-open.
- Bookmark creation with optional labels, listing, deletion, and full-locator navigation verified.
- Immersive mode system bar transitions, typography customizations (font size, font family,
  line spacing, margins, continuous scroll, themes) verified.

