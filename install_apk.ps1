# install_apk.ps1 — Helper to build, install, and launch PhoneSpeaker APK on Android
$ErrorActionPreference = "Stop"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "PhoneSpeaker v2.1 — Android Rebuild & Install Script" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# 1. Locate / Detect JAVA_HOME (prefer standard LTS JDK 17/21)
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $candidateJdks = @()
    $wildcards = @(
        "C:\Program Files\Java\jdk*",
        "C:\Program Files\Eclipse Adoptium\jdk*",
        "C:\Program Files\Microsoft\jdk*"
    )
    foreach ($pat in $wildcards) {
        $found = Get-Item -Path $pat -ErrorAction SilentlyContinue
        if ($found) {
            foreach ($item in $found) {
                $candidateJdks += $item.FullName
            }
        }
    }
    $candidateJdks += @(
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jre",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
    )

    foreach ($jdk in $candidateJdks) {
        if ($jdk -and (Test-Path "$jdk\bin\java.exe")) {
            $env:JAVA_HOME = $jdk
            Write-Host "Auto-detected JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Green
            break
        }
    }
}

if (-not $env:JAVA_HOME) {
    Write-Warning "JAVA_HOME could not be auto-detected. gradlew.bat will attempt fallback."
} else {
    Write-Host "Using JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Green
}

# 2. Compile fresh APK with gradlew.bat assembleDebug
$androidDir = Join-Path $PSScriptRoot "PhoneSpeakerAndroid"
Write-Host "`n[1/3] Building fresh PhoneSpeaker v2.1 APK..." -ForegroundColor Cyan
Push-Location $androidDir
try {
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) {
        Write-Error "gradlew assembleDebug failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$builtApk = Join-Path $PSScriptRoot "PhoneSpeakerAndroid\app\build\outputs\apk\debug\app-debug.apk"
$apkPath = Join-Path $PSScriptRoot "PhoneSpeaker.v2.1.apk"
if (Test-Path $builtApk) {
    Copy-Item -Path $builtApk -Destination $apkPath -Force
    Write-Host "Fresh APK copied to: $apkPath" -ForegroundColor Green
} else {
    Write-Error "Built APK not found at $builtApk"
}

# 3. Locate adb
$adb = "adb"
try {
    Get-Command $adb -ErrorAction Stop | Out-Null
} catch {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb) {
        $adb = $sdkAdb
    } else {
        Write-Warning "Could not locate adb.exe. APK built successfully at $apkPath."
        exit 0
    }
}

Write-Host "`n[2/3] Checking for connected Android devices via ADB..." -ForegroundColor Cyan
$devicesOutput = & $adb devices
$devices = $devicesOutput | Select-String -Pattern "\b(device|offline|unauthorized)\b"

if (-not $devices) {
    Write-Host "`nNo Android device found via USB or Wireless ADB." -ForegroundColor Yellow
    Write-Host "The fresh APK is ready at: $apkPath" -ForegroundColor Green
    Write-Host "`nTo connect your phone via Wireless ADB (No cable needed):" -ForegroundColor White
    Write-Host "  1. On your phone: Settings -> Developer options -> Wireless debugging -> ON" -ForegroundColor Gray
    Write-Host "  2. Tap 'Pair device with pairing code'" -ForegroundColor Gray
    Write-Host "  3. In PowerShell run:" -ForegroundColor Gray
    Write-Host "     & `"$adb`" pair <phone_ip>:<pairing_port>" -ForegroundColor White
    Write-Host "     & `"$adb`" connect <phone_ip>:<connect_port>" -ForegroundColor White
    Write-Host "  4. Then re-run this script: .\install_apk.ps1`n" -ForegroundColor White
    exit 0
}

Write-Host "`n[3/3] Device detected! Stopping old instance, installing fresh APK, and launching..." -ForegroundColor Green
& $adb shell am force-stop com.hasan.phonespeaker.v2
& $adb shell am force-stop com.hasan.phonespeaker

Write-Host "Installing $apkPath ..." -ForegroundColor Cyan
& $adb install -r -t $apkPath
if ($LASTEXITCODE -ne 0) {
    Write-Error "adb install failed with exit code $LASTEXITCODE"
}

Write-Host "`nLaunching PhoneSpeaker MainActivity on device..." -ForegroundColor Green
& $adb shell am start -n com.hasan.phonespeaker.v2/com.hasan.phonespeaker.MainActivity
if ($LASTEXITCODE -ne 0) {
    & $adb shell am start -n com.hasan.phonespeaker/.MainActivity
}

Write-Host "`nSuccess! PhoneSpeaker v2.1 is compiled and running on your phone." -ForegroundColor Cyan
