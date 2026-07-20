# Changelog

## 1.1.0 — 20 July 2026

### Interface and interaction

- Replaced the liquid-glass shader and duplicated backdrop rendering with a flat, time-aware background and simple translucent blocks. The appearance remains clear while requiring substantially less rendering work.
- Simplified idle blocks to colour and transparency alone; the subtle border appears only while a block is being moved or resized.
- Added restrained haptic feedback after a successful swipe to the previous or next day.
- Kept the timeline visible from the first frame while its blocks load from local storage.
- Improved long-block labels so they remain readable while scrolling, and refined compact-block sizing, duration labels, resize handles and overlap layout.
- Kept current-time labels, date headings, calendar state and system bars consistent across midnight and time-of-day palette changes.
- Prevented open sheets and time-aware system bars from competing over system-bar colours.

### Responsiveness and efficiency

- Limited active drag movement to one temporary GPU translation layer and removed all runtime shader, blur and backdrop work.
- Reduced gesture calculations, layout nodes, repeated text measurement and overlap calculations on interaction paths.
- Paused the minute clock while the app is not visible and limited palette updates to meaningful five-minute steps.
- Scoped minute and date updates so unchanged parts of the interface can be skipped by Compose.
- Prefetched only the adjacent days, cancelled stale work and retained a bounded three-day in-memory cache.
- Removed redundant Room KTX, lifecycle ViewModel Compose, benchmark build-type and obsolete source dependencies.

### Reliability and accessibility

- Unified overlap validation for creation, movement, resizing, restoration and accessibility actions.
- Revalidated writes against the database inside transactions while preserving responsive optimistic movement and reverting failed writes safely.
- Preserved coroutine cancellation, serialised rapid updates per block and prevented duplicate delete, undo and sheet submissions.
- Added a timeline accessibility action for creating a block at the visible time, plus direct rename, delete, move and resize actions on blocks.
- Isolated connected interface tests from the real Daytile package so they cannot replace the installed app or clear its plans.
- Capped titles without splitting UTF-16 surrogate pairs and improved large-font sheet coverage.

### Privacy, security and build quality

- Retained a fully offline runtime with zero Android permissions, no account, no analytics and no logging.
- Kept backup and device-transfer access disabled and removed unused AndroidX initialisers and services from the release manifest.
- Continued to reject permissions, cleartext traffic, networking or analytics dependencies, and logging calls during release builds.
- Updated the app to target Android 16 (API 36), refreshed locked dependencies and removed obsolete build configuration.
- Expanded unit, migration, interaction, accessibility, overlap, geometry and palette-contrast coverage.
- Minified code and resources for release builds and retained baseline-profile and macrobenchmark support outside the shipped app.

### Installation note

The signing key used for v1.0.x is unavailable. Android therefore cannot install v1.1.0 over a v1.0.x installation; the old app must be uninstalled first. As Daytile deliberately disables backup and device transfer, uninstalling also removes its local plans. Releases from v1.1.0 onwards use the replacement signing lineage.
