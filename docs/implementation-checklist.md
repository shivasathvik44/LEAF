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

- [ ] Phase 0: compatible pinned toolchain, Compose/Hilt shell, privacy manifest,
  CI, unit tests, build and lint pass.
- [ ] Phase 1: Room schema, safe managed-file import/recovery, metadata/covers,
  duplicate handling, library/details/filter/sort/removal verified.
- [ ] Phase 2: offline EPUB/PDF Fragment rendering, complete Locator persistence,
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
- Launch/navigation, remote CI and production signing remain unverified.
