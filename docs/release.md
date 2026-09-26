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

Generate an app-specific baseline profile on a JIT-enabled physical test device:

```bash
./gradlew :app:generateReleaseBaselineProfile
```

Run macrobenchmarks only on a dedicated device:

```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
```

Until a measured profile is committed, `app/src/main/baseline-prof.txt` uses package rules for the app's own classes and methods. AGP expands them against the release bytecode and merges dependency profiles, so code changes cannot leave stale method signatures. Replace these fallback rules once a measured profile covers startup and interactions. The manifest removes androidx.startup, so sideloaded installs get the profile from `PlannerApp`, which calls `ProfileInstaller.writeProfile` a few seconds after launch.

Profile generation and macrobenchmarks temporarily install, clear or remove `com.privateplanner`. They must never run on a device containing plans that need to be retained. Profile generation also requires `dalvik.vm.usejit=true`.

Benchmark traces and results are written to `benchmark/build/outputs/connected_android_test_additional_output/`.

## 5. Publish

- Commit and push the verified source.
- Create an annotated `v<versionName>` tag on that commit and push it.
- Rename the signed APK to `daytile-<versionName>.apk`.
- Produce `SHA256SUMS.txt` for that exact file.
- Publish a GitHub release using the corresponding changelog entry and attach only the signed APK and checksum file.
- Download the public assets again and verify their hashes, signature, package name, version and permission manifest.
