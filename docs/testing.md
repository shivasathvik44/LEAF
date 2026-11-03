# Testing and verification

## Recorded results

### Foundation device verification — 2026-11-03

The preserved foundation app and instrumentation APKs were installed on an isolated Android 35 (Android 15), x86_64 emulator with ordinary 4096-byte memory pages. `NavigationTest.mainDestinationsAreReachable` passed: **OK (1 test)** in 2.461 seconds. A separate cold launch of `com.leaf.reader/.MainActivity` returned `Status: ok` in 1292 ms. The screenshot was visually inspected; Leaf branding and all four main navigation destinations were visible.

The executed device-test command was:

```sh
adb -s emulator-5556 shell am instrument -w -r \
  -e class com.leaf.reader.NavigationTest \
  com.leaf.reader.test/androidx.test.runner.AndroidJUnitRunner
```

These were the frozen foundation APKs retained under the ignored `.leaf-tools/phase0/` directory, not a claim that the current import or reader implementation has passed device tests. No personal books or library data were used. API 24, API 37, 16 KB page-size devices, importing, reading, annotations, and process restoration remain separate verification gates. GitHub Actions has not been verified by this local test.

The host's bundled SwiftShader graphics path crashed natively before Android boot with emulator 37.2.13 and 36.1.9. A native debugger stopped in `RenderThread` with bundled `gles_swiftshader/libGLESv2.so` frames. The working local configuration uses official emulator **36.1.9**, Linux build **13823996**, with host graphics and Vulkan disabled:

```sh
ANDROID_HOME="$PWD/.leaf-tools/android-sdk" \
ANDROID_SDK_ROOT="$PWD/.leaf-tools/android-sdk" \
ANDROID_AVD_HOME="$PWD/.leaf-tools/avd" \
.leaf-tools/emulator-36.1.9/emulator/emulator \
  -avd leaf-api35-e36 -port 5556 -no-window -no-audio -no-boot-anim \
  -gpu host -feature -Vulkan -memory 2560 -no-snapshot \
  -camera-back none -camera-front none
```

The [official emulator archive](https://developer.android.com/studio/emulator_archive) supplied the Linux archive, verified before extraction: 319203216 bytes; SHA-256 `5a580b0169e3fd26c99a67b86720f367f78828b1733d748cbe566e001b517024`. Host-specific graphics workarounds do not establish compatibility on other machines. This configuration preserves the isolated AVD's data and does not alter system-wide graphics or libc settings.

Local evidence is retained under the ignored `.leaf-tools/verification/` directory: `foundation-navigation-device.log`, `foundation-launch.log`, `android35-device-facts.txt`, `foundation-device.png`, and native emulator logs. The reproducible local helpers are `.leaf-tools/tools/start-emulator36.sh` and `.leaf-tools/tools/run-foundation-device-tests.sh`. The previously recorded JVM/build/lint results are tracked in the [implementation checklist](implementation-checklist.md).

### Library/import gate — 2026-11-03

`assembleDebug`, `testDebugUnitTest`, `lintDebug` and `connectedDebugAndroidTest`
passed together using the selected toolchain and the API 35 emulator described
above. The run executed **54 JVM tests and 27 Android tests**, with zero failures,
errors or skipped tests. Lint reported zero errors and 15 warnings. Raw local log:
`.leaf-tools/verification/phase1-check-3.log`.

Android coverage includes the real system document-picker launch/cancellation,
main navigation, 10 Room relationship/transaction/preservation tests, and 15
real import/storage tests. Import tests use actual Readium, Room and content URIs
from a debug-only non-exported FileProvider limited to test fixtures. They isolate
their storage and database, preserve source bytes, verify restart persistence,
duplicate and damaged-copy recovery, corrupt/large/unsupported inputs, explicit
private-copy removal, orphan quarantine, metadata bounds and a real SQLite
`RAISE(ABORT)` failure after file promotion. No fake rendering engine is used.

### Reader rendering & navigation gate — 2026-11-03

`assembleDebug`, `testDebugUnitTest`, `lintDebug`, and `connectedDebugAndroidTest`
passed together on API 35 (Android 15), x86_64 emulator (`emulator-5556`).
The run executed **54 JVM unit tests and 35 Android tests**, with zero failures,
errors, or skipped tests. Lint reported zero errors and 15 warnings.

Reader Android coverage includes:

- Standard-EPUB WebView text/chapter rendering and ordinary-PDF native ink rendering.
- Parser/position inspection for image-heavy, RTL, fixed-layout EPUBs and scanned PDFs.
- Full Locator round-trip serialization preserving href, progression, title, and text fragments.
- Activity recreation and orientation changes retaining the chapter/page without native-constructor errors.
- Production ViewModel + Room + DataStore integration with conflated asynchronous locator persistence.
- Bookmark creation, labelling, list observation, navigation, and removal.
- Chapter preservation while requesting combined font-size, line-spacing, margin and scroll changes.

`ActivityScenario.recreate()` is configuration recreation, not an OS process-death
test. This original run did not individually assert theme colors, font-family
rendering, immersive system bars or every format's native view.

### Phase 2 review gate — 2026-11-03

The same four Gradle tasks passed with **54 JVM tests and 41 Android tests**,
zero failures/errors/skips, and zero lint errors (16 warnings). Device remained
API 35, x86_64, 4 KB pages. Raw local log:
`.leaf-tools/verification/phase2-review-check-1.log`.

New real-reader assertions cover decoded illustrations in a visible EPUB WebView,
Arabic RTL content/chapter navigation, fixed-layout publisher-font preservation,
scanned-PDF raster pixels and page restoration, and computed reflowable EPUB
font-size/family changes. Two real Readium service/container tests delay locator
delivery to verify that closing a reader retains its publication until an active
seek completes, that cancellation releases the lease, and that close happens once.

The review also corrected unsupported fixed-layout appearance controls and added
PDF page-count display. Tests verify the underlying capability/page-count values;
they do not yet exercise the complete production Compose reader chrome.

Process death, immersive system-bar transitions, complete theme/appearance visual
QA, annotations/search, API 24/37, and 16 KB devices remain separate verification
gates. No CI execution or production release signing is claimed.

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
