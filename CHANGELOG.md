# Changelog

## 1.6.2 — 9 October 2026

- Consistent black tile text in the light palette and white in the dark palette, with dark washes adjusted for at least 4.5:1 contrast at every hour and in held and lifted states. Day and week use the same solid fill, so overlapping blocks retain their colours and contrast.
- Week titles use the lines that fit complete measured glyphs and ellipsise when needed, including multilingual text. Enlarging a tile no longer hides the whole title at particular sizes. The held resize edge sits on the lower border and leaves the title visible during the gesture and after release.
- Reminder chimes now decode on Android 8. Compatible FLAC headers add four bytes per file; the decoded audio and reminder timing are unchanged.
- Removed the obsolete luminance calculation, colour-switch helpers and four cached fields. Corrected the README reminder caption. No dependencies or stored data are added; task storage is unchanged.
- The signed APK is 140,897 bytes, 252 bytes smaller than 1.6.1. All 77 unit tests pass, along with all 81 Android tests on both Android 8 and Android 17. Pixel checks cover fitting, resizing, cancellation, saving and day/week colours; an isolated minified update preserves 57,456 tasks byte for byte. Verification is recorded in `docs/optimisation.md`.

## 1.6.1 — 8 October 2026

- Faster group moves on crowded days: one shared overlap check replaces block copies and a separate policy for every selected block. Small days need no scratch array; older unsnapped blocks retain the same rules.
- Lifted blocks use faster ID lookups in drawing, checks and bulk saves, with cached drag bounds. All lifted tiles stay available when scrolling through a crowded day, and edits update the bounds immediately. The redundant linear lookup helper is removed.
- Crowded weeks create tiles near the viewport, retaining long blocks and the block being held. Both pages stay current during a week swipe, and renaming retains the existing overlap layout.
- Moving week tiles and carrying compact day tiles reuse their drawing. Contrast colours are cached, and duplicate background blending is removed.
- Pending cross-day moves copy and sort each affected list once, then update both days in one screen refresh.
- Features, appearance, reminder behaviour and task storage are unchanged. The signed APK grows by 1,120 bytes (0.8%). All 77 unit and 75 Android tests pass; sixteen day and week captures match 1.6.0 pixel for pixel in both palettes. Measurements are recorded in `docs/optimisation.md`.

## 1.6.0 — 8 October 2026

- The week. Two fingers closing on the day bring in the week that holds it: seven columns from Monday to Sunday under their dates, headed by the month alone. From 6:00 to midnight fills the screen where it is tall enough for an hour to be 40dp, and the night is a scroll away. Each column is a stack of bands three hours tall in the colour blocks take in those hours, so the bands divide the time as the columns divide the days, and nothing is ruled. Swipe sideways for the next or the last week, which follows the finger as a change of day does. Tap a date to go into its day. Two fingers opening, or Back, return to the day the week was come into from, as it was left, wherever the fingers are and whatever was touched in the week meanwhile; after a swipe to another week, to the same day of that week. Between the two, the one fades and falls back before the other comes forward, so the day and the week are never on screen together.
- In the week, a tap on empty time adds a block to that day at its quarter hour, a tap on a block opens its actions, a hold moves it to any day and time in quarter-hour steps, keeping its minutes, and a hold on its lower edge lengthens or shortens it by quarter hours. A move to another day is shown at once and saved in one checked write. The schema is unchanged.
- A block has the same colour and the same ink in the week as on the day. The heading and the dates stand on the bare paper: the week is drawn from beneath its dates down and never behind them, at rest or while it comes and goes.
- The week's titles and hour labels keep their size at larger text settings, where seven columns hold no more, and its dates grow as far as 130%; the day, a pinch away, has every size. A block of 20 minutes or more shows its title, and one of two side by side, too narrow for a few letters, is its colour alone.
- Several blocks move together. Two held at once are lifted and stay lifted when let go; a tap then lifts another or puts one down, and a drag up or down on any of them carries them all at once, with no hold: they are lifted already. They are put down together in one write, all of them or none, and a tap on empty time or Back puts them down where they were.
- The keyboard's key adds the block and leaves the sheet open for the next, which starts where the last ended, with the day brought up so that it shows; on an empty field the key closes the sheet. The Add button adds and closes as before.
- A block whose lower edge is held keeps its look and gives no long buzz: only its handle darkens. Lightening and the buzz now mean lifted, and nothing else. Held still near the bottom of the screen, an edge no longer sets the day scrolling by itself, and once the finger moves the day goes by a quarter as fast as under a carried block.
- A stroke within 300 ms of the day last scrolling belongs to the scroll and resizes nothing.
- Reminders come ten minutes before a block, not five, and say what their countdown runs to: “Next up at 15:20” before a block and “Until 16:05” while it runs, each over the block's name on a line of its own.
- A second finger no longer changes the day when the first lifts, and nothing in a touch with two fingers is a tap.
- A screen reader gets the same in the week: a block's actions move it a day or a quarter hour either way and lengthen or shorten it, each date opens its day, and the day and the week each offer the other.
- The unsigned release APK is 135,933 bytes, against 111,609. Tested on Android 17.

## 1.5.1 — 6 October 2026

- Crowded-day scrolling no longer clones the tile lookup's key and value arrays or searches that clone for every retained tile. Long blocks and a block being dragged remain in the visible window as before.
- Tile placement formats accessibility descriptions only when the spoken content changes. Initial placement and resize steps each format the description once, and moving a short tile retains its title layout when only its visual offset changes.
- Pending moves and shared text paints use primitive ID lookups. Resizing a block preserves the day's existing order without sorting it again, and title submission reads the input once.
- Removed four duplicate gesture fields; previews and active styling follow the existing gesture state. Features, resources, database schema and stored task size are unchanged. Verification and size measurements are recorded in `docs/optimisation.md`.

## 1.5.0 — 5 October 2026

- Changing day follows the finger. A sideways drag carries the day's blocks with it and the next day's in beside them, behind the hour column. Let go 72dp along, or flick, and the day changes; short of that, or flicking back, it returns. The heading leans after the finger and changes, with the tick and the current time's line, at the point where letting go changes day. A day picked from the date sheet or reached with Back slides in the same way. Where animations are switched off the blocks still follow the finger and nothing moves by itself.
- The unsigned release APK is 111,685 bytes, against 108,797. At rest nothing on screen changes: eleven screens of one journey match 1.4.2 pixel for pixel. Tested on Android 17.

## 1.4.2 — 5 October 2026

- A long block's title, kept in view as the day scrolls, rests below the heading's fade. It rested inside the fade, where the heading's tint washed it out.
- The unsigned release APK is 108,797 bytes, against 109,653. Block and palette colours are kept as plain numbers and text styles as one number each, the palette on display shares the class of a daylight stop, and the reminder chimes are encoded 3% smaller and decode to the same samples. Nothing on screen changes: fifteen screens of one journey, in both palettes, match the build before it pixel for pixel.

## 1.4.1 — 5 October 2026

### Storage

- Schema 9 keeps the blocks in one table, in the order the planner reads them: by day, start and id, with no row ids, no index beside it and no table counting ids. A block takes about 34 bytes where it took 46, so a year of eight blocks a day is about 100 KB instead of 150 KB. An existing database is rebuilt in that order when first opened, keeping every block and id. The last id given out is kept in the file's header, so a deleted block's id is still never used again.
- The database's pages are 1 KB, a quarter of the platform's, and the file lies in the planner's own folder, not in a folder for databases. A database is three pages before it holds a block, so a new one is 3 KB, one block of storage, where it was 20 KB in a folder that took another; a month of eight blocks a day is 11 KB instead of 29 KB. The platform makes every file in its own page size, so the file is written out again once, as it is made or brought over, which also fills its pages and leaves out SQLite's emptied table of ids. An earlier release's file is then moved, and its folder removed. On the test emulator reads and writes take as long as before up to ten years of blocks; after twenty, finding the duration of a title never used before takes 2.2 ms where it took 1.1 ms, off the main thread.
- The planner keeps no settings file. The reminders switch is the reminder receiver's own enabled state, which the system already stored, and the splash colour is told to the system when the app is left, once per process and when it changes. A folder and a file, 16 KB on the test emulator, are no longer made, and no settings are read as a process starts.
- Updating removes what earlier releases and their libraries left beside the database: the two settings files, the profile installer's notes and Room's lock file, with the folders that empties. The reminders switch is carried over to the receiver first. On the test emulator the data of a copy of 1.3.3 holding three blocks went from 221 KB to 37 KB, and that of a copy of 1.4.0 from 78 KB to 37 KB.
- After a first use, one block added, the app's data is 37 KB on the test emulator against 78 KB for 1.4.0.
- The unsigned release APK is 110 KB, against 116 KB. The app's name, the launcher icon's background colour and the ids of a block's accessibility actions are written where they are used, which removes three resource types. The result of a write and the kind of sheet that is open are numbers, where each was a class or more, and the planner's two threads go unnamed. The reminder chimes are encoded 5% smaller and decode to the same samples. The Apache licence text for the Kotlin standard library moves from the APK to `docs/kotlin-licence.txt`: no class of that library ships, and the release build now fails if one would.

### Responsiveness and correctness

- Entering a title from a physical keyboard no longer leaves the whole day shaded until the next touch. Since 1.4.0 the platform drew its focus highlight over the day's scroll view, and over the whole heading band and each button's touch area when they held the focus; the heading and buttons now show focus by their own ripple alone. A sheet's scrim no longer takes keyboard focus; back closes the sheet.
- At launch the grid labels and the heading's date are ready when the main thread reaches them. The warm-up thread lost that race in about half of launches on the test emulator, which cost the main thread 1.5 ms.
- Finding the duration of a title never used before reads the blocks themselves: 0.6 ms after ten years of blocks on the test emulator, where reading through the day index took 2.8 ms.
- Swiping through days reads each missing day once, and reads still queued for days already left are skipped.
- A moved block lays out only its time label again and a renamed one only its text; a rename leaves every column in place. Dragging near an edge schedules frames only while the finger is in an edge zone. The heading is laid out once per change of date or width.
- A reminder's block is found among today's blocks, or tomorrow's for a warning shown before midnight, so nothing is stored in the notification for it.

### Removed

- The migrations from database schemas 1 to 4. No published release used them: 1.0.0 shipped schema 5.

### Changed

- Clearing the app's storage switches reminders off on Android 13 and later, as before. On Android 8 to 12 the system keeps the receiver's state through a clear, so the switch stays as it was.

Plans opened by this version cannot be opened by 1.4.0 or earlier.

## 1.4.0 — 4 October 2026

### Storage

- The interface uses platform Android views, and database and reminder work runs on one worker thread without the coroutines library. Compose, coroutines, AndroidX, the profile installer and the benchmark and profile build configuration are removed; two interfaces are all that remains of the Kotlin standard library. The unsigned release APK is 116 KB, against 954 KB for 1.3.3, and the installed app about 140 KB against 2.8 MB (7.1 MB once compiled). See `docs/optimisation.md` for the measurements.
- Schema 7 drops the title index, which was two fifths of the database, and the obsolete Room identity table, preserving tasks and IDs. A year of eight blocks a day takes about 160 KB instead of 250 KB. A new block still takes the duration of the last block with the same title; finding it now reads back through the day index, which for a title never used before takes about 3 ms on the worker thread after ten years of blocks. A migration test checks all 50,400 historical tasks and verifies that the freed pages are returned.
- A fixed-size primitive lookup replaces the general AndroidX collection dependency, retaining unboxed task IDs and shared column layouts.
- Dex remains uncompressed in the APK so Android 9 and later can run it without keeping a separate extracted copy.
- The database uses a rollback journal emptied at commit, and the reminders switch and recorded splash share one settings file. Day and reminder queries stay indexed, and the three-day cache keeps long histories off the interaction path.
- The bundled licence notice names the Kotlin standard library, the only third-party code left, instead of AndroidX libraries that no longer ship.

### Responsiveness and correctness

- Launch takes about a fifth less main-thread time than 1.3.3 and scrolling about half, measured on the test emulator; processor time for a launch as a whole is unchanged.
- A write and the re-read of its day make one trip to the worker thread, and the answer returns to the main thread ahead of the frame being prepared. Reminder syncing follows on the same thread once the interface has its answer. A write is no longer cancelled if the screen closes while it is in flight.
- An open sheet keeps its colours through the day's five-minute colour steps and is rebuilt only at the 07:00 and 20:00 flips, around what it holds.
- Dense days keep fewer off-screen views, avoid range allocations during scrolling and use a smaller, faster layout lookup. Long titles redraw only when their pinned position changes, and clock ticks reuse each five-minute palette.
- Detached tiles, stopped screens and dismissed snackbars release gesture, animation and timeout work.
- The native title field retains its 120-character limit, selection, keyboard and plain-text paste behaviour. Native tests cover the interface, including large fonts, dense days, accessibility, colour steps under a half-typed title and preserving an unsaved title across activity recreation.
- Active reminders are left untouched when their contents, colour and deadline are unchanged. Edits refresh them; clock changes reset expiry timers; switching reminders off disables their receiver.
- Prepared statements, transactional writes, overlap rules, nearby-day prefetching and failed-write recovery remain in place.

## 1.3.3 — 3 October 2026

### Interface and interaction

- Tapping Reminders while notifications are blocked now closes the date sheet, so the message pointing to system settings can be read. It previously appeared underneath the sheet, leaving the tap with no visible result.

### Responsiveness and efficiency

- Timeline tiles are composed with the window's width, known before layout, instead of in a subcomposition that waited for it to be measured. The measured width replaces it wherever the two differ.
- The date sheet's bell is stroked directly from its path, no longer built as a vector, rendered to a bitmap and tinted.
- The notification permission is requested through the platform itself. Opening the date sheet no longer registers a result launcher, whose random key seeded the secure random generator on the main thread. The sheet's first frame took a median 8.0 ms of main-thread time on the test emulator, down from 11.5 ms.
- Release rules keep only what the app needs. The toolchain's defaults kept every getter and setter of every view, and three libraries pinned parcelling, foldable and start-up classes nothing uses. The APK is about 28 KB smaller, with 100 fewer classes and 485 fewer methods.

## 1.3.2 — 26 September 2026

### Reminders

- Reminders on the day the clocks change now fire at the right time; blocks after the change were an hour off.
- After a time-zone change, the next reminder moves to the new local time instead of firing at the old moment.
- A reminder's accent now matches its tile exactly, including for blocks that share a time with others.

### Interface and interaction

- Dragging a block towards the top or bottom of the screen now scrolls relative to what is visible: scrolling builds to full speed at the bottom of the heading and at the top of the navigation bar, so the finger never has to cover either, and both zones follow font size and navigation mode. Full speed at the bottom was previously out of reach.
- A tap on empty time is no longer lost when it lands just after a block is dropped or changed.
- A block moved just after swiping to its day no longer briefly jumps back when that day's prefetch finishes late.
- Creating a block beside older, unsnapped blocks now checks the rounded end time against the overlap limit.
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
- Removed the Material 3 library. The title field, buttons, ripple and snackbar it provided are now a few hundred lines of the app's own, drawn pixel for pixel as before, and the APK is about 59 KB smaller.
- Replaced the Room database library with the platform's SQLite on the same file, version and schema, so existing planners open unchanged. Opening no longer checks the schema, a write no longer logs itself to a tracking table for observers, and the APK is about 43 KB smaller.
- Reminder and title-history queries now seek directly to the requested day and minute in the existing indices; frequently used database statements are reused.
- Crowded-day layouts reuse their scratch arrays and store block IDs without boxed keys or individual hash-map entries.
- Empty-space taps skip column lookups for blocks outside the tapped time, and gutter taps no longer scan the day's blocks.
- Once disabled reminders have cleared their alarm and notifications, subsequent planner writes skip reminder scheduling entirely.
- Replaced the generated baseline-profile method list with two package rules expanded by the release build, eliminating stale signatures and manual regeneration.

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
