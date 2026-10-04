# Release process

Daytile releases must remain offline, private, minified and signed. Never publish an unsigned APK or replace an established signing key.

## 1. Prepare

- Update `versionCode`, `versionName`, `CHANGELOG.md` and any affected README guidance.
- Confirm the working tree contains only intended changes.
- Use JDK 17 or newer with the Android SDK installed.

## 2. Configure signing

The release build is signed only when all four environment variables are present:

```bash
export DAYTILE_RELEASE_STORE_FILE='/secure/path/daytile-release.jks'
export DAYTILE_RELEASE_STORE_PASSWORD='...'
export DAYTILE_RELEASE_KEY_ALIAS='daytile'
export DAYTILE_RELEASE_KEY_PASSWORD='...'
```

PowerShell equivalents:

```powershell
$env:DAYTILE_RELEASE_STORE_FILE='C:\secure\path\daytile-release.jks'
$env:DAYTILE_RELEASE_STORE_PASSWORD='...'
$env:DAYTILE_RELEASE_KEY_ALIAS='daytile'
$env:DAYTILE_RELEASE_KEY_PASSWORD='...'
```

Keep the keystore and credentials in secure, tested backups outside the repository. Every update in one Android signing lineage must use the same key; losing it prevents in-place updates permanently.

## 3. Verify

Run the complete host-side release checks:

```bash
./gradlew \
  :app:test \
  :app:lintRelease \
  :app:verifyPrivacy \
  :app:assembleRelease
```

`:app:test` replaces the old `:app:testDebugUnitTest` and `:app:testReleaseUnitTest`.
AGP 9 builds unit tests only for the `testBuildType`, so those per-variant task
names no longer exist and naming them fails the build.

With a non-production Android device connected, also run:

```bash
./gradlew :app:connectedInstrumentedTestAndroidTest
```

Connected interface tests use the isolated `com.privateplanner.instrumented` application ID. They cannot replace or clear the real `com.privateplanner` package.

Verify the final APK before publication:

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

The signature check must report a verified signer. The APK manifest must contain exactly the four reminder permissions (`POST_NOTIFICATIONS`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`) and nothing else; `verifyPrivacy` fails the build on any other permission, `INTERNET` included.

## 4. Performance checks

The interface uses Android views. Compose, the baseline-profile plugin, the profile
installer and the benchmark module have been removed; their former Gradle tasks are
not part of this project.

Use the minified release build for size and timing comparisons. Test copies must use
separate application IDs and a debug signing key, keeping the production package and
its data intact. For each comparison:

1. Record unsigned APK size and uncompressed `classes.dex` size. Keep dex uncompressed
   in the APK so modern Android devices can load it without retaining an extracted copy.
2. Run `:app:connectedInstrumentedTestAndroidTest`. This includes a 50,400-task migration,
   deletion and reopening test, a 1,008-task crowded day, native gestures and rendering,
   reminders, and activity recreation with an unsaved title.
3. Seed identical histories into the isolated minified copies. Alternate force-stop and
   `am start -W` runs, discard warm-up runs, and report the median and samples. Do not run
   Gradle during timing. Report `dumpsys meminfo -s` separately as a process snapshot.
4. Exercise create, rename, selection and paste, move and edge scrolling, both resizes,
   delete/undo, day navigation, the calendar, and reminders. Compare screenshots outside
   the advancing clock regions.

The measured workload, device, APK sizes and verification results are recorded in
[optimisation.md](optimisation.md). Keep host microbenchmarks distinct from Android
launch and frame measurements.

## 5. Publish

- Commit and push the verified source.
- Create an annotated `v<versionName>` tag on that commit and push it.
- Rename the signed APK to `daytile-<versionName>.apk`.
- Produce `SHA256SUMS.txt` for that exact file.
- Publish a GitHub release using the corresponding changelog entry and attach only the signed APK and checksum file.
- Download the public assets again and verify their hashes, signature, package name, version and permission manifest.
