# Release

Daytile releases should stay offline, private, minified, and unsigned unless the signing environment is explicitly present.

## Build Checks

Run this before distributing a build:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest lintDebug verifyPrivacy assembleRelease connectedDebugAndroidTest
```

## Signing Inputs

`assembleRelease` signs only when all four variables are present:

```powershell
$env:DAYTILE_RELEASE_STORE_FILE='C:\path\to\daytile.jks'
$env:DAYTILE_RELEASE_STORE_PASSWORD='...'
$env:DAYTILE_RELEASE_KEY_ALIAS='...'
$env:DAYTILE_RELEASE_KEY_PASSWORD='...'
```

Without those variables, Gradle still builds the release artifact but leaves it unsigned. The debug key is only used by `debug`, `benchmark`, and generated local benchmark/profile builds.

## Performance Check

Use a physical device for benchmark numbers:

```powershell
.\gradlew.bat :benchmark:connectedBenchmarkReleaseAndroidTest
```

Benchmark traces and JSON output are written under:

```text
benchmark/build/outputs/connected_android_test_additional_output/
```
