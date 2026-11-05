# Dependencies and licenses

## Selected versions

The version catalog and wrapper properties are the authority for the selected versions. Selection does not establish successful resolution, compilation, or device compatibility. Readium's 3.4.0 source was inspected during foundation work; the application and its resolved transitive graph still require verification.

| Component | Selected version | Purpose |
| --- | --- | --- |
| Gradle | 9.8.2 | Wrapper/build execution |
| Android Gradle Plugin | 9.3.1 | Android compilation and packaging |
| Kotlin / Compose compiler | 2.4.20 | Language and Compose compilation |
| JDK | 17 | Build runtime and app bytecode target |
| Android compile/target SDK | 37 | Android platform APIs |
| Android minimum SDK | 24 | Android 7.0 minimum |
| KSP | 2.3.13 | Room/Hilt code generation |
| Compose UI/Foundation | 1.12.0 | User interface |
| Material 3 | 1.4.0 | Material components |
| Hilt | 2.60.1 | Dependency injection |
| Readium shared/streamer/navigator/PDFium adapter | 3.4.0 | Local publication parsing/rendering |
| Room | 2.8.5 | Local relational storage |
| DataStore | 1.2.1 | Preferences |
| Coil | 3.3.0 | Local cover loading; no network add-on selected |
| Coroutines | 1.11.0 | Background work and observable state |
| Core library desugaring | 2.1.5 | Java API compatibility |
| JUnit | 4.13.2 | JVM tests |
| Espresso core | 3.7.0 | Android tests for the native text-selection menu; test-only |

Phase 4 declares `com.github.marain87:AndroidPdfViewer:3.2.8` as `compileOnly` so
Leaf can name the existing viewer's public drawing callbacks. Readium already
provides that same artifact at runtime; this does not add another runtime PDF
backend or change its version. The callbacks observe the current native bitmap
page before activity accounting starts, without replacing Readium rendering or
overriding the adapter's own render/page-change listeners.

Readium 3.4.0 itself builds with Kotlin 2.4.20 and AGP 9.3.1. Its stable Fragment navigator is selected; the separately published experimental navigator APIs are outside this integration. Readium's source Gradle daemon uses JDK 21, while Leaf initially selects JDK 17. Build and lint results must resolve this distinction. See [AGP 9.3 compatibility](https://developer.android.com/build/releases/agp-9-3-0-release-notes) and [Gradle 9.8.2 compatibility](https://docs.gradle.org/9.8.2/userguide/compatibility.html).

## Readium source audit — 2026-11-01

The audit examined the official 3.4.0 source archive, including manifests, build scripts, EPUB `WebViewServer`, EPUB parser, navigator assets, and PDFium adapter. It is a source-level audit, not proof of the final APK's behavior.

- The shared, streamer, stable navigator, and PDFium document/navigator manifests contain no permissions or exported components. They do not require Leaf to declare `INTERNET`.
- EPUB `WebViewServer` intercepts publication/asset requests, using the synthetic HTTPS origins `readium_package` and `readium_assets`; packaged content is served through publication resources and `WebViewAssetLoader`. No localhost HTTP server is required for these local resources.
- `EpubParser` accepts an `HttpClient` and defaults to a network-capable implementation for remote publication resources. Leaf must supply a client that rejects network requests and omit runtime network permissions. Remote assets may therefore be unavailable, which is appropriate for local-only reading. External hyperlinks must not trigger automatic network activity.
- `readium-shared` includes HTTP implementations in its core artifact. Presence of HTTP code is not evidence of a network request; omission of `INTERNET`, an explicit rejecting client, and offline device tests are the relevant controls.
- The stable navigator also depends on Media3, browser helpers, and a bundled PhotoView JAR. These ship as part of the upstream navigator even though audiobook/TTS features are outside Leaf's scope. Do not remove transitive classes without proving the EPUB/PDF integration still works.
- Readium uses Timber. Leaf must not install a release logging tree that prints publication contents, selected text, notes, or private paths.
- No analytics or advertising dependency was found in these inspected Readium build scripts. The complete resolved app graph and merged manifest still need review.

Primary source: [Readium 3.4.0 source](https://github.com/readium/kotlin-toolkit/tree/3.4.0), including [stable navigator](https://github.com/readium/kotlin-toolkit/tree/3.4.0/readium/navigator), [EPUB parser](https://github.com/readium/kotlin-toolkit/blob/3.4.0/readium/streamer/src/main/java/org/readium/r2/streamer/parser/epub/EpubParser.kt), and [dependency catalog](https://github.com/readium/kotlin-toolkit/blob/3.4.0/gradle/libs.versions.toml).

## PDF native dependencies and 16 KB pages

Readium selects `com.github.marain87:AndroidPdfViewer:3.2.8` and `com.github.marain87:PdfiumAndroid:1.9.8`. The viewer's upstream documentation records 16 KB work in 3.2.6 and the update to PDFium 1.9.8 in 3.2.8. This is an upstream compatibility claim; Leaf's packaged binaries have not yet been established as compatible. [AndroidPdfViewer source](https://github.com/marain87/AndroidPdfViewer), [PdfiumAndroid source](https://github.com/marain87/PdfiumAndroid).

The selected PDFium AAR was downloaded from JitPack and inspected with `readelf -lW` on 2026-11-01. Its SHA-256 is `8897d2ad45d59b233a44c5493f8c1055f3c843839d86e3e73aa7c8e3fc3371c2`. Both `libpdfium.cr.so` and `libpdfiumandroid.so` have `0x4000` LOAD alignment on `arm64-v8a` and `x86_64`. Their 32-bit `armeabi-v7a`/`x86` variants use `0x1000` alignment. The AAR manifest declares minimum SDK 21 and no permissions/components. No license/NOTICE entries were present in the AAR. These results inspect a dependency, not the final APK; ZIP packaging and 16 KB runtime behavior remain untested.

The adapter README describes its historical PDFium backend as unmaintained and warns about memory limits for large protected PDFs. Leaf does not include LCP or another DRM service. Corrupt and protected files must fail with useful errors; large ordinary/scanned PDFs still require stress tests. PDF backend native code also requires a vulnerability and update review before release.

For the final APK, inspect every `arm64-v8a` and `x86_64` native library's ELF LOAD segment alignment, verify APK ZIP alignment, and run on a 16 KB system. A modern AGP cannot repair a prebuilt `.so` with incompatible ELF alignment. [Android's 16 KB guidance](https://developer.android.com/guide/practices/page-sizes).

```sh
zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release-unsigned.apk
```

Record the APK checksum, resolved AAR checksums, native ABI inventory, ELF results, and actual device page size with the release results. Ordinary 4 KB emulator tests are insufficient.

## License inventory and retained notices

Original Leaf code uses MIT. Dependencies retain their own terms. The following component licenses were identified from upstream sources; this is not yet a complete resolved-artifact license audit.

| Component | License / retained material |
| --- | --- |
| Kotlin, coroutines, AndroidX, Hilt/Dagger, Coil, Gradle | Apache-2.0; retain relevant copyright and NOTICE material |
| Readium Kotlin Toolkit | BSD-3-Clause; full copyright, conditions and disclaimer bundled |
| Readium CSS | BSD-3-Clause; full license bundled separately |
| PhotoView 2.3.0 JAR embedded by Readium | Apache-2.0; full upstream license bundled |
| AccessibleDfA font | SIL OFL-1.1; copyright, reserved font name and complete license bundled |
| iA Writer Duospace asset | Complete upstream bundled license retained, including its font notices |
| AndroidPdfViewer 3.2.8 | Apache-2.0; complete upstream license bundled |
| PdfiumAndroid 1.9.8 | Apache-2.0 wrapper notices plus BSD-style PDFium notice bundled |
| PDFium native third-party code | Exact prebuilt inventory and all additional notices still require artifact/source reconciliation |
| Jsoup | MIT; license/copyright must be retained when resolved |
| Timber | Apache-2.0; license/notice must be retained when resolved |
| JUnit 4 | EPL-1.0; test/build tooling, not an app runtime dependency |

Readium and font notice files copied from the 3.4.0 source are in `app/src/main/assets/licenses/`; selected PDF adapter/PhotoView/CSS licenses were retrieved from their public upstream repositories. [Bundled notice index](../app/src/main/assets/licenses/index.txt) identifies each source. The MIT license for Leaf does not relicense these assets or native components.

The final release must make the complete notices readable from Settings/About and retain them in the distributed APK. Apache-2.0 components require a license copy and applicable upstream NOTICE content; BSD components require their copyright, conditions and disclaimer; OFL fonts retain their notices and reserved-name conditions. Copying a top-level dependency license alone does not establish that every embedded native third-party notice has been covered.

## Remaining release audit

Capture the resolved app graphs and inspect each direct and transitive artifact:

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Reconcile versions and bundled notices against those graphs, including embedded JARs, fonts, JavaScript/CSS, and native libraries that may not appear as independent Maven coordinates. Review merged debug/release manifests for permissions/components, inspect release logs, and test offline operation. No analytics, advertisements, tracking SDKs, accounts, or unnecessary network modules belong in Leaf. A completed public release license/privacy audit has not yet been recorded.
