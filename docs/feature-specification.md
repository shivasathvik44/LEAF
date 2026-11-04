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

Shelves support names, descriptions, counts and many-to-many book membership.
Deleting a shelf removes memberships but keeps books. Reading activity records
only actively observed foreground time on an unlocked screen. Idle, background,
locked and unobserved process-death intervals do not count. Checkpoints prevent
inflation; daily aggregation splits at local midnight. Activity includes totals,
books started/completed, daily minutes, history, calendar and streaks.

## Settings and privacy

Settings persist via DataStore: app theme/accent, default reader appearance,
library sorting and layout. Storage includes managed usage and safe cache cleanup.
About includes version and third-party notices; a repository link requires the
owner's actual GitHub URL. There are no accounts, ads, analytics, broad storage
permissions, runtime network access or automatic cloud backup of private data.

## Explicitly deferred

Accounts/sync, AI, PDF highlighting/search, TTS, dictionary, comic archives,
bookstore, manual/encrypted backup and folder synchronization are excluded.
