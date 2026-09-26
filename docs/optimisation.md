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
