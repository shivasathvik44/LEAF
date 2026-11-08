# Leaf v1.0 feature specification

This is the acceptance contract, not a statement that all features are implemented.
See the implementation checklist and verification report for observed results.

## Library and import

Library is the default destination. It has search, import, continue reading,
recently opened books, a two-column cover grid and list preference. Users can sort
by title, author, import date, last opened or progress, and filter by unread,
reading or completed. Book details expose metadata, status, shelf membership,
opening and removal. Empty, loading and recoverable error states are required.

Import uses `OpenMultipleDocuments` / `ACTION_OPEN_DOCUMENT`, accepts EPUB and PDF,
and streams a bounded private copy. The pipeline validates extension and content,
hashes SHA-256, rejects duplicates, parses with Readium, extracts metadata/cover,
promotes managed files, and inserts the database record. A failed or interrupted
import must leave neither a visible partial book nor an abandoned temporary file.
Encrypted/DRM-protected files are unsupported and need clear recovery messages.
Removing a book never deletes the original document. Removing the private copy
requires a dialog explaining the data and annotations that will be removed.

## Reader

The full-screen reader hides main navigation. Stable Readium Fragment navigators
render local EPUB and PDF publications. Controls include back, page navigation,
chapter navigation, bookmark, contents, progress and appearance. Controls can be
hidden; orientation and process restoration reopen the managed publication.

The entire Readium Locator JSON is persisted, not just a page index. EPUB text
reflow must retain meaningful reading position. Global preferences and per-book
overrides include light/dark/sepia, typography, margins and scrolling. Fixed-layout
PDFs expose only settings their engine supports; EPUB typography is not applied.

## Annotations and search

Bookmarks include full locator JSON, optional labels and creation timestamps.
Reflowable-EPUB selection creates highlights using actual Readium selection
locations, with gold, green, blue and pink colors and rendered decorations restored
on reopen. Notes belong to highlights, support editing/deletion, and remain private.
Lists navigate back to the original locator. A failed database save keeps the
visible draft available for retry and rolls back related writes together.
The selected Readium navigator does not support text selection for fixed-layout
EPUBs; Leaf explains this limitation instead of offering unsupported creation.

EPUB search uses the publication search service with loading, contextual results,
cancellation, empty/error states and result navigation. It can search text in
fixed-layout EPUBs where the publication exposes searchable content. Images do not
receive OCR. PDF highlights and PDF search are outside the v1.0 support contract.

Annotation storage accepts up to 64 KiB each of selected text and note content,
256 KiB of full locator JSON, and 4 KiB of bookmark label, measured in UTF-8 bytes.
Oversized input is rejected with an explanation and is never silently truncated.
Search accepts 200 characters in the UI (512 at the integration boundary), with
at most 1,000 matches in one resource, 5,000 overall, and a 4,096-character limit
on unbroken context extension. Retained results have a cumulative 8 MiB UTF-8
payload limit, counting full locator JSON, display titles, and before/match/after
text. Reaching any search limit produces an explicit recoverable error asking for
a more specific phrase; partial search results are identified as incomplete.

## Shelves and activity

Shelves support creation, name/description editing, cover collages, counts and
many-to-many book membership. Books can be added or removed from a shelf screen
or selected into several shelves from book details. Creating a shelf from that
picker includes its first book in the same transaction. Deleting a shelf removes
memberships and preserves its books and their files, progress, annotations and
reading activity. Names are unique under SQLite's ASCII `NOCASE` rule. Names allow
100 Unicode code points and descriptions 2,000; oversized edits are explicitly
rejected while retaining the prior valid draft. Loading, empty, retryable errors
and configuration-restored editor text are required.

Reading activity records only observed time after native content renders in a
resumed, focused, foreground reader on an interactive unlocked device. Covered
modals, background, lock and idle periods do not earn time. Real input renews a
two-minute idle deadline. Five-second closed checkpoints use monotonic duration
and real calendar endpoints; gaps over fifteen seconds and uncertain clock-jump
windows are discarded conservatively. No running timer is restored from storage.

Checkpoint retries preserve their UUID and interval. Pending memory is bounded
across readers, further accrual pauses under storage pressure, and failed closed
writes show a warning and retry when a reader is next opened. Abrupt process death
may lose uncommitted or failed in-memory time; it cannot add unattended time.

Activity includes total time, started/completed books, daily minutes, history,
month calendar and current/longest streaks. Started books require observed time or
reading/completed status; a failed opening timestamp alone is insufficient.
Completed counts reflect current status. Local-midnight/DST splitting, future-time
clipping and overlap/replay deduplication keep totals and book/day history coherent.
Calendar selection survives configuration restoration. Deleting a book removes
its activity through the existing confirmed book-removal flow. Raw checkpoint
compaction and large-history performance remain release checks.

## Settings and privacy

Settings persist app theme and accent, reader defaults, library sorting and
layout in DataStore. Per-book appearance takes precedence over reader defaults.
Storage displays private book, cover and recovery usage. Cache cleanup removes
only Leaf-owned temporary import files under the shared import lock.

Settings lists quarantined EPUB and PDF copies. Restoring one uses the standard
validation, hashing and duplicate detection path. A successful restore consumes
the private recovery copy once the managed book is available. Validation and
storage failures preserve the recovery copy for retry. About shows the app
version and bundled notices and can open the repository in the user's browser.
Leaf keeps its library, annotations and reading history on the device.

## Explicitly deferred

Accounts/sync, AI, PDF highlighting/search, TTS, dictionary, comic archives,
bookstore, manual/encrypted backup and folder synchronization are excluded.
