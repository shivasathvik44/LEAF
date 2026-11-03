# Changelog

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
