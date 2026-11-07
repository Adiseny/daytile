# Slightly quicker resizing after scrolling — 7 November 2026

The existing scroll guard is reduced from 300 to 275ms. A touch during that interval
still continues scrolling; resizing becomes available 25ms sooner. No gesture detection,
state or storage is added. Long-press timing and resize snapping are unchanged.

The existing continued-scroll and drag/resize/accessibility gesture tests pass on
Android 17. All 77 unit tests, release lint, privacy verification, R8 and release build
pass. The unsigned APK remains 153,392 bytes and dex remains 124,748 bytes.

The follow-up verification adds native touch routing at 250ms and 285ms after the last
scroll. The first stroke continues scrolling; the second resizes without scrolling;
cancellation restores the original duration and saves nothing. Backdating the existing
scroll timestamp on the UI thread avoids depending on a sleeping test thread waking
inside a 25ms interval. The test fails at the intended resize assertion with the former
300ms guard and passes with 275ms. The interval uses the same monotonic
[uptime clock](https://developer.android.com/reference/android/os/SystemClock) on both
sides. The 275ms choice is a conservative feel adjustment, not a proven universal optimum.

Fresh verification passes all 77 unit tests and all **95 Android tests on each of Android
8/API 26 and Android 17/API 37**, covering gestures, month boundaries, fitting text,
font scales, rapid writes, reminders, migrations and dense plans. Release checks were
rerun from their tasks, including lint (zero errors, 28 warnings), privacy and shrinking.
The haptic API warnings are guarded with fallbacks for older Android versions; custom
views are constructed directly, and the grid-label allocation uses a configuration cache.

An isolated copy of the minified release passes crowded day/week navigation and pinch
checks with 57,456 tasks. Updating it preserves every database byte and SQLite integrity;
the installed dex matches the release. Native week resizes from 30 to 120 minutes and
back save the correct values and retain identical held/released title pixels.
Fresh 504-task repository-save medians are 6.93 to 1.87ms in memory and 7.50 to 2.15ms
with disk commits, about 3.7 and 3.5 times faster respectively. These measure group saves,
not overall app speed; they do not establish a startup improvement.

Removed the explicit `-repackageclasses` rule: class repackaging has been the
[AGP default since 9.1](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization),
and this project uses 9.3.3. Rebuilding produces a **byte-identical APK**, SHA-256
`b6e91143db8870fe3c15aab27493cb6c72078f75162e1ba2a989f1da3507da36`.
APK inspection confirms no test fixtures, catalogue images or Kotlin library classes.
The seven [external catalogue files](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)
remain outside the APK; upstream licence assets remain compressed. There are no new
dependencies, permissions, task fields, database indexes or persistent caches.

# Stable title width while resizing — 7 November 2026

The duration readout used to choose its column width from the current string and font
size. Labels such as `55m`, `1h` and `1h 5m` therefore gave the title different widths.
If a readout stopped fitting, the title reclaimed its column, then lost it again when a
shorter readout returned. This caused sideways rewrapping during an otherwise vertical
resize, including after a save or cancellation.

The readout now has a bounded column based only on tile width and font scale. Its size
covers the largest duration style where room permits and stays capped at 34% of the
tile. Narrow columns keep their previous title-only layout. A readout is measured once
and drawn in full, right-aligned with 8dp of outer padding and at least 4dp from the title;
when it cannot fit, its column stays reserved so the title does not jump. Existing font
sizes, vertical positioning and height-dependent title styles are retained.

No gesture state or persistent data is added. A freshly drawn tile, a resize preview,
a cancelled resize and a saved resize all use the same column. Tiles too narrow for a
readout no longer format a duration string during text layout.

The three new Android regressions fail against the preceding build and pass with this
fix. They cover growing and shrinking through minute/hour, digit-count and font-size
boundaries, five tile widths and four font scales; visible readouts must be complete,
aligned and clear of the title. Native resize previews, cancellation and a saved
55-to-65-minute resize must retain the same title boundary. All 77 unit tests and all
94 Android tests pass on Android 8/API 26 and Android 17. Release lint, privacy checks,
R8 and the release build pass.

The unsigned APK changes from 153,376 to 153,392 bytes: **16 bytes added**. Only
`classes.dex` differs. Resources, manifest, licence assets, external catalogue files,
dependencies and task storage are unchanged.

# Catalogue information outside the APK — 6 November 2026

The size and performance goals apply to both downloaded and installed copies, and to
plans with hundreds or thousands of tasks. Catalogue support must not add app code,
dependencies, permissions, task fields, persistent caches or extra work in interactions.
Downloaded APK size, installed storage and performance are measured separately; a smaller
compressed download is not automatically a smaller or faster installed app.

The catalogue-only part of PR #1 (reference `904d13a`) supplies seven files in the root
`fastlane/metadata/android/en-US/` directory. This is an external listing format, not a
build plugin or Android asset directory. The existing day, week and night screenshots
are reused byte for byte; their Git blob IDs match the README pictures. The description
now states the hold-to-move gesture, day/week views, reminder default and local storage
precisely. No speculative release version or changelog is added.

This checkout also predated the GPL and Kotlin notices already committed on upstream
`main` (`a5bc6e1`). Those notices and the matching source licence documents are retained.
They are separate from F-Droid catalogue metadata. The two notice assets are ordinary
ZIP-compressed text, never loaded by the planner, and take no space per task. The app's
code, resources and manifest are byte-identical before and after bringing them over.

| Build | Unsigned APK |
| --- | ---: |
| Audited planner before the upstream notice files | 137,325 bytes |
| Same planner with the upstream notices, before catalogue files | 153,376 bytes |
| Same planner with notices and external catalogue files | 153,376 bytes |

The 16,051-byte increase belongs entirely to the two upstream licence assets and their
ZIP entries. **Catalogue information adds zero APK bytes.** A forced fresh build with
the catalogue present has the exact same APK SHA-256 as the build without it:
`2f84e9f3e3cb76eceb3304a14910b61544f205ef2e8b4c69aeca9971a370ef53`.

Both notice texts match their source files, are compressed, and remain available offline.
Dex remains stored uncompressed. No catalogue description, icon or screenshot is present
in the APK. The 77 unit tests, release lint, privacy verification, shrinking checks and
fresh release build pass. The previously verified runtime and database code is unchanged.
F-Droid clients can cache listing images separately; that is not Daytile task storage.

# Group saves and fitting titles — 6 November 2026

The audit covered all production Kotlin files, SQL, resources, dependencies and release
shrinking. The remaining substantial repeated work was in group saves and week updates.
No dependency, database migration, column, index or persistent cache is added.

## Changes

- Group previews find selected tasks in one pass through the day with primitive binary
  searches, replacing a fresh linear task search for every selected ID. Pending-write
  identity checks still keep later moves and resizes in charge.
- Group saves use each task's existing `(day, start, id)` primary key. Previously every
  selected task's update scanned its day by ID. Cross-day moves share the same query,
  and publish both completed days before notifying the interface once.
- Each week column keeps its own tile lookup. Updating a column no longer scans the
  other six columns' tiles. Day updates allocate a removal list only when a tile leaves.
- Week navigation compares the arriving month title with the title already displayed.
  An unchanged title stays stationary throughout dragging, cancellation and landing;
  changed months and boundary weeks retain their transition. Day swipes and ticks keep
  their behavior.
- Day titles use the height left after reserving the measured time label and resize
  handle, rather than fixed line caps. Five lines display fully when they fit, and
  shorter tiles can show two lines when both the time and handle still have room. The
  duration keeps its separate column and fits the tile height even at large font scales.
  Pinned text stops above the resize handle using its actual height.
- Both tile views share complete-line fitting, including fallback-font padding. Week
  titles no longer rebuild their layout once for every rejected line count. The fitted
  line limit preserves the previous centring of narrow multilingual tiles. Text with
  insufficient height stays hidden rather than showing clipped glyphs.
- Rapid moves between days keep the newest preview even when an earlier save finishes
  on that same destination day. Updated titles and later resizes retain priority. A
  deterministic worker/main-thread interleaving reproduced the brief old-position flash
  before the correction.

## Measurements

On the Android 17 emulator, an alternating comparison of complete repository saves for
504 selected tasks gave medians of **6.98 ms before and 1.76 ms after**, about **4 times
faster**. Each sample averages ten saves, with five warm-up batches per path and seven
measured batches; every stored field is checked after each batch. This uses Android's
in-memory SQLite and includes validation and the transaction, not disk synchronization
or the complete gesture. `GroupShiftPerformanceTest` retains the previous query as the
reference implementation.

A separate run using a database file, including transaction commits, measured medians
of **7.77 ms before and 2.24 ms after**, about **3.5 times faster**, for the same 504
selected tasks. Timings vary between runs and Android versions; neither benchmark is a
claim that the entire app runs this many times faster.

| Measurement | Before | After |
| --- | ---: | ---: |
| Unsigned release APK | 136,801 bytes | 137,325 bytes |
| Uncompressed `classes.dex` | 124,208 bytes | 124,732 bytes |

The requested fitting and navigation behavior plus the optimizations cost **524 bytes**
(0.38% of the APK). Dex remains uncompressed, and the release check still rejects any
remaining Kotlin library class or interface. Per-task storage is unchanged.

A counterbalanced startup comparison used identical 57,456-task files in isolated
minified copies. Both processes were stopped before every launch, the order reversed
each pair, and four warm-up pairs were discarded. Fifteen measured launches per copy
gave medians of **239 ms before and 237 ms after**. The overlapping samples do not
establish a launch improvement; the measured gain is in group saves. Installed dex
hashes and every fixture byte were checked after the comparison.

## Verification

- All 77 host unit tests and all 91 Android tests pass on Android 17 and Android 8/API 26.
  The two requested behavior regressions were first reproduced against the original code.
  New checks cover stationary month titles in both swipe directions, retained transitions
  at month/year boundaries, and five complete title lines with safe pinned bounds.
  The follow-up audit adds fitting shorter titles, exhaustive comparison with the
  previous week-title fitting/positioning algorithm, actual multilingual ink bounds,
  four font scales, disk-backed group saves and rapid cross-day save interleavings.
- Existing multilingual/emoji height sweeps, native gestures, resize/cancellation pixel
  checks, reminders and database migrations pass. The compositor test now selects a bare
  paper pixel near the status bar's centre before checking that same pixel under a tile;
  its former fixed pixel could land on a grid line visible through the translucent bar.
- Twelve ordinary/crowded day/week snapshots match the original pixel for pixel. The
  other four differ only where crowded day titles now reveal more text. Both week title
  size atlases also match. Captures use fixed day/night palettes.
- Isolated minified copies pass native day/week navigation and pinch checks with ordinary
  and crowded plans. Native minified week resizes from 30 to 120 minutes and back save
  the correct times, keep the title visible and preserve held/released text pixels.
  Updating a 57,456-task copy preserves every database byte, with
  SQLite integrity checks passing before and after.
- Unit tests, release lint, privacy verification, R8 and the release build pass. Lint
  reports no errors; signing and publication are separate from this source change.

# Stable tile text — 1.6.2, 9 October 2026

## Changes

Tile ink follows the palette in both views: black in light, white in dark. Light washes
retain their previous values; dark washes use 65% at rest and 55% while lifted. Both
views draw the same opaque composite, so text contrast is independent of a block behind
it. The obsolete per-tile colour switch, luminance calculation and four cached fields
are removed. No dependency, database change or persistent cache is added.

Week titles fit the height of complete measured glyphs, including fallback fonts, rather
than relying on fractional line estimates. The largest fitting line count is retained;
long text ellipsises instead of disappearing. Padding uses the space left over. Small
or narrow tiles that cannot fit a readable line remain their colour and retain their
spoken title. Holding the resize edge marks the lower border and does not change the
text layout or its available space.

The README reminder caption no longer gives the former five-minute lead time.

Android 8's [FLAC sniffer](https://android.googlesource.com/platform/frameworks/av/+/android-8.0.0_r1/media/libstagefright/FLACExtractor.cpp)
requires the STREAMINFO block to be marked as non-final. The chimes previously used
STREAMINFO as their sole metadata block, which the older sniffer rejected. An empty
final padding block fixes this with four bytes per file. The compressed audio frames
are identical, and both chimes decode to their original PCM hashes. No playback code,
extra resource or dependency is added.

## Verification

The rendering regressions failed against 1.6.1: holding a 20-minute block hid its title,
multilingual text could exceed its tile, and native resize previews could differ from
fresh tiles. The following checks pass with the replacement:

- All 77 unit tests, with an independent sRGB contrast reference. Tile ink meets the
  [4.5:1 normal-text target](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)
  for every minute of the palette clock, all 24 start hours, all three colour variants,
  and both ordinary and lifted states: 207,360 combinations. Existing palette, band and
  grid contrast checks also pass.
- All 81 Android tests on both Android 8/API 26 and Android 17/API 37 emulators. The
  added checks sweep every physical pixel of
  height from 10 to 120dp at four widths, using short, long, unbroken, accented, Chinese
  and emoji titles. Once drawn, a title must stay drawn as the tile grows. Its full
  measured height must fit.
- Both reminder chimes decode to the original PCM hashes on both Android versions.
  Screenshot checks read the native compositor and wait for the expected pixels;
  keyboard focus uses a real key event, and empty-time taps stay within the viewport.
- Pixel comparisons check that holding reuses the title, resizing forwards and backwards
  matches fresh layouts, cancellation restores every pixel, and saved resizes survive a
  return through the day view. Rendered ink and fill are checked for each hour, variant
  and held state across 11 palette times, including sunrise and dusk boundaries.
- Sixteen full day/week captures cover ordinary and crowded plans at both ends in both
  palettes. All-hour day/week and title-size atlases are visually reviewed in both
  palettes, including Android 8's title-size atlases. These capture the intended
  rendering changes; they are not described as
  unchanged pixels from 1.6.1.
- Isolated copies of the final minified APK pass native pinch, week-swipe and resize
  checks. A multilingual block resized from 30 to 120 minutes and back saves the correct
  times; held and released title pixels match. An update from 1.6.1 preserves all 57,456
  tasks and every database byte, with SQLite integrity checks passing.
- Unit tests, release lint, privacy verification, R8 and the signed build pass. Lint has
  no errors and 29 existing warnings. No Kotlin library class or interface ships.

## Size and launch checks

| Measurement | 1.6.1 | 1.6.2 |
| --- | ---: | ---: |
| Signed APK | 141,149 bytes | 140,897 bytes |
| Uncompressed `classes.dex` | 124,468 bytes | 124,208 bytes |

The APK is 252 bytes smaller. Dex remains uncompressed and task storage is unchanged.
On a dedicated Android 17 emulator, alternating force-stopped minified copies with
57,456 tasks, then discarding two warm-up runs each, gave 247, 253, 261, 264, 254 ms before
and 266, 262, 280, 251, 257 ms after: medians of 254 and 262 ms. These samples fall within emulator
variation and are not used to claim a launch improvement.

# Crowded plans — 1.6.1, 8 October 2026

This pass audits the production code, queries, resources and release packaging on top of
1.6.0. The substantial remaining work was in group moves and crowded weeks. Features,
colours, overlap limits, reminder behaviour and the database schema stay the same.

## Changes

- Group moves check all selected blocks in one occupancy sweep, without copying the
  day's blocks or constructing a policy for each selected block. One primitive array
  holds total and selected occupancy. Normal days use five-minute slots; older unsnapped
  days retain minute precision. Days with seven or fewer blocks need no scratch array.
  Crowding outside the moved blocks remains irrelevant.
- Lifted IDs are sorted once, with primitive binary searches during drawing, checking
  and bulk saves. The redundant linear lookup helper is removed.
  The selected blocks' earliest start and latest end are computed when the selection or
  its times change, rather than on every pointer event. All lifted tiles survive crowded
  day window changes, including blocks outside the viewport.
- Crowded week columns keep nearby tiles, long blocks crossing the viewport and a held
  block. Ordinary columns keep all their tiles. Scrolling maintains both pages during a
  week swipe. Renaming keeps existing overlap layouts, using the same geometry check as
  the day view.
- Moving a week tile reuses its recorded drawing. Compact day tiles also reuse their
  drawing when carried together; larger tiles update their time labels. Week tiles cache
  their contrast ink, and both views avoid blending the same background twice.
- Pending cross-day moves copy each affected list once and sort once. A move publishes
  both days before notifying the screen, avoiding an intermediate render.

## Measurements

| Measurement | 1.6.0 | 1.6.1 |
| --- | ---: | ---: |
| Signed release APK | 140,029 bytes | 141,149 bytes |
| Uncompressed `classes.dex` | 123,348 bytes | 124,468 bytes |

The APK grows by 1,120 bytes (0.8%). Dex stays uncompressed, and no library class ships.
No database column, index, persistent cache or settings file is added: task storage is
unchanged, however many historical tasks are kept. The existing migrations remain
necessary for users of earlier releases.

On the Android 17 emulator, a valid five-minute move of 504 selected tasks took about
10–11 ms with the previous check and roughly 0.03–0.15 ms with the replacement across
separate runs. JIT warm-up and emulator variation affect these measurements. The reproducible
host and Android benchmarks retain the previous algorithm as a reference. These timings
measure the overlap check, not complete gestures or app launches.

With 57,456 tasks (50,400 historical and 7,056 in the current week), alternating
force-stopped minified copies gave launch medians of 261 ms and 250 ms. After discarding
two warm-up runs each, the samples were 252, 263, 273, 261, 247 ms before and 243, 250,
267, 241, 257 ms after. Launch performance is unchanged within the emulator's variation.

## Verification

- 77 unit tests pass, including 2,000 generated comparisons with the previous group
  check, snapped and unsnapped boundaries, missing IDs and unrelated overcrowding.
- 75 Android tests pass on Android 17, including a week containing 7,063 blocks, retaining
  held and all-day tiles across viewport changes, updating carry bounds after edits,
  gestures, accessibility, reminders and the 50,400-task migration and reclamation test.
- Sixteen captures from instrumented copies of 1.6.0 and 1.6.1 match pixel for pixel:
  ordinary and crowded days and weeks at both ends, in fixed light and dark palettes.
- `:app:test`, `:app:lintRelease`, `:app:verifyPrivacy` and the signed release build pass.
  Lint reports no errors; R8 verifies that no Kotlin library class or interface remains.
- The release signature matches 1.6.0. Minified copies use isolated application IDs and
  a debug signing key for launch, pinch and week-swipe checks. Updating an isolated copy
  preserves all 57,456 tasks and every database byte; SQLite integrity checks pass.

# Fewer allocations in interactions — 1.5.1, 6 October 2026

This pass audits all production Kotlin files, resources, dependencies and packaging on
top of 1.5.0. The remaining useful changes are in tile updates, crowded-day scrolling
and pending saves. No dependency, database column, index, setting or persistent cache is
added, and no feature or resource changes.

## Changes

- Initial tile placement formats its accessibility description once instead of twice.
  A snapped resize step also formats it once instead of twice. Geometry updates only
  invalidate the text layout when its width or height changes; a changed visual offset
  retains the existing title and duration layouts. Descriptions still use the platform
  setter, so accessibility services receive content updates.
- A tile's preview and active styling follow its gesture phase and last snapped value.
  The two duplicate preview fields and two duplicate active flags are removed.
- Visible tiles are marked and unmarked in one pass instead of cloning both buffers of
  the tile lookup and removing each retained ID from the clone. Only removed views go
  into a temporary list. Removals occur after the scan to avoid repeatedly compacting
  Android's sparse array. Long tiles crossing the viewport and the active gesture are
  retained, including while the two days of a swipe are paired.
- Pending times and shared text paints use the platform's primitive-key
  `LongSparseArray`, avoiding boxed keys in those lookups. A pending resize changes no
  start or ID, so its merged day keeps the existing order without another sort. Pending
  moves still sort, including when an earlier save finishes underneath a newer move.
- Title submission copies the input to a string once instead of twice. Tile binding
  checks block equality once, and placement checks its text geometry once.

## Size

| Measurement | 1.5.0 | 1.5.1 |
| --- | ---: | ---: |
| Uncompressed `classes.dex` | 99,100 bytes | 99,024 bytes |
| Unsigned release APK | 111,685 bytes | 111,609 bytes |

The database and its queries are unchanged; a history of hundreds or thousands of
tasks needs exactly the same storage as before. The gain here is avoided work and
temporary allocations rather than a smaller download.

## Verification and limits

- `:app:test` (66 unit tests), `:app:lintRelease` (no errors), `:app:verifyPrivacy` and
  `:app:assembleRelease` pass, including the R8 checks that discard every Kotlin library
  class and interface.
- All 46 instrumented tests pass on the Android 17 emulator with animations on. Two are
  new: every pixel and the spoken time after a cancelled resize, and long and held tiles
  across crowded-day window changes.
- A minified copy of published 1.5.0 with blocks on two days was updated in place. Both
  days match 1.5.0 pixel for pixel, and a move, a resize and a day change then work as
  before.
- No timing was measured. The emulator cannot resolve differences of a few
  milliseconds, so no speed figure is claimed.

# The day follows the finger — 5 October 2026

One change on top of 1.4.2, released as 1.5.0: a sideways drag carries the day's blocks
with it and the next day's in beside them. It is the first release since 1.4.0 to add to
the APK, so this records what it costs.

## Measurements

| Measurement | 1.4.2 | After |
| --- | ---: | ---: |
| Unsigned release APK | 108,797 bytes | 111,685 bytes |
| Uncompressed `classes.dex` | 96,212 bytes | 99,100 bytes |
| Classes / method references in the dex | 85 / 842 | 85 / 861 |
| Frames drawn by a launch | 4 | 4 |
| Frames drawn by a swipe to the next day | 1 | 13 to 16 |
| Main-thread time of a frame of that swipe, median | 1.5 ms | 0.2 ms |

A frame of the swipe moves blocks that are drawn already: one property written per
block, nothing laid out and no text measured. The heading is drawn again only while it
leans, and the day's grid only as the second day joins it and leaves and as the current
time's line goes or comes. Nothing runs between changes of day.

The touch that starts a drag builds the next day's blocks, the work 1.4.2 did on the
frame after the finger lifted, and they are kept if the day changes. On the debug build
that event took about 1 ms with 7 blocks a day, 2 to 5 ms with 56 and 10 to 13 ms with
588, of which about 300 are near the screen.

## What was left out

The first working version added 4,752 bytes. Three things came out:

| Left out | Saved | In its place |
| --- | ---: | --- |
| The heading's two names drawn together through a timed swap | about 900 bytes | The heading changes in one frame, as it always has, at the point where letting go changes day |
| The current time's line travelling with today's blocks | about 400 bytes | It goes with the heading, and the grid is not drawn again each frame |
| The platform's path interpolator for the landing | about 300 bytes | A cubic, which also let the screen's statics stay merged into another class |

Verified on the Android 17 emulator: 66 unit and 44 instrumented tests, `lintRelease`,
`verifyPrivacy`; eleven screens of one journey on minified copies of 1.4.2 and this
build (two blocks added, the next day and a block there, the day after, back twice, a
swipe let go short, the day before, Back to today) match below the status bar; a held
drag looked at short of the point, past it and back; the same swipes with animations
switched off; and a copy of 1.4.2 holding blocks on two days, reminders on, updated in
place.

# A pinned title and a last trim — 5 October 2026

One pass on top of 1.4.1, released as 1.4.2. On screen only a long block's pinned title
moves, to below the heading's fade. The rest is size, and there is little left to take.

## Measurements

| Measurement | 1.4.1 | After |
| --- | ---: | ---: |
| Unsigned release APK | 109,653 bytes | 108,797 bytes |
| Uncompressed `classes.dex` | 96,840 bytes | 96,212 bytes |
| Classes / method references in the dex | 86 / 843 | 85 / 842 |
| The two reminder chimes | 6,884 bytes | 6,658 bytes |

What changed, in bytes of APK: colours kept as plain integers where they were 64-bit
(288); text styles as one packed number each in place of a class and five objects, with
the palette on display sharing the daylight stop's class (340); the chimes encoded again
with a wider search, decoding to the same samples, which the instrumented test checks
by hash on Android (226). The pinned-title fix cost 8.

Verified on the Android 17 emulator: 66 unit and 42 instrumented tests, `lintRelease`,
`verifyPrivacy`, and the same journey run on minified copies of the build before and
after (the day, the morning and the small hours, another day, the title sheet empty and
typed, a new block, its actions, rename, delete with its message, the calendar and its
next month, then the evening, its calendar and its title sheet in the dark palette).
Every screenshot matches below the status bar; the only pixels that differ anywhere are
the system's signal icon.

## Where the bytes are

Of the dex's 96 KB, 42 KB is instructions and the rest is its tables: 16.5 KB of strings
(platform class and method names, SQL, the interface's text), 21 KB of ids and lists,
3.5 KB of line tables, 6 KB of class records. No declaration in the app is unused.

## Looked at and left

| Idea | Would save | Why it stays |
| --- | ---: | --- |
| Chimes at 11,025 Hz | 2,761 bytes | Different samples. Nothing in either chime is above 1.1 kHz and the difference measures below -88 dBFS, but it is a change to a sound tuned by ear and needs listening to first. |
| The platform's `LinearLayout` in place of `Flow` | about 1,700 bytes | It rounds the other way: text in sheets and buttons can move by a pixel. |
| R8's API outlines | about 1,300 bytes | Without them classes that touch newer APIs fail verification on Android 8 to 10 and run slower there. |
| Line tables | 3,466 bytes | R8 writes them at every minimum API level; no option removes them. Raising the minimum to Android 9 saves 780 bytes of other code and drops Android 8. |
| The build tools' stamp in `META-INF` | about 250 bytes | Packaging fails without the file. |
| A window on the search for a title's last duration | time only | After twenty years of blocks the search for a title never used takes 2 ms off the main thread. A window would forget lengths older than it. |

The database is as it was: 34 bytes a block, 3 KB when new. A row is its key, its title
and one byte of duration; the ideas that remain (packing start and duration together,
sharing titles) were measured for 1.4.1 and are recorded below.

# One table, small pages and no settings file — 5 October 2026

Two passes on top of 1.4.0, released as 1.4.1. Nothing changes on screen but the keyboard-focus shading
described in the changelog, which lists every change; this records what was measured.

## Measurements

| Measurement | 1.4.0 | After |
| --- | ---: | ---: |
| Unsigned release APK | 115,874 bytes | 109,653 bytes |
| Uncompressed `classes.dex` | 98,248 bytes | 96,840 bytes |
| Classes / method references in the dex | 100 / 861 | 86 / 843 |
| Installed, fresh (APK, odex, vdex) | 143,285 bytes | 138,988 bytes |
| New database | 20,480 bytes | 3,072 bytes |
| App data after a first use, one block added | 77,824 bytes | 36,864 bytes |
| App data of a copy of 1.3.3 with three blocks, updated | 221,184 bytes | 36,864 bytes |
| App data of a copy of 1.4.0 with three blocks, updated | 77,824 bytes | 36,864 bytes |
| Frames drawn by a launch | 4 | 4 |
| Launch, main-thread CPU | 19.2 ms | 18.8 ms |
| Launch, whole process CPU | 97.5 ms | 95.9 ms |

Sizes are from debug-signed, minified copies under separate application IDs on the
Android 17 emulator (x86_64, 1344x2992, a file system of 4 KiB blocks). App data is what
`pm get-package-storage-stats` reports as data, less what it reports as cache: the
database, its journal, the folders the system makes and the system's own profile of the
app. Each file and each folder is at least one block. Launch figures are medians of
twelve launches made in touch mode after a force-stop, alternating between builds, from
`schedstat` 1.2 s after `am start -W` returns; the two builds are within what this
emulator can tell apart, and no launch of either drew the day a second time.

## Database size

Eight blocks a day, 24 repeating titles and one block in six with a title used once,
added a day at a time in a shuffled order; host SQLite 3.51.2 with auto-vacuum on, as
Android builds it.

| Blocks | 1.4.0 | One table, 4 KiB pages | One table, 1 KiB pages | The 1.4.0 file brought over |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 20,480 | 12,288 | 3,072 | 3,072 |
| 96 | 20,480 | 12,288 | 6,144 | 6,144 |
| 240 (a month) | 28,672 | 20,480 | 11,264 | 10,240 |
| 496 | 45,056 | 28,672 | 19,456 | 17,408 |
| 2,920 (a year) | 151,552 | 110,592 | 101,376 | 91,136 |
| 14,600 | 667,648 | 495,616 | 491,520 | 443,392 |
| 29,200 (ten years) | 1,331,200 | 987,136 | 983,040 | 885,760 |

A database is three pages before it holds a block: the header, auto-vacuum's map and the
table. In the platform's pages that is three blocks of storage and in 1 KiB pages one.
Past that the two grow alike, 34 bytes a block. A file brought over is written out in
key order with full pages, 30 bytes a block, and fills like any other from then on.

## Page size and time

The same blocks in both page sizes, written out in key order. Medians on the emulator, ms:

| Blocks | Pages | Open and read a day | Read a day | Title never used | Title in use | Insert | Move |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2,920 | 4 KiB | 0.155 | 0.009 | 0.067 | 0.012 | 0.52 | 0.012 |
| 2,920 | 1 KiB | 0.165 | 0.009 | 0.066 | 0.012 | 0.49 | 0.013 |
| 29,200 | 4 KiB | 0.154 | 0.009 | 0.562 | 0.012 | 0.39 | 0.013 |
| 29,200 | 1 KiB | 0.157 | 0.009 | 0.568 | 0.012 | 0.41 | 0.013 |
| 58,400 | 4 KiB | 0.155 | 0.009 | 1.122 | 0.012 | 0.39 | 0.012 |
| 58,400 | 1 KiB | 0.171 | 0.009 | 2.222 | 0.012 | 0.38 | 0.012 |

The one difference is the search for a title never used before, which reads every block,
once the file no longer fits SQLite's page cache: after twenty years of eight blocks a
day in 1 KiB pages, and somewhat later in the platform's. It runs on the worker thread
inside a create.

## Titles shared between blocks: measured, not done

Keeping each distinct title once, in a table of its own, with a number in the block:

| 29,200 blocks, 1 KiB pages | Titles in the blocks | Titles shared |
| --- | ---: | ---: |
| Every title one of 30 | 852,992 bytes | 570,368 bytes |
| Six in ten one of 30, the rest used once | 925,696 bytes | 832,512 bytes |
| Every title used once | 1,035,264 bytes | 1,135,616 bytes |

A third smaller when every title repeats, a tenth with a mix, a tenth larger when none
does, for a second table, a join in every read, and a search and a sweep in every
create, rename and delete. Up to a few hundred blocks it saves nothing. Left as it is.

## The system's compiled files

A fresh install is compiled to `verify`: an odex of 17,152 bytes and a vdex of 2,264
beside the APK. After three sessions of use the system had saved no profile of a build
this small, so its idle compilation (`speed-profile`) left those files as they were.
Compiling every method (`speed`), the upper bound, makes the odex 271,936 bytes.

# Worker thread, standard library and schema 7 — 4 October 2026

This pass starts from the native-view working tree described in the next section
(unsigned release APK 261,150 bytes) and changes nothing on screen. Its result is
released as 1.4.0.

## Changes in this pass

- Removed the coroutines library, which had become larger than the app's own code
  (153 of 348 classes). Every database call and reminder sync runs on one worker thread,
  in the order asked, and results return to the main thread as asynchronous messages,
  which is what the coroutine dispatcher did underneath. The view model holds plain
  fields and tells the screen when they change; it no longer publishes immutable state
  copies through a flow.
- A write and the re-read of its day are one task, so the interface gets its answer one
  thread hop sooner than the write, observer wake-up and re-read sequence delivered it.
  The selected day and any neighbour not already cached are read in one task. Reminder
  syncing is queued behind the task that wrote, so no system call stands between a write
  and its result.
- Removed what single lines of app code kept of the Kotlin standard library: reflection
  scaffolding and 21 function interfaces behind three callable references, the abstract
  list family behind `DayOfWeek.entries`, two reflection-probing classes behind `use`
  (which ran during the first query at launch), `Pair`, ranges, the empty list, `lazy`,
  `lateinit` checks and their property names, generated `toString` text, and bounds and
  rounding messages. Two interfaces, `Function0` and `Function1`, are what remains.
- Schema 7 drops the title index. The measurements below decided it.
- An open sheet is rebuilt only when the palette flips between light and dark. Every
  five-minute step now redraws the day; the previous check compared the paper colour
  alone and skipped steps that moved only sheet or line colours.
- Ripples no longer redraw at rest. From Android 12 the platform's ripple answers every
  change of state, the window gaining focus at launch included, with a background
  animation that shows nothing unless the view is focused or hovered, and each step
  redraws the view. The date heading's ripple made every launch draw eleven frames where
  1.3.3 drew three. The planner's ripples now hear only of presses, focus and hover.
- Removed two unused declarations, packaging rules for libraries that no longer ship,
  and the AndroidX names from the bundled licence notice, which now covers the Kotlin
  standard library.

## Measurements

| Measurement | 1.3.3 | Start of this pass | After |
| --- | ---: | ---: | ---: |
| Unsigned release APK | 953,907 bytes | 261,150 bytes | 115,874 bytes |
| Uncompressed `classes.dex` | 1,793,260 bytes | 243,308 bytes | 98,248 bytes |
| Classes / method references in the dex | | 348 / 1,928 | 100 / 861 |
| Installed, fresh (APK, odex, vdex) | 2,842,746 bytes | 296,225 bytes | 143,285 bytes |
| Installed, compiled | 7,117,162 bytes | 1,054,545 bytes | 398,149 bytes |
| Database holding a few blocks | 28 KiB | 24 KiB | 20 KiB |
| Frames drawn by a launch | 3 | 11 | 4 |
| Launch, main-thread CPU | 26.4 ms | 25.9 ms | 20.9 ms |
| Launch, render-thread CPU | 56.9 ms | 96.7 ms | 60.9 ms |
| Launch, whole process CPU | 102.2 ms | 171.5 ms | 100.0 ms |
| Six scroll gestures, main-thread CPU | 301 and 346 ms | | 144 and 186 ms |
| Heaviest frame opening the date sheet, main thread | | 4.4 ms | 4.4 ms |

Sizes and timings are from debug-signed, minified copies under separate application IDs
on the Android 17 emulator (x86_64, 1344x2992); the debug signature adds about 8 KiB to
each APK. "Compiled" is 1.3.3 after its bundled profile was applied (`speed-profile`)
and the other two with every method compiled (`speed`), their upper bound; neither
bundles a profile. CPU times are medians of eight launches made in touch mode after a
force-stop, alternating between builds, read from each thread's `schedstat` 1.2 s after
`am start -W` returns; the scroll figures are two runs of six 400 ms swipes. Frame
counts and frame times are from `dumpsys gfxinfo framestats` (`SyncQueued -
HandleInputStart`). Wall-clock launch time is about 105 ms for all three builds and this
emulator cannot resolve differences of a few milliseconds, so none is claimed.

With three blocks on today, a following frame recorded the day again, meaning the blocks
arrived after the first frame, in 2 of 16 cold launches at the start of the pass and in
none of 16 after an earlier build of this pass; the sample is small.

## Title index

Eight blocks a day, 24 repeating titles and one block in six with a title used once.
Timings are medians of 101 runs on the emulator, through `PlannerDatabase`:

| Blocks | File with index | File without | Never-used title, without | Routine title, without | With index |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 2,920 (one year) | 253,952 | 163,840 | 0.30 ms | 0.017 ms | 0.027 ms |
| 14,600 (five years) | 1,196,032 | 700,416 | 1.35 ms | 0.014 ms | 0.014 ms |
| 29,200 (ten years) | 2,363,392 | 1,392,640 | 2.81 ms | 0.014 ms | 0.014 ms |
| 58,400 (twenty years) | 4,755,456 | 2,801,664 | 6.32 ms | 0.014 ms | 0.013 ms |

The index was 41% of the file. Without it, the duration of the last block with the same
title is found by walking back through the day index: a handful of rows for a title in
regular use, every earlier block for a title never used. That walk runs on the worker
thread inside a create whose commit already waits on the disk. Reading a day is
unchanged at about 0.02 ms at every size. To restore the index, add its `CREATE INDEX`
to `createSchema` and raise the schema version.

## Verification

- `:app:test`, `:app:lintRelease`, `:app:verifyPrivacy` and `:app:assembleRelease` pass:
  66 unit tests and no lint errors. The flow de-duplication test went with the flow.
- 30 instrumented tests pass on the Android 17 emulator, including the 50,400-task
  migration from schema 5, which now also asserts that no title index is left, and two
  new ones: a write changing nothing leaves the list on screen untouched, and a colour
  step leaves a half-typed title sheet alone while the light and dark flip rebuilds it
  around its text.
- A scripted journey ran on the start-of-pass build and this one side by side inside one
  five-minute palette step, once in the light palette and once in the dark: launch, a future day, the create sheet, typing, one, two and
  three columns, the actions sheet, rename, delete with its snackbar, undo, a move, the
  date sheet, the next month, reminders on and off, and back. All 19 comparable
  screenshots matched in every pixel outside the status bar's clock. Injected drags are
  not exact on this emulator: over 20 repeats this build landed on the minute every time
  and the start-of-pass build was five minutes out once.
- Reminders on the minified copy: the receiver was disabled until the switch was turned
  on, creating a block armed the alarm, the warning and start notifications posted and
  handed over, and after the process was killed a delivered alarm started it and posted
  the reminder.
- A copy holding schema 6 data opened, migrated in one transaction and showed its blocks.
- A copy of the published 1.3.3 source, given blocks on two days and with reminders on,
  was updated in place: the alarm was re-armed before the app was opened, every block and
  the reminders switch were kept, the database moved to schema 7 and the rollback journal,
  and a new block saved.
- The heading's pressed and released states match the start-of-pass build in every pixel.
- The signed APK carries the same signing certificate as 1.3.3, and its dex is identical
  to that of the copy these checks ran on.
- Not run: any device or Android version other than the Android 17 emulator.

# Native-view optimisation verification — 4 October 2026

This pass starts from the supplied **uncommitted native-view implementation**, not
from the published Compose app. Earlier measurements below describe earlier passes
and are historical. Existing storage migrations and native controls are preserved.

## Changes in this pass

- Replaced the remaining AndroidX collection dependency with a fixed-size primitive
  ID lookup dedicated to block layouts. The map has no boxing, entries, resize or
  deletion machinery. Collision, missing-ID, zero/negative-ID and legacy crowded-day
  tests preserve the layout contract.
- Dense days retain a smaller margin around the viewport, updated at half-hour
  boundaries. Ordinary scroll frames no longer allocate a range. Tile reconciliation
  clones the platform's primitive arrays directly instead of reinserting every key.
- Long tiles redraw their pinned labels only when their actual offset changes. The
  five-minute colour palette is shared between launch warm-up and foreground ticks.
- Fixed the foreground clock: it previously tried to schedule before collection was
  active, so time and palette updates could stop after launch. Repeated starts cancel
  the previous collector, and stopping removes the clock callback.
- Cancelled drags, detached tiles and stopped screens release gesture interception
  and frame callbacks. Detached snackbars release their animation and timeout callbacks.
  Older Android versions receive supported haptic constants.
- Removed the obsolete Compose hit-test implementation and its implementation-only
  tests, the unused tile accessor, and stale dependency locks/profile instructions.
  Native touch-target and gesture tests cover the replacement paths.
- Converted the remaining Compose-only tests to native Android tests, repaired two
  release lint errors, and added activity recreation, clock lifecycle, colour and
  gesture cancellation regression checks. Tests and measurement fixtures do not ship
  in the release APK. Required notices, migrations and user history are retained.

## Measurements

| Measurement | Supplied working tree | After this pass |
| --- | ---: | ---: |
| Unsigned release APK | 264,542 bytes | 261,150 bytes |
| Uncompressed `classes.dex` | 246,700 bytes | 243,308 bytes |
| Dense layout plus 1,008 ID lookups, host median | 18.10 µs | 10.47 µs |
| Allocation for that operation | 26,864 bytes | 24,784 bytes |
| Views in the crowded-day process snapshot | 479 | 430 |
| Cold launch, Android emulator median | 141.5 ms | 142.5 ms |

The layout benchmark uses a warmed host JVM, seven batches of 5,000 operations and a
1,008-task day with seven ten-minute blocks at each start. Allocation is measured with
the JVM thread-allocation counter. This is an operation benchmark, not a frame-rate
claim.

Android measurements use separate debug-signed, minified APK copies on the Android 17
Pixel 9 Pro XL emulator, with 50,400 historical tasks and 1,008 tasks today. The APKs'
manifest package IDs alone were changed to isolate them; the production package and
its data were untouched. Twelve measured launches per build followed one warm-up,
with alternating order and no build running. Before samples: 150, 180, 145, 127, 144,
128, 144, 120, 131, 148, 139, 123 ms. After: 126, 144, 153, 141, 145, 140, 130, 147, 126,
151, 128, 160 ms. The launch difference is within the run-to-run spread. PSS snapshots
were 26,314 and 27,035 KiB respectively; these do **not** establish a total-memory
reduction. The two captured crowded-day screenshots matched every pixel.

## History storage

Schema 6 is unchanged. The existing date/start and title/date/start indices preserve
bounded indexed queries, and the three-day cache does not load the historical database
into the interface. Removing the title index would save disk space at the cost of
scanning history when a new title is used; it remains intentional.

Host SQLite 3.51.2, 4 KiB pages, full auto-vacuum, eight 45-minute tasks per day and 20
repeating titles of the form `Task 5 – Focus`:

| Tasks | Database bytes | Day read | Previous duration | Next reminder |
| ---: | ---: | ---: | ---: | ---: |
| 100 | 24,576 | 5.18 µs | 3.29 µs | 2.83 µs |
| 1,000 | 106,496 | 7.14 µs | 3.32 µs | 3.10 µs |
| 2,920 (one year) | 266,240 | 7.21 µs | 3.37 µs | 3.15 µs |
| 29,200 (ten years) | 2,461,696 | 7.70 µs | 3.57 µs | 3.41 µs |
| 100,800 | 8,523,776 | 8.46 µs | 3.79 µs | 3.37 µs |

Times are medians of seven batches of 3,000 reads, including Python/SQLite call overhead.
Longer or more varied titles change storage costs. Both isolated Android databases
with 51,408 unique-title tasks occupied 4,444,160 bytes and passed `integrity_check`.
No historical tasks are automatically discarded to achieve these sizes.

## Verification

- 67 unit tests and 29 Android tests pass, including schema versions 1–5, a 50,400-task
  migration with every stored field verified, deletion space recovery, reopening,
  transaction rollback/observers, reminders and a 1,008-task interactive day.
- Native tests cover create/rename/delete/undo, seven day swipes each way, scrolling
  to both ends of a dense day, drag/resize, accessibility actions, minimum touch size,
  large fonts, title limits including emoji, unchanged warm-up rendering, continuous
  status-bar tint, clock lifecycle, detaching a held tile, and an unsaved title and
  selected date surviving activity recreation.
- The isolated minified release passed create, rename, delete/undo, day navigation,
  calendar/reminder toggling, alarm scheduling and persistence after process restart.
- `:app:test`, `:app:lintRelease` (zero errors), `:app:verifyPrivacy`, R8/resource
  shrinking and `:app:assembleRelease` pass. The final distributable build is unsigned;
  publication and production signing are separate release steps.

```bash
JAVA_HOME=/home/adiseny/.local/opt/android-studio/jbr ./gradlew \
  :app:test :app:connectedInstrumentedTestAndroidTest \
  :app:lintRelease :app:verifyPrivacy :app:assembleRelease
```

---

# Optimisation verification — 4 October 2026

Compared with 1.3.3 as published and with the working tree supplied at the start of this pass (1.3.3 plus the uncommitted storage, schema 6 and platform title-field changes). The interface and features are unchanged.

## Changes

- The baseline profile's library rules were measured again. The list in the tree predated the platform title field: 1,632 of its rules named the Compose text field that is gone, and one named the code that replaced it. It also carried the code that screen-reading tools run (81 rules for Compose's accessibility delegate alone), which every install then compiled. The new list is driven by touch alone and holds 2,563 classes and 11,161 methods in place of 3,112 and 14,387; after R8, 1,445 and 4,279 in place of 1,487 and 4,566.
- The reminder receiver is enabled only while reminders are on. Off, which is the default, a restart, a clock change or an update no longer starts the process. The first reminder sync in a process restates the setting; the system keeps it across restarts and updates.
- The merged manifest no longer names two foldable-device libraries, which the system loaded into every process where a device has them, or AndroidX's component factory, which only unwraps components the app does not have.
- R8 no longer keeps reflection attributes nothing reads, or the native declarations of the path library whose binary the APK already left out. `-checkdiscard` now covers that whole package.
- The title field's selection toolbar no longer offers Share, which the platform's field had added and 1.3.3 never had. Its actions are those of 1.3.3: Cut, Copy, Paste, Select all and, in the toolbar's overflow, the device's Read aloud.
- The changelog and the 3 October notes below described a one-off database rebuild the code does not perform; both now say what it does.

Audited and left as they are: the profile installer's receiver (removing it from release builds saves 3 KB, but the benchmark build types share the release sources and the profile and benchmark tasks need it), the title-history index (dropping it saves 35 to 39% of the database but makes creating a block with a new title scan every earlier row), the retained library code (over nine tenths of the dex, with no part the app could shed short of replacing Compose), cold-start work, the tile and gesture code, and unused declarations, of which a scan found none.

## Measurements

Android 17 emulator (x86_64), isolated debug-signed copies of each minified release build installed side by side with empty data.

| Measurement | 1.3.3 | Start of pass | After |
| --- | ---: | ---: | ---: |
| Unsigned release APK | 953,907 bytes | 1,511,723 bytes | 1,510,079 bytes |
| `classes.dex` | 1,793,260 bytes | 1,482,140 bytes | 1,480,496 bytes |
| Fresh install (APK and verification data) | 2,842,746 bytes | 1,587,690 bytes | 1,587,662 bytes |
| Installed after first profile compilation | 7,117,162 bytes | 4,532,642 bytes | 4,380,830 bytes |
| Compiled code within that | 3,907,408 bytes | 2,627,672 bytes | 2,492,304 bytes |
| Cold launch, median of 36 | 110.0 ms | 110.5 ms | 113.0 ms |
| Title sheet's first opening, median of 12 | 7.1 ms | 9.2 ms | 8.0 ms |
| Date sheet's first opening, median of 12 | 8.4 ms | 9.9 ms | 9.9 ms |

The installed figures are the debug-signed APK plus `base.odex`, `base.vdex` and `base.art` after `cmd package compile -m speed-profile -f`, with the bundled profile installed by the app itself. Launch times are `am start -W` after `am force-stop`, the three builds taken in rotation; their quartiles span 101 to 132 ms for every build. Sheet times are the main-thread time of the three frames from the tap, in a process that has not opened the sheet before; quartiles span about 6 to 13 ms for every build. The timing differences between builds are inside that spread: this pass and the tree it began from neither speed up nor slow down launch or the sheets by an amount the emulator can show. The storage figures are exact.

A block costs about 70 bytes of database (host SQLite, schema 6, 20 titles, eight blocks a day): 221 KB for a year, 2.0 MB for ten.

## Verification

- 69 unit tests and 27 Android tests pass, the new one covering the receiver's state as reminders are switched and as a new process restates it.
- Release lint (no errors), `verifyPrivacy`, R8 with both `-checkdiscard` guards, resource shrinking and release assembly pass.
- On the minified release copy: the title sheet in its rename state matches 1.3.3 pixel for pixel; empty and typed it differs only in the anti-aliasing of the placeholder and the cursor column. A typed title survives the activity being recreated by a font-size change.
- Reminders, end to end on the minified release copy: off after a write, the receiver is disabled; switching on enables it and arms an exact alarm; with the process killed, the alarm started it at the minute and posted the warning with its countdown, and five minutes later the start reminder replaced it and the next alarm was armed. Installing an update over it kept the receiver enabled and re-armed the alarm without the app being opened. Switching off withdrew the alarm and disabled the receiver.
- The profiling journey ran on an unminified copy under its own application ID, checking the screen after each step: create (button and keyboard action), rename, paste, the title limit, every way of dismissing a sheet, move, both resizes, edge scrolling, seven columns and a refused eighth block, delete with undo, timeout and back, day swipes, the date sheet, the notification permission, reminders on and off, a process started for a reminder, and repeated cold starts. The normal application package and its data were not touched.

---

# Optimisation verification — 3 October 2026

Compared with the working tree supplied at the start of this pass, including the existing uncommitted storage and baseline-profile changes. The interface, task history, overlap rules, undo, accessibility and reminder features are retained.

## Changes

- Schema 6 stores duration only in the task row. The narrower title/date/start index uses SQLite's implicit row ID to answer the complete history ordering without a temporary sort.
- The obsolete Room identity table is removed. Task IDs, titles, dates, durations and the next generated ID are preserved. Whole unused pages are returned at commit after deletions without any rebuild: Android creates its databases with auto-vacuum on, which the migration test asserts.
- Dense timelines estimate the initial viewport before measurement, avoiding a temporary composition of the entire day. The same scroll calculation supplies the measured first-frame position.
- Unchanged active reminders skip notification construction and reposting. Rename, timing and overlap-colour changes still refresh them; clock-change broadcasts also reset their expiry timers.
- Repository writes call the DAO transaction directly, removing the callback adapter and its coroutine wrappers. Observers receive one invalidation per committed batch and none for a rolled-back batch.
- A late failed time write cannot add a distant day back to the three-day cache.

The audit also covered overlap calculations, drag/resize, scrolling, calendar and text rendering, launch work, assets, dependencies and release shrinking. Existing primitive layout maps, constant-time placement checks, nearby-day prefetching, background database access, paused background clocks and R8/resource shrinking remain useful. Migration paths, licences, tests and baseline-profile rules are required; they are not dead application code.

## Storage and SQL measurements

| Measurement | Before | After |
| --- | ---: | ---: |
| Database with 100,800 tasks | 6,610,944 bytes | 6,393,856 bytes |
| Previous-duration query, median | 4.48 µs | 3.77 µs |
| Individual delete/commit, median | 18.96 µs | 19.31 µs |
| Unsigned release APK | 1,825,075 bytes | 1,827,047 bytes |

The SQL measurements use host SQLite 3.51.2, 100 days of 1,008 tasks, 30 repeating titles and seven tied starts per ten-minute interval. Query times are medians of seven batches of 3,000 reads; deletion times cover 200 individual commits. These are host measurements, not Android frame-time claims. Auto-vacuum returns whole unused pages and does not repack partially occupied ones; retained history still requires storage. See [SQLite's auto-vacuum documentation](https://www.sqlite.org/pragma.html#pragma_auto_vacuum).

## Verification

- 69 unit tests cover repository behaviour, overlap equivalence/stress, geometry, colours and time handling.
- 26 Android tests include a 50,400-task version-5 database spanning almost 20 years: every field is checked after migration, deleted space is reclaimed, IDs remain monotonic, and remaining tasks survive reopening. Versions 1–4 and an abandoned write-ahead log are covered separately.
- Transaction observer tests cover rollback, a 100-write batch and a missing-row deletion.
- Reminder tests cover unchanged notifications, renaming, overlap colours, clock-change refresh and deletion.
- Release lint, privacy verification, R8 optimisation, resource shrinking and release assembly pass.
- A debug-key-signed minified release under `com.privateplanner.instrumented` passed creation, rename, deletion/undo, day navigation, calendar/reminder-control rendering and persistence after force-stop. All 51,408 pre-existing test tasks were compared field-for-field after its migration and workflow; SQLite's integrity check passed. The normal application package and its data were not touched. Profile installation returned success.

## Crowded-day release measurements

On the Android 17 Pixel 9 Pro XL emulator, both isolated minified release copies used the same 51,408-task database: 1,008 tasks today and 50,400 historical tasks. Nine force-stop/launch cycles were run for each; the first was discarded, including the after-build's one-time migration. No Gradle build ran during timing.

| Measurement | Before | After |
| --- | ---: | ---: |
| Cold launch to initial display, median of eight | 321 ms | 260.5 ms |
| PSS immediately after the ninth launch | 62,818 KiB | 37,394 KiB |

Launch samples were 315, 309, 334, 345, 344, 316, 319, 323 ms before and 258, 263, 243, 266, 258, 329, 273, 242 ms after. Timings use `adb shell am start -W` following `am force-stop`; memory is a `dumpsys meminfo -s` snapshot, including transient startup allocations, not a steady-state heap measurement. These single-emulator results do not guarantee frame rates or zero lag on every device. The unchanged part of the timeline screenshot matched exactly; differences were confined to the advancing clock regions.

```bash
JAVA_HOME=/home/adiseny/.local/opt/android-studio/jbr ./gradlew \
  :app:test :app:connectedInstrumentedTestAndroidTest \
  :app:lintRelease :app:assembleRelease
```

---

# Optimisation verification — 26 September 2026

Compared with the working tree supplied at the start of this pass, including its existing uncommitted optimisations. The interface and features are unchanged.

## Changes

- Reminder and previous-duration queries seek by both date and minute using the existing indices. No schema change or data migration is required.
- Seven SQLite statements retain their wrappers and bindings, with every argument rebound on the database thread before reuse.
- Layout results use primitive block IDs, and overlap clusters share scratch arrays instead of allocating two arrays per cluster.
- Empty-space tap checks reject the time gutter immediately and calculate column geometry only for blocks at the tapped time.
- Once disabled reminders have cleared persistent state, subsequent writes skip the reminder coroutine and clock/system work. Toggling the switch invalidates that shortcut.
- Two package rules replace 1,057 lines of generated baseline-profile configuration. Release builds expand them against current bytecode, including changed signatures.
- Creation validates its rounded end time against legacy unsnapped blocks. A stale database call in an existing gesture test was also repaired.

## Measurements

| Measurement | Before | After |
| --- | ---: | ---: |
| Next-reminder SQL query | 16.74 µs | 0.89 µs |
| Previous-duration SQL query | 30.59 µs | 2.93 µs |
| Allocation per dense-day layout | 82,680 bytes | 26,848 bytes |
| CPU time per dense-day layout | 11.85 µs | 13.01 µs |
| Unsigned release APK | 982,011 bytes | 981,959 bytes |

SQL measurements are host microbenchmarks on SQLite 3.51.2: 100 days with 1,008 blocks per day, seven overlapping blocks per ten-minute interval, five batches of 2,000 reads. Android query results were separately checked against a reference across dates, tied starts, case-insensitive titles, missing rows and concurrent callers.

Layout measurements use a warmed host JVM, a 1,008-block day, and median results across seven batches of 2,000 calculations. The primitive map and shared buffers reduce allocation by 67.5%, with a roughly 1.2 µs CPU tradeoff in this particular benchmark. These numbers describe individual operations, not whole-app speed or a guarantee about frame times on physical devices. APK size is essentially unchanged.

## Verification

- 68 unit tests and 21 Android instrumentation tests passed on the Android 17 emulator.
- Release lint passed with no errors; existing toolchain/version and KTX suggestion warnings remain.
- `verifyPrivacy`, R8 optimisation, resource shrinking and release assembly passed.
- Expanded profile output contains the updated layout signature and cached-statement methods.
- A separately signed and isolated minified release copy passed creation, rename, deletion/undo, day navigation, calendar/reminder-control rendering and persistence after process restart. Profile installation returned success. The normal application package and its data were not replaced.

Commands used with the installed Android Studio JDK:

```bash
JAVA_HOME=/home/adiseny/.local/opt/android-studio/jbr ./gradlew \
  :app:test :app:lintRelease :app:assembleRelease \
  :app:connectedInstrumentedTestAndroidTest
```

The 1.3.2 production release APK is signed and verified against the same certificate as the published 1.3.1 release.
