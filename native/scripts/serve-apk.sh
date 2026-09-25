#!/usr/bin/env bash
# The native twin of the repo-root serve_apk.sh: build the debug APK and serve
# it over WiFi on port 8000 so a phone can download and sideload it. Every
# build carries a newer versionCode (see app/build.gradle.kts), so it always
# installs as an upgrade.
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew :app:assembleDebug
APK_DIR="app/build/outputs/apk/debug"
cp -f "$APK_DIR/app-debug.apk" "$APK_DIR/lotus-native.apk"

echo "====================================================="
echo "Open one of these on the phone (same WiFi or tailnet):"
for IP in $(hostname -I 2>/dev/null || ipconfig getifaddr en0 2>/dev/null || echo localhost); do
    echo "   http://$IP:8000/lotus-native.apk"
done
echo "Ctrl+C to stop."
echo "====================================================="
cd "$APK_DIR"
python3 -m http.server 8000
