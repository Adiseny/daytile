param(
    [string]$Serial
)

$ErrorActionPreference = "Stop"

$javaHome = "C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot"
if (Test-Path $javaHome) {
    $env:JAVA_HOME = $javaHome
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
}

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb was not found at $adb"
}

$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" } | ForEach-Object {
    ($_ -split "\s+")[0]
}

if (-not $Serial) {
    if ($devices.Count -ne 1) {
        throw "Expected exactly one connected Android device. Connected: $($devices -join ', '). Re-run with -Serial <serial>."
    }
    $Serial = $devices[0]
} elseif ($Serial -notin $devices) {
    throw "Device '$Serial' is not connected. Connected: $($devices -join ', ')"
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    & $adb -s $Serial @AdbArgs
}

function Get-Setting {
    param([string]$Namespace, [string]$Name)
    ((Invoke-Adb shell settings get $Namespace $Name) | Out-String).Trim()
}

function Set-Setting {
    param([string]$Namespace, [string]$Name, [string]$Value)
    Invoke-Adb shell settings put $Namespace $Name $Value | Out-Null
}

$savedSettings = @{
    window_animation_scale = Get-Setting global window_animation_scale
    transition_animation_scale = Get-Setting global transition_animation_scale
    animator_duration_scale = Get-Setting global animator_duration_scale
    font_scale = Get-Setting system font_scale
    accelerometer_rotation = Get-Setting system accelerometer_rotation
    user_rotation = Get-Setting system user_rotation
}

try {
    Set-Setting global window_animation_scale 0
    Set-Setting global transition_animation_scale 0
    Set-Setting global animator_duration_scale 0
    Set-Setting system font_scale 1.0
    Set-Setting system accelerometer_rotation 0
    Set-Setting system user_rotation 0

    .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest

    if ($Serial -like "emulator-*") {
        Invoke-Adb uninstall com.privateplanner | Out-Null
    }
    Invoke-Adb install -r "app/build/outputs/apk/debug/app-debug.apk" | Out-Host
    Invoke-Adb install -r "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" | Out-Host
    & $adb -s $Serial shell am instrument `
        -w `
        -e class com.privateplanner.ui.ReadmeScreenshotTest `
        com.privateplanner.test/androidx.test.runner.AndroidJUnitRunner | Out-Host

    $localDir = "docs/readme"
    New-Item -ItemType Directory -Force -Path $localDir | Out-Null
    $remoteDir = "/sdcard/Android/data/com.privateplanner/files/readme-screenshots"
    $names = @(
        "daytile-timeline.png",
        "daytile-create.png",
        "daytile-actions.png"
    )

    foreach ($name in $names) {
        Invoke-Adb pull "$remoteDir/$name" "$localDir/$name" | Out-Host
    }

    Get-ChildItem $localDir -Filter "daytile-*.png" | Sort-Object Name | ForEach-Object {
        "{0}  {1:n0} bytes" -f $_.FullName, $_.Length
    }
} finally {
    foreach ($entry in $savedSettings.GetEnumerator()) {
        $namespace = if ($entry.Key -in @("font_scale", "accelerometer_rotation", "user_rotation")) {
            "system"
        } else {
            "global"
        }
        if ($entry.Value -and $entry.Value -ne "null") {
            Set-Setting $namespace $entry.Key $entry.Value
        }
    }
}
