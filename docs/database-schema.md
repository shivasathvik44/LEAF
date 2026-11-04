# Database schema

Leaf uses Room 2.8.5 with an initial schema version of **1**, database file `leaf.db`, and `exportSchema = true`. This is a new repository; there is no earlier Leaf schema or historical migration to invent. The generated `app/schemas/com.leaf.reader.data.local.database.LeafDatabase/1.json` must be committed after successful KSP generation.

## Tables

| Table | Columns | Keys and indexes |
| --- | --- | --- |
| `books` | `id`, `title`, `author`, nullable `description`, `format`, `storagePath`, nullable `sourceUri`, nullable `coverPath`, `fileSize`, `contentHash`, `importedAt`, nullable `lastOpenedAt`, `status` | UUID primary key; unique SHA-256 and storage path; title, author, import/open date, status indexes |
| `reading_progress` | `bookId`, full `locatorJson`, `progression`, `updatedAt` | Book ID primary key and foreign key |
| `bookmarks` | `id`, `bookId`, full `locatorJson`, nullable `label`, `createdAt` | UUID primary key; book index; unique book/locator pair |
| `highlights` | `id`, `bookId`, selection `locatorJson`, `selectedText`, ARGB `color`, `createdAt` | UUID primary key; book index |
| `notes` | `id`, `highlightId`, `content`, `updatedAt` | UUID primary key; unique indexed highlight foreign key; one optional note per highlight |
| `shelves` | `id`, `name`, `description`, `createdAt` | UUID primary key; unique name with SQLite `NOCASE` collation |
| `book_shelf` | `bookId`, `shelfId` | Composite primary key; shelf index; foreign keys to both parents |
| `reading_sessions` | `id`, `bookId`, `startedAt`, `endedAt`, `activeDurationMs` | UUID primary key; book, start and end indexes |

All timestamps are epoch milliseconds. Enums are stored by their stable names using explicit Room converters. Domain types contain no Readium classes; the reader integration owns locator JSON serialization and parsing. Progress is a finite value between zero and one; the complete JSON remains the authoritative saved position.

## Relationships and removal

Book foreign keys cascade to progress, bookmarks, highlights, shelf memberships, and reading sessions. Deleting a highlight cascades to its note. Deleting a shelf cascades only to memberships and never deletes books. Shelf names are trimmed; SQLite `NOCASE` provides ASCII case-insensitive uniqueness, not full Unicode case folding.

`BookRepository.removeRecord` is database-only. It does not touch managed files or original source documents. A separate removal use case owns user confirmation and private-file cleanup. Per-book DataStore overrides are separate from Room and can be reset by that use case.

## Transactions

Full progress and status changes share a Room transaction. Bookmark insertion deduplicates exact locator JSON inside a transaction. Highlight/note creation and editing are transactional. Checkpoint writes accept a caller-supplied UUID for idempotent retry; reusing that UUID for a different interval is rejected. Imports must coordinate managed-file finalization with an aborting unique book insert and explicit recovery; SQLite and the file system are not one atomic transaction.

Phase 3 uses the existing annotation tables without changing schema version 1.
Repository validation rejects oversized UTF-8 values before writes: 64 KiB each
for selected text and notes, 256 KiB for locator JSON, and 4 KiB for bookmark labels.
Values within the limits are kept intact. Updating a note preserves its UUID;
clearing blank note content removes only the note, and deleting its highlight
cascades to that note. Color and note updates either commit together or roll back
together. File-backed repository tests close and reopen the database and inject
actual SQLite insert/update failures to exercise preservation and rollback.

Reading sessions are closed, observed checkpoints rather than persisted running timers. Active duration must be positive and no larger than its wall interval. The reader must submit only unlocked foreground activity and normalize active intervals for statistics; background/process-death time is never inferred from an open database timestamp.

## Migration policy

`LeafDatabase.ALL_MIGRATIONS` is explicitly empty for initial version 1. The builder uses it without any destructive fallback. Every future version change must add an explicit data-preserving `Migration`, retain the older exported schemas, and test representative existing books, locators, annotations, shelves, and history. Never use `fallbackToDestructiveMigration`, destructive downgrade, or drop/recreate to make an upgrade pass.

Staged instrumentation coverage exercises foreign keys, cascades, unique constraints, transactional rollback, locator preservation, note updates, shelf membership, idempotent checkpoints, reopening a database created from the exported initial schema, and rejecting an unsupported downgrade while preserving its records. No upgrade migration can be claimed tested until one exists. These tests require the generated schema asset and an Android runtime; source creation alone is not a passing test result.
