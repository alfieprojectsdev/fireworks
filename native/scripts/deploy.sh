#!/usr/bin/env bash
# Build the debug APK, install it on the connected phone and launch it.
# Any extra arguments are passed to `am start` as launch switches, e.g.
#   scripts/deploy.sh --ez testMode true --ez hud true
#   scripts/deploy.sh --es simulate church --ef simSpeed 20 --ez hud true
# Connect first (wireless debugging): adb connect <ip>:<port>
set -euo pipefail
cd "$(dirname "$0")/.."

# adb is often not on PATH (notably in Git Bash on Windows). Look where the SDK
# actually is: sdk.dir in local.properties, then ANDROID_HOME / ANDROID_SDK_ROOT,
# then PATH.
resolve_adb() {
    local sdk="" d exe
    if [ -f local.properties ]; then
        # local.properties escapes ':' and '\' (C\:\\Users\\...).
        sdk=$(sed -n 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' local.properties | head -n 1 |
            sed -e 's/\\:/:/g' -e 's/\\\\/\\/g')
    fi
    for d in "$sdk" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        [ -n "$d" ] || continue
        if command -v cygpath >/dev/null 2>&1; then d=$(cygpath -u "$d"); fi
        for exe in "$d/platform-tools/adb" "$d/platform-tools/adb.exe"; do
            if [ -x "$exe" ]; then echo "$exe"; return 0; fi
        done
    done
    if command -v adb >/dev/null 2>&1; then command -v adb; return 0; fi
    echo "adb not found: set sdk.dir in local.properties or ANDROID_HOME" >&2
    return 1
}
ADB=$(resolve_adb)
echo ">> using $ADB"

./gradlew :app:assembleDebug
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am force-stop dev.alfieprojects.mirroredlotus || true
"$ADB" shell am start -n dev.alfieprojects.mirroredlotus/.MainActivity "$@"
echo ">> logs: \"$ADB\" logcat -s Lotus LotusProbe LotusCamera LotusRenderer LotusAudio LotusLocation"
