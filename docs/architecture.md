# Architecture

## Status and boundaries

This document establishes the implementation contracts for Leaf v1.0. A component described here is a design requirement until its code, tests, and milestone verification are recorded in the implementation checklist. The initial workspace contained no application source or user data.

The application is a single Android app module. Compose and Material 3 provide the UI; screen ViewModels expose immutable `StateFlow` state and accept user events. Use cases coordinate operations that span repositories. Room is the source of truth for library metadata and reading records. DataStore holds preferences. Only the reader integration layer uses Readium publication, navigator, and locator types.

```text
Compose screen -> ViewModel -> Use case / repository contract
                                   |
                   Room / DataStore / private files / Readium
```

Screens must not query DAOs or manipulate files. Navigation carries book IDs, never publication objects or book bytes. The reader is a full-screen destination with the main bottom bar hidden. Main destinations are Library, Shelves, Activity, and Settings.

## Repository contracts

| Repository | Responsibility |
| --- | --- |
| BookRepository | Observe and query books, details, reading status, removal |
| BookImportRepository | Validate, stage, hash, deduplicate, parse, and finalize imports |
| PublicationRepository | Open and close Readium publications; expose integration-layer metadata |
| ReadingRepository | Save and restore full locator JSON and reading progress |
| AnnotationRepository | Bookmarks, EPUB highlights, and personal notes |
| ShelfRepository | Shelf metadata and many-to-many membership |
| StatisticsRepository | Persist active sessions and aggregate reading activity |
| PreferencesRepository | Persist global preferences and supported per-book overrides |

Hilt constructs database, repositories, use cases, and ViewModels. Coroutines perform file/database work off the main thread; Flow propagates changes to the UI. WorkManager is appropriate only for durable, deferrable maintenance that requires it, not for an always-running reader service.

## Publication storage

```text
files/
  books/
  covers/
  cache/
    temporary-imports/
```

An import reads a Storage Access Framework URI into isolated temporary storage, validates its supported format and size, computes SHA-256, rejects duplicates, and opens the staged publication through Readium. Available metadata and a cover are extracted before promoting the private file and committing metadata. Recovery must reconcile files and records after interruption. Import failures clean staged files and avoid partially published database entries.

The file-system and SQLite cannot share one atomic transaction. Finalization and deletion therefore require ordered, recoverable operations. Cleanup must distinguish active imports, completed managed files, and abandoned files. A source URI is provenance, not authorization to delete a user's original file. Deleting a private imported copy requires explicit confirmation.

## Reading and annotations

Readium's stable Fragment-based EPUB and PDF navigators are hosted from Compose. Open publications have explicit ownership and are closed after use. A publication is reopened from its managed path after process restoration.

Persist the complete Readium locator as JSON; progression is a derived value for library display and sorting. A page number is insufficient for reflowable EPUBs. Restore the locator after navigator creation, with a best-effort fallback when content changes or a saved location is invalid.

Locator navigation holds a publication operation lease while selecting a native
resource. For reflowable EPUBs, it waits for a fresh public `PaginationListener`
event for the target resource, then reapplies the complete destination locator
within that resource. This works around Readium 3.4's delayed resource-page
listener overriding backward jumps with the chapter's final page. Each event has
a revision and navigator generation, so rapid A → B → A requests remain observable
even if a debounced locator `StateFlow` would conflate identical final values.
The wait is cancellable and bounded to ten seconds; it verifies that the reader
and navigator are still current before the second request. Bookmark, highlight,
chapter, progression and search-result jumps share this path. The ViewModel
cancels its previous navigation job when a newer jump supersedes it or the reader
closes. Strict viewport checks verify saved-passage backward navigation and rapid
chapter requests in the Phase 3 local gate.

EPUB text selection must yield real Readium locations. Saved highlights must be restored as navigator decorations; storing a database row alone is not a rendered annotation. PDF text highlighting and unverified PDF search are excluded from v1.0.

`ReaderDocument` isolates selection, decoration and search APIs. A native EPUB
selection action yields the complete locator JSON and selected text. Domain
annotations use opaque JSON rather than Readium classes; the ViewModel observes
Room and reapplies colored decorations after the native navigator is attached.
Decoration activation opens the corresponding saved annotation. Fixed-layout
EPUBs retain publisher layout and do not offer unsupported text selection.

The annotation repository validates UTF-8 field sizes before writing. Highlight
creation with a note, and color/note edits, share one Room transaction. A failed
write leaves existing records intact; the UI retains the draft for retry.
The Phase 3 changes use existing schema-1 tables and require no migration.

EPUB search uses Readium's string search service with a bounded ICU matching
algorithm. It retains Readium's extraction/context/locator pipeline while rejecting
too many matches or pathological unbroken snippet context before constructing an
unbounded result set. Iterator traversal runs on a background dispatcher, closes
the iterator in `finally`, and holds an operation lease so reader closure cannot
close the publication underneath the search. Cancellation and document/query
generation checks prevent stale results from replacing the current reader state.
A cumulative 8 MiB UTF-8 limit bounds the full locator JSON and display text kept
in results; exceeding it asks the user to refine the phrase without truncating
locators. The feature specification records all input and result limits. Search
navigation uses the real result locator and a temporary navigator decoration
where supported.

## Session accounting

Only foreground, unlocked, active reader time contributes to a session. Use monotonic elapsed time for duration and wall-clock time for dates. Pause accounting when the activity loses active reader state, the screen locks, or inactivity is detected. Persist bounded checkpoints so process death cannot add unattended elapsed time. Daily aggregation must handle sessions that cross midnight, timezone changes, and interrupted sessions without double counting.

## Data safety

Room uses foreign keys and indexes, UUID IDs, a unique content hash, and transactions for related writes. Shelf membership has a composite key; deleting a shelf removes memberships without deleting books. Reading progress is keyed by book ID. Notes depend on highlights. Destructive migrations are prohibited; exported schemas and migration tests protect existing data.

Automatic Android backup and device extraction rules must exclude the library database, managed books, annotations, and history in v1.0. No broad storage permission, analytics SDK, advertising SDK, or unnecessary internet permission is permitted. Components remain unexported unless Android entrypoint behavior requires otherwise. Errors shown to users must omit private paths and book or note contents; release logs must not contain them.

## Release gates

Every milestone must build, pass relevant automated checks, and record the limits of device verification. A v1.0 claim requires actual offline EPUB/PDF import and rendering, locator restoration, annotations, search, shelves, statistics, migration safety, privacy verification, CI success, and a reproducible release build. Production signing depends on credentials supplied by the owner outside the repository.
