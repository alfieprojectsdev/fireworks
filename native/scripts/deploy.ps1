# Windows twin of deploy.sh: build, install and launch on the connected phone.
#   .\scripts\deploy.ps1 --ez testMode true --ez hud true
#   .\scripts\deploy.ps1 --es simulate church --ef simSpeed 20 --ez hud true
# Connect first (wireless debugging): adb connect <ip>:<port>
# Gradle needs JAVA_HOME on a JDK 17-21 (Temurin 21 works; Android Studio's JBR 25 does not).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

# adb is often not on PATH on Windows. Look where the SDK actually is: sdk.dir
# in local.properties, then ANDROID_HOME / ANDROID_SDK_ROOT, then Android
# Studio's default location, then PATH.
function Resolve-Adb {
    $sdkDirs = @()
    if (Test-Path local.properties) {
        $line = Get-Content local.properties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            # local.properties escapes ':' and '\' (C\:\\Users\\...).
            $sdkDirs += (($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\:', ':' -replace '\\\\', '\')
        }
    }
    if ($env:ANDROID_HOME) { $sdkDirs += $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { $sdkDirs += $env:ANDROID_SDK_ROOT }
    if ($env:LOCALAPPDATA) { $sdkDirs += (Join-Path $env:LOCALAPPDATA 'Android\Sdk') }
    foreach ($dir in $sdkDirs) {
        $candidate = Join-Path $dir 'platform-tools\adb.exe'
        if (Test-Path $candidate) { return $candidate }
    }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw "adb not found: set sdk.dir in local.properties or ANDROID_HOME"
}
$adb = Resolve-Adb
Write-Host ">> using $adb"

& .\gradlew.bat :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
& $adb shell am force-stop dev.alfieprojects.mirroredlotus
& $adb shell am start -n dev.alfieprojects.mirroredlotus/.MainActivity @args
Write-Host ">> logs: & '$adb' logcat -s Lotus LotusProbe LotusCamera LotusRenderer LotusAudio LotusLocation"
