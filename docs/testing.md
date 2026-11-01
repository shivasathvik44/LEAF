# Testing and verification

## Recorded results

No test, device, lint, CI, or release result is claimed by this initial document. Record commands, toolchain/device versions, exit status, report locations, and known gaps after each milestone. A configured workflow is not evidence that GitHub Actions has run successfully.

## Commands

Run from the repository root with JDK 17 and the configured Android SDK installed:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

The last command requires an available emulator or authorized Android device. Inspect reports under `app/build/reports/` and XML results under `app/build/test-results/` or `app/build/outputs/androidTest-results/`. A failed assertion or build must fail the job; do not skip tests, weaken assertions, or use `continue-on-error` to obtain a green result.

## Required coverage

| Layer | Scenarios |
| --- | --- |
| Unit | SHA-256 and duplicate policy; input validation; metadata fallback; locator serialization and progress bounds; bookmarks; highlights and notes; shelf operations; session accounting and daily statistics; preference persistence |
| Room instrumentation | Foreign keys, cascades, unique hashes, many-to-many membership, transactions, schema migration and existing data preservation |
| Compose/navigation | Main destinations, full-screen reader, book-ID arguments, loading/empty/error states, back behavior, font scaling and touch targets |
| Reader instrumentation | EPUB and PDF rendering offline, page/chapter navigation, real text selection, highlight decorations, search results and navigation, saved-position restoration, orientation and process restoration |
| Reliability | Interrupted imports, corrupt/protected content, missing metadata/covers, duplicate imports, large files, low storage, finalization failures, orphan recovery, delete confirmation and source-file preservation |
| Privacy | Merged manifest permissions/components, backup/extraction exclusions, dependency audit, offline operation, absence of book/notes/private paths in release logs |

## Fixture policy

Use original or legally redistributable publications. Record each fixture's author/source, license, and purpose alongside it. Do not commit copyrighted personal books, private notes, or database snapshots. Include standard, image-heavy, RTL, and fixed-layout EPUBs; ordinary and scanned PDFs; malformed files; and reproducibly generated large files. Do not represent an arbitrary ZIP as a valid EPUB or a file extension as proof of format.

## Device procedure

1. Start with an empty installation and import multiple EPUB/PDF files through the system document picker. Re-import one to verify duplicate handling.
2. Read in airplane mode, navigate chapters/pages, change EPUB typography and orientation, then reopen each book at its full saved locator.
3. Kill and restore the process, including during import and active reading; verify no inflated sessions or partial library entries.
4. Select EPUB text, add several highlight colors and notes, restart, verify visible decorations, edit/delete records, and navigate from saved annotations and search results.
5. Exercise bookmarks, shelves, sorting, filters, list/grid, and completion status across restarts.
6. Background the app, lock the device, and leave the reader idle; verify that these intervals do not inflate reading time. Check midnight boundaries.
7. Remove a library entry with each confirmation choice; verify that the original source remains intact. Delete a shelf and verify its books remain.
8. Inspect TalkBack descriptions, large system fonts, contrast, focus, touch targets, and system bars on API 24 and API 37. Test PDF rendering on a 16 KB page-size environment in addition to ordinary 4 KB devices; verify all packaged native ELF libraries and APK ZIP alignment.

## CI and release

The Android CI workflow installs `platforms;android-37.0` and `build-tools;37.0.0`, builds the app, runs JVM unit tests and lint, and runs instrumentation on emulator API levels 24 and 35. An app compiled against API 37 can run on API 35; API 37 device validation remains a separate release check. Reports are retained even when a check fails. Workflow configuration must be validated on GitHub before CI is reported as passing. The regular emulator matrix does not by itself establish 16 KB page-size compatibility.

Before release, repeat the full fixture and device matrix, inspect the merged release manifest and dependency licenses, verify signing with owner-supplied credentials, and record the APK checksum and output path. An unsigned release artifact or debug-signed APK is not a production signed release.
