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

### Annotations and search gate — verified locally 2026-11-04

`assembleDebug`, `testDebugUnitTest`, `lintDebug` and `connectedDebugAndroidTest`
passed together on API 35, x86_64, 4 KB pages. The final run executed **57 JVM
tests and 61 Android tests**, with zero failures, errors or skipped tests. Lint
reported zero errors and 16 warnings. Raw local log:
`.leaf-tools/verification/phase3-check-3.log`.

Phase 3 adds three JVM tests for exact annotation byte limits, seven isolated
file-backed Room tests, native EPUB annotation/search tests and four production
`MainActivity` reader UI tests. These tests use actual Readium, Room, DataStore and
Compose; they do not replace the renderer or selection with a fake implementation.
Native UI tests operate only on their own UUID book records and fixture files.

Verified coverage includes:

- Real long-press selection through the native Highlight action, persisted selected
  text/full locator, note editing, decoration removal and fresh native navigator
  restoration. Production UI assertions check the decoration's RGB color and
  intersection with the actual visual viewport, backward saved-passage navigation,
  and a real tap on the decoration opening its note editor.
- Exact UTF-8 size boundaries, full-locator/note persistence after database reopen,
  stable highlight/note IDs, note cascade, blank-note clearing and oversize rejection.
- Real SQLite `RAISE(ABORT)` failures that must roll back new highlights or color
  changes with their notes; the production editor retains a failed-save draft
  and allows retry without duplicate data.
- Contextual EPUB results, no-results state, real result navigation, explicit broad
  query/context errors, and iterator/publication lifetime during cancellation/close.
- A real Readium result-payload test with 100 matches and a roughly 64 KiB chapter
  title, verifying the cumulative 8 MiB UTF-8 locator/display-text guard fails
  explicitly even when the match-count limit has not been reached.
- Rapid chapter requests within one UI turn returning to the original chapter's
  visible opening passage. The test observes a stable result beyond Readium's
  pagination debounce, so a delayed callback cannot move it to the chapter end.

Strict production UI visibility checks exposed a Readium 3.4 backward-resource
jump that selected the saved chapter but settled on its final page. The verified
correction waits for a fresh public `PaginationListener` revision from the current
navigator generation, then reapplies the full locator through the shared
cancellable navigation path. This also handles rapid A → B → A requests whose
identical final locator can be conflated by a debounced `StateFlow`.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`, SHA-256
`b802488a61eafadea4fc8a4a5b21a423de06b1890385b2b064dcab10bbe79419`.

Fixed-layout EPUB text selection is unsupported by the chosen navigator. EPUB
search operates on available text and performs no OCR; PDF annotation/search are
excluded. File-backed database reopening, fresh native navigator creation and
`ActivityScenario.recreate()` cover persistence and configuration restoration, not
OS process death. API 24/37, 16 KB devices, full accessibility, immersive system
bars, complete appearance QA, remote CI and release signing remain separate gates.

### Shelves, reading activity and CI — 2026-11-06

Debug APK assembly, 80 JVM tests and all 82 instrumentation tests passed with
zero failures or skips. Instrumentation ran separately on API 24 and API 35
Google APIs x86_64 images using emulator 36.1.9 with host graphics. Android lint
reported zero errors and 17 warnings. The device cases cover real EPUB and PDF
imports, native reader and annotation restoration, search locator persistence,
shelf editing and membership, and reading activity through screen-off and wake.

SDK Manager 22.0 installed the workflow's three packages in an isolated SDK,
and `actionlint` passed. GitHub Actions verification is pending for the local
fixes. Local logs are retained in the ignored `.leaf-tools/verification/`
directory as `ci-api24-full-lifecycle-fix.log` and `ci-api35-full-lifecycle-fix.log`.

### Offline process restoration — 2026-11-09

On the API 35 Google APIs emulator, Leaf imported the CC0 standard EPUB through
Android's document picker. With airplane mode enabled, it rendered chapter 3
and saved `OEBPS/chapter3.xhtml` in Room. After sending the Activity home and
killing its background process with `adb shell am kill com.leaf.reader`, the
original PID exited. Selecting the existing task in Android Recents launched a
new process and rendered the same chapter in the live WebView.

The file-backed Room tests reopen the exported version 1 schema and exercise
rejection of an unsupported downgrade. The recovery test moves a valid orphan
and a damaged copy into private recovery storage. It verifies that the valid
copy returns to the library with matching bytes and that the damaged copy is
retained after validation fails.

### Phase 5 workflow checks — 2026-11-09

The commands in `.github/workflows/android-ci.yml` built the debug APK and test
APK with JDK 17. All 81 JVM tests and 84 instrumentation tests passed. The full
device suites ran on API 24 and API 35 Google APIs x86_64 emulators. The revised
Settings case passed on API 35 after the full run and as part of the final API 24
run. Debug lint had zero errors and 19 warnings. `actionlint` passed. The hosted
workflow still needs to run for this branch.

### Phase 5 release candidate — 2026-11-09

The minified `:app:assembleRelease` build and `:app:lintRelease` both passed.
Release lint had zero errors and 19 warnings. The unsigned APK is at
`app/build/outputs/apk/release/app-release-unsigned.apk` with SHA-256
`6cf1c55dd67264f89843dbc3422d91aeec2aa15762d270011e1289f53d4ce7af`.

The packaged manifest disables backups, references both exclusion rule files,
and requests only `WAKE_LOCK` and Leaf's signature-scoped receiver permission.
It omits the unused Media3 Bluetooth activity and the debug test harness and
fixture provider. The launcher and the `DUMP`-protected profile installer
receiver are the exported components. A lint exception on the Bluetooth merge
removal accounts for Media3's runtime-only class. The APK audit checks that the
activity is absent from the packaged manifest.

`zipalign -c -P 16 4` passed, and all packaged `arm64-v8a` and `x86_64` native
libraries passed the 16 KB ELF segment checks. On a Google APIs Android 17
x86_64 emulator, `getconf PAGE_SIZE` returned `16384`. All 27 selected reader,
import, recovery and Settings tests passed in airplane mode, including native
ordinary and scanned PDF rendering. A development-signed copy of the minified
APK also imported the CC0 PDF through the system picker and rendered page 2
offline. Settings labels were checked visually at 150% font scale. The sampled
application log contained no crash, fixture text or private publication path.

Public distribution still requires the owner's signing key, a hosted workflow
run for this branch, a full accessibility review and reconciliation of the
[native PDFium provenance and notices](dependencies.md#phase-5-resolved-artifact-review).

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

Both Android CI jobs use the SDK setup action to install `platform-tools`,
`platforms;android-37.0` and `build-tools;37.0.0`. The build job runs JVM unit
tests and lint and uploads the debug APK when its checks pass. The device jobs
run the full instrumentation suite on Google APIs x86_64 images for API 24 and
35, with a 35-minute limit on emulator setup and test execution. Reports are
retained even when a check fails. GitHub Actions must pass before CI can be
reported as verified. API 37 and 16 KB page-size validation are separate release
checks.

Before release, repeat the full fixture and device matrix, inspect the merged release manifest and dependency licenses, verify signing with owner-supplied credentials, and record the APK checksum and output path. An unsigned release artifact or debug-signed APK is not a production signed release.
