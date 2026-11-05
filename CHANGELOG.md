# Changelog

## Unreleased — shelves and reading activity (verification pending)

- Added custom shelves with descriptions, cover collages, book counts, editing,
  membership management and a book-details shelf picker. Shelf deletion keeps books.
- Added observed reading checkpoints and an Activity screen with daily time,
  calendar/history, started/completed books and streaks. Background, locked, idle,
  unfocused and modal-covered reader intervals do not earn time.
- Start activity only after the visible native EPUB/PDF page renders, rather than
  treating a parsed publication or default locator as proof of usable content.
- Keep failed checkpoint UUIDs for retry in a bounded in-memory backlog; pause
  accrual under storage pressure and surface save failures after reader closure.
- Added accounting, projection, shelf transaction and real-screen/lifecycle tests.
  The complete Phase 4 build, lint and device gate remains pending. Room stays at
  schema 1; no destructive migration or new runtime PDF dependency is introduced.

## Unreleased — annotations and search, verified locally 2026-11-04

- Added native reflowable-EPUB selection, four highlight colors, visible Readium
  decorations, note editing/deletion, and navigation from saved annotations.
- Added contextual EPUB search with cancellation, loading/empty/error states,
  result navigation and explicit limits for broad queries, oversized context and
  retained result text exceeding 8 MiB.
- Bound annotation fields by UTF-8 size without truncation; keep color/note changes
  transactional and preserve drafts after a failed save. Room remains at schema 1.
- Corrected backward EPUB jumps so a saved passage can return to its full locator
  after Readium selects the chapter, rather than settling on the chapter's final
  page. Navigation now retains the publication and cancels superseded requests.
- Added repository rollback/reopen tests, native annotation/search lifecycle tests,
  and production reader UI tests with actual viewport/color, saved-passage,
  decoration activation and rapid chapter-request assertions.
- Full local gate passed: 57 JVM tests and 61 API 35 Android tests, zero
  failures/errors/skips, debug assembly and zero lint errors (16 warnings).
  Database/fresh-navigator reopen and configuration recreation are covered;
  OS process-death, wider-device and release verification remain pending.

## Unreleased — Phase 2 review

- Cancel stale reader seeks and keep publication resources alive until suspended
  locator work completes or is cancelled.
- Preserve fixed-layout EPUB typography, hide unsupported reflow controls, and
  display PDF page counts without changing the saved full locator.
- Expanded native-format/lifecycle verification: 54 JVM tests and 41 API 35
  Android tests passed, with debug assembly and zero lint errors.
- Corrected verification notes to distinguish configuration recreation from
  untested process death and to avoid overstating reader-chrome/theme coverage.

## Unreleased — library/import milestone

- Added managed EPUB/PDF importing with real Readium metadata, bounded covers,
  SHA-256 duplicate detection, repair, transactional failure recovery and safe
  removal that preserves the original document.
- Added a searchable cover library, list/grid layouts, status filters, sorting,
  book details, DataStore appearance preferences and full import feedback.
- Verified 54 JVM tests, 27 API 35 Android tests, debug build and lint.

## Unreleased

### Added

- Initial project documentation, issue templates, and Android CI configuration.

No v1.0 release has been declared. Verified implementation milestones will be recorded here as they land.
