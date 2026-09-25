# Changelog

## Unreleased

### Reminders

- Reminders on the day the clocks change now fire at the right time; blocks after the change were an hour off.
- After a time-zone change, the next reminder moves to the new local time instead of firing at the old moment.
- A reminder's accent now matches its tile exactly, including for blocks that share a time with others.

### Interface and interaction

- Dragging a block towards the top or bottom of the screen now scrolls relative to what is visible: scrolling builds to full speed at the bottom of the heading and at the top of the navigation bar, so the finger never has to cover either, and both zones follow font size and navigation mode. Full speed at the bottom was previously out of reach.
- A tap on empty time is no longer lost when it lands just after a block is dropped or changed.
- A block moved just after swiping to its day no longer briefly jumps back when that day's prefetch finishes late.
- The date sheet reads calendar days aloud as dates and announces today and the selected day.
- Launching no longer flashes through the system's black or white splash: on Android 13 and later the splash takes the planner's own light or dark paper, with matching status and navigation bars, as it was when the app was last left. Earlier versions follow the system's dark theme setting.

### Responsiveness and efficiency

- Launch reads the clock without building date-time zone rules on the main thread.
- The launch warm-up now builds the day's colour palettes while the screen is being created, instead of the main thread building them first.
- Processes started for a reminder or after a restart no longer prepare the interface.
- Moving or resizing a block no longer recomposes every tile, and the five-minute colour steps no longer recompose the whole screen.
- Removed the AndroidX Startup provider, which queried the package manager on every process start, reminder wake-ups included; the baseline profile is still installed shortly after launch.
- Composition trace markers are no longer compiled in.
- Posting the first reminder in a process checks the notification channels with one system call instead of three.
- Prefetching the neighbouring days no longer recomposes the screen after launch and after every swipe.
- Each tile is laid out as one node instead of two, and tiles that show their duration drop a further nested box.
- Leaving the app again with an unchanged palette no longer starts a thread and reads the splash setting from disk.
- Removed an unused native graphics library, which with its page alignment took about 75 KB of the APK across four processor architectures.
- Regenerated the baseline profile for the changed code.

## 1.3.1 — 25 September 2026

### Responsiveness and efficiency

- Cold launches open at the current time in the first frame, without an initial jump from midnight or movement during the splash screen's exit.
- Prepared launch data and grid labels away from the main thread, with normal text measurement available whenever the display settings differ.
- Reduced repeated database processing, text measurement, layout and drawing work while editing and scrolling.
- Kept the current-time badge within the time gutter, with its line and dot visible across the timeline.
- Extended the lightweight header glaze behind the status icons and retained its smooth fade into the timeline.
- Reduced packaged code and resources, and added a bundled baseline profile for the app's own code.

## 1.3.0 — 8 September 2026

### Reminders

- Reminders now arrive five minutes before a block starts, rather than as it starts.
- A reminder counts down to the end of its block and clears itself when the block is over.
- Deleting, moving or resizing a block now updates or withdraws its reminder immediately instead of leaving it showing the old time.
- Reminders carry the colour of the block they belong to.

### Responsiveness and efficiency

- Moved all reminder scheduling off the main thread; editing a block no longer calls system services on the interaction path.
- Editing a block no longer queries the notification service or re-arms an unchanged alarm.
- The countdown and the automatic clearing are performed by the system, so the app never wakes to update them and still keeps only one pending alarm.

## 1.2.0 — 8 September 2026

### Reminders

- Added optional reminders. The bell at the bottom left of the date sheet switches them on, and every block then notifies you the minute it starts.
- Reminders are off until you turn them on, and notification access is requested only on first use.
- Delivery is exact and survives a restart, a clock change, a timezone change and an app update.

### Interface and interaction

- Fixed the date sheet's calendar to a constant height so it no longer jumps between months.
- Removed the date sheet's Cancel button; tapping outside the sheet already closes it.

### Privacy

- Daytile now declares four permissions, all of them for reminders: `POST_NOTIFICATIONS`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` (Android 12 only) and `RECEIVE_BOOT_COMPLETED`. None grants access to any data.
- `INTERNET` remains absent, so planner data still cannot leave the device. `verifyPrivacy` now enforces this as an exact allowlist and fails the release build on any other permission.

## 1.1.1 — 20 July 2026

### Interface and interaction

- Added a visible outline to placed blocks and increased its thickness while moving or resizing.
- Restored the timeline header's fade into the planner background.
- Made create and rename text bold and removed the redundant close button from those sheets.
- Positioned scrolling labels for long blocks from the measured header edge so they remain visible below it across display and font sizes.
- Synchronised sheet scrims with status- and navigation-bar dimming so the full window changes in one frame.

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
