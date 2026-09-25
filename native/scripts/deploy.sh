#!/usr/bin/env bash
# Build the debug APK, install it on the connected phone and launch it.
# Any extra arguments are passed to `am start` as launch switches, e.g.
#   scripts/deploy.sh --ez testMode true --ez hud true
#   scripts/deploy.sh --es simulate church --ef simSpeed 20 --ez hud true
# Connect first (wireless debugging): adb connect <ip>:<port>
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop dev.alfieprojects.mirroredlotus || true
adb shell am start -n dev.alfieprojects.mirroredlotus/.MainActivity "$@"
echo ">> logs: adb logcat -s Lotus LotusProbe LotusCamera LotusRenderer LotusAudio LotusLocation"
