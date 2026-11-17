# Changelog

All notable changes to Immich File Picker are recorded here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Version
codes are derived as `major * 10000 + minor * 100 + patch`; the git tag (for example
`v1.1.0`) is the single source of truth for a release.

## [Unreleased]

Nothing yet.

## [1.1.0] — 2026-11-17

The release that makes Immich show up in **any** app's file picker. In 1.0.0 the app could
only be reached through its own custom picker activity, so `ACTION_OPEN_DOCUMENT` callers —
and every app that resolves a file chooser to the system DocumentsUI — never offered it at
all. This version replaces that activity with a real Storage Access Framework root.

### Added

- **A Storage Access Framework root.** `ImmichDocumentsProvider` now publishes a root, so
  the app is listed in system file choosers for both `ACTION_OPEN_DOCUMENT` and
  `ACTION_GET_CONTENT`. One root, one layout, one sort order, whichever action the caller
  uses.
- **Favorites folder.** Starred assets are now a first-class folder next to Albums, served
  from the same paging path.
- **Albums with paging.** Each album folder loads its newest assets in 50-item pages, in
  album-last-activity order, instead of a flat list.
- **SAF picker bridge.** `SafPickerBridgeActivity` is a transparent, UI-less activity that
  makes the app visible to choosers which list *apps* rather than SAF roots — notably the
  system photo picker that browsers such as Firefox route media uploads through. It opens
  DocumentsUI at the Immich root and forwards the result unchanged, so it adds
  discoverability without adding a second interface.
- **Numeric name prefixes** (`0001_…`, `0002_…`) on listed files. DocumentsUI applies its
  own A–Z sort to any root it renders and ignores the provider's row order, so numbering
  the rows is the one lever that makes the default view newest-first. Cosmetic only: the
  file that gets attached keeps its real name, and no bytes are modified.
- Unit tests for the document-id scheme ([`DocumentIdsTest`](app/src/test/kotlin/dev/immich/picker/util/DocumentIdsTest.kt))
  and the prefix/ordering logic ([`RankedNamesTest`](app/src/test/kotlin/dev/immich/picker/util/RankedNamesTest.kt)).
- [`app/lint.xml`](app/lint.xml) documenting the deliberate lint suppressions, so the
  reasons live in the repository rather than in a commit message.

### Changed

- **The root is capped at 400 assets per folder.** DocumentsUI has no lazy paging for
  providers — it does not re-query on scroll — so an unbounded root loaded an entire large
  library within seconds of being opened. 400 is deep enough to find a recent file while
  keeping an accidental open cheap; older items remain reachable through Albums.
- Trashed assets are excluded from the timeline, albums and favorites alike.
- Caller MIME types are narrowed to what the library can actually serve. If a caller only
  accepts something Immich cannot supply (for example `application/pdf`), the root says so
  instead of showing a list where nothing is selectable.
- The network security configuration now documents why cleartext and user-installed CAs are
  permitted, and suppresses the corresponding lint warnings with a written rationale
  instead of leaving them unexplained. No behavioural change.
- Preference writes use `androidx.core.content.edit`, so all writes in a block are applied
  together and a failure cannot leave a half-written credential set.

### Removed

- **The custom in-app picker.** `PickerActivity`, `PickerScreen`, `PickerViewModel` and
  `ThumbnailLoader` are gone. They could not serve `OPEN_DOCUMENT` at all, and they
  disagreed with DocumentsUI on ordering, because DocumentsUI re-sorts any root it renders.
  Removing them left one implementation and one set of behaviours to maintain.
- Their tests (`AlbumSortTest`, `PickerNavigationTest`) were removed with them.
- Leftover launcher icons from earlier builds, and unused icon dependencies.

### Upgrade notes

- The package id (`com.virtunio.immichpicker`), signing key and stored-credential format are
  unchanged, so this installs as a normal update over 1.0.0 and keeps your sign-in.
- Anyone who relied on the old `GET_CONTENT` picker entry point now reaches the same library
  through DocumentsUI instead; there is no setting to bring the old screen back.

[Unreleased]: https://github.com/cipriann-max/immich-provider/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/cipriann-max/immich-provider/compare/v1.0.0...v1.1.0
