# Changelog

## 1.1.0 — 20 July 2026

### Interface and interaction

- Replaced the liquid-glass design with a flat, time-aware background and simple translucent blocks.
- Removed animated day transitions and added haptic feedback after a successful swipe to the previous or next day.
- Rendered the timeline grid immediately instead of waiting for blocks to load from local storage.
- Improved long-block labels so they remain readable while scrolling, and refined compact-block sizing, duration labels, resize handles and overlap layout.
- Fixed date headings, calendar state and current-time indicators across midnight.
- Centralised system-bar styling so open sheets and time-aware palette changes cannot overwrite each other.

### Responsiveness and efficiency

- Deleted the duplicated backdrop renderer and all app runtime shader and blur work; GPU translation now applies only to the block being dragged.
- Reduced gesture calculations, layout nodes, repeated text measurement and overlap calculations on interaction paths.
- Paused the minute clock while the app is not visible and limited palette updates to meaningful five-minute steps.
- Scoped minute and date updates to their actual readers, reducing wider Compose recomposition.
- Bounded the in-memory cache to the selected and adjacent days, and cancelled stale prefetch work.
- Removed redundant Room KTX and lifecycle ViewModel Compose dependencies, obsolete benchmark build types and unused source helpers.

### Reliability and accessibility

- Replaced separate occupancy checks with one overlap policy for creation, movement, resizing, restoration and accessibility actions.
- Revalidated database writes inside transactions and reverted optimistic interface changes after rejected writes.
- Propagated coroutine cancellation, serialised rapid updates per block and blocked duplicate delete, undo and sheet submissions.
- Added a timeline accessibility action for creating a block at the visible time, plus direct rename, delete, move and resize actions on blocks.
- Isolated connected interface tests from the real Daytile package so they cannot replace the installed app or clear its plans.
- Capped titles without splitting UTF-16 surrogate pairs and improved large-font sheet coverage.

### Privacy, security and build quality

- Removed unused AndroidX initialisers and services from the release manifest.
- Removed release-only coroutine debugger and Kotlin tooling resources.
- Updated the app to target Android 16 (API 36), refreshed locked dependencies and simplified build configuration.
- Expanded unit, migration, interaction, accessibility, overlap, geometry and palette-contrast coverage.

### Installation note

The signing key used for v1.0.x is unavailable. Android therefore cannot install v1.1.0 over a v1.0.x installation; uninstalling the old app also deletes its locally stored plans. v1.1.0 starts the replacement signing lineage.
