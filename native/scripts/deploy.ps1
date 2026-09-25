# Windows twin of deploy.sh: build, install and launch on the connected phone.
#   .\scripts\deploy.ps1 --ez testMode true --ez hud true
#   .\scripts\deploy.ps1 --es simulate church --ef simSpeed 20 --ez hud true
# Connect first (wireless debugging): adb connect <ip>:<port>
# Gradle needs JAVA_HOME on a JDK 17-21 (Temurin 21 works; Android Studio's JBR 25 does not).
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

& .\gradlew.bat :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
adb install -r app\build\outputs\apk\debug\app-debug.apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }
adb shell am force-stop dev.alfieprojects.mirroredlotus
adb shell am start -n dev.alfieprojects.mirroredlotus/.MainActivity @args
Write-Host ">> logs: adb logcat -s Lotus LotusProbe LotusCamera LotusRenderer LotusAudio LotusLocation"
