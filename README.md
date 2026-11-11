# Leaf

Leaf is a native, offline-first Android ebook reader for a personal EPUB and PDF library. The project targets Android 7.0 (API 24) and newer, with Kotlin, Jetpack Compose, Material 3, Room, DataStore, Hilt, and the Readium Kotlin Toolkit.

## Development status

Leaf v1.0.0-rc1 is available as the first public release candidate. Feature availability and verification must still be checked against [the implementation checklist](docs/implementation-checklist.md) and [testing notes](docs/testing.md). A successful compilation alone does not establish that importing, rendering, annotations, or position restoration work on a device.

Library and import, the reader, EPUB annotations and search, custom shelves,
and foreground reading activity have passed local checks on API 24 and API 35.
The reader supports reflowable EPUB text selection, colored highlights, notes,
and contextual EPUB search. Fixed-layout EPUB text selection and PDF highlighting/search
are unsupported. Settings provides reader defaults, private storage recovery and
offline access to bundled licenses.

## Build

Install JDK 17 and Android SDK 37. Leaf's selected foundation toolchain is Gradle 9.8.2, Android Gradle Plugin 9.3.1, Kotlin/Compose compiler 2.4.20, and Readium 3.4.0, with minimum SDK 24 and target SDK 37. Compatibility and successful build verification remain milestone gates. Set `ANDROID_HOME` or create an untracked `local.properties` containing `sdk.dir=/absolute/path/to/android-sdk`.

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` after a successful build. With an emulator or Android device attached:

```sh
./gradlew :app:connectedDebugAndroidTest
```

First-time build dependency downloads require internet access. Reading local publications must work without an internet connection. Toolchain and dependency versions are recorded in the Gradle version catalog and [dependency notes](docs/dependencies.md).

## Install the release candidate

Download [LEAF v1.0.0-rc1](https://github.com/shivasathvik44/LEAF/releases/tag/v1.0.0-rc1) from GitHub. On an Android device, open `leaf-v1.0.0-rc1.apk`, allow installation from the relevant source if requested, and install LEAF.

Verify the downloaded APK with the attached [`SHA256SUMS.txt`](https://github.com/shivasathvik44/LEAF/releases/download/v1.0.0-rc1/SHA256SUMS.txt) file before installing it. This is a release candidate; feedback and bug reports are welcome.

## Product scope

The v1.0 target includes a searchable library; managed EPUB/PDF importing; Readium rendering and full locator restoration; reader appearance controls; bookmarks; EPUB highlights, notes and search; custom shelves; and foreground-only reading statistics.

Cloud accounts and synchronization, AI features, PDF highlighting, unverified PDF text search, text-to-speech, dictionary lookup, comic archives, stores, manual backup, and folder synchronization are outside v1.0.

## Data and privacy

Leaf's design has no account, analytics, advertising, backend, or automatic cloud synchronization. Source documents are selected with Android's document picker. Leaf manages private imported copies; removal must never delete the original selected source. Library data, annotations, imported books, and reading history are excluded from Android automatic backup in v1.0. See [architecture](docs/architecture.md) for storage and privacy boundaries.

Do not attach personal ebooks, private notes, database files, credentials, or signing keys to issue reports. Reproduce publication problems using redistributable fixtures where possible.

## Documentation

- [Architecture](docs/architecture.md)
- [Testing](docs/testing.md)
- [Dependencies and licenses](docs/dependencies.md)
- [Changelog](CHANGELOG.md)

## Release signing

Run `./gradlew :app:assembleRelease :app:lintRelease` to build and check the
minified release candidate. The unsigned APK is written to
`app/build/outputs/apk/release/app-release-unsigned.apk`. A public release
requires the owner's signing credentials, supplied outside version control.

## License

Original Leaf code is licensed under the [MIT License](LICENSE). Third-party components retain their own licenses and required notices; see [dependency notes](docs/dependencies.md).
