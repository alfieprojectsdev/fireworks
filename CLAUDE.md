# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

A GPS-triggered AR anniversary app deployed as an Android APK via Capacitor. When the user is within 100 meters of a specific church in Manila (14.658888, 121.071173), the app activates a Three.js AR scene overlaid on the camera feed, showing fireworks and a spinning cylindrical marquee anchored to the church's physical location. The occasion: May 15, 2008 (wedding anniversary with "Bhaze").

## Native port (`native/`)

`native/` is a separate Gradle project: a native Kotlin + GLES 3 + Camera2 port of this experience, built to remove the WebView build's field latency. It has the same sites, acts, monument, fireworks and audio. Camera and overlay are drawn in one GL pass, posed at the camera frame's exposure instant, from 200 Hz rotation vectors. It installs as **Lotus Native** (`dev.alfieprojects.mirroredlotus`), side by side with this Capacitor app, which stays as the A/B reference and the rollback.

- Work in `native/` follows `native/CLAUDE.md` (the `:core`-with-tests rule, frame and pose-timing landmines).
- `./gradlew :core:test` (from `native/`) runs the JVM tests with no Android SDK. The current state and the next on-device steps are in `native/docs/HANDOVER.md`.
- Behaviour changes to the web build's story (sites, act distances, fireworks, audio constants) should be mirrored in `native/core` and recorded in `native/docs/PARITY.md`, or the two builds drift.

## Build & Deploy

The entire build-and-deploy workflow is:
```bash
./serve_apk.sh
```
This runs `npx cap sync android`, builds the debug APK via Gradle, then serves it over WiFi on port 8000 so the phone can download and sideload it.

To run steps individually:
```bash
npx cap sync android          # sync web assets to Android project
cd android && ./gradlew assembleDebug   # build APK only
```

The built APK lands at `android/app/build/outputs/apk/debug/app-debug.apk`.

There are no tests, no lint scripts, and no dev server—the app is static HTML.

## Architecture

Primary logic is split across two files: **`public/index.html`** (all JS, Three.js AR scene, GPS/sensor wiring) and **`android/app/src/main/java/com/alfie/anniversary/SensorBridgePlugin.java`** (Capacitor native plugin delivering GPS and orientation events with lower latency than the WebView APIs).

**Layered rendering (z-index stack):**
- `#camera-bg` (z=1): raw `<video>` element showing the rear camera
- `#ar-canvas` (z=2): Three.js WebGLRenderer canvas (alpha: true, so camera shows through)
- `#ui-layer` (z=3): "Locating…" status text shown before the experience triggers

**Experience trigger logic:**
- The native `SensorBridgePlugin` is the primary GPS and orientation source on Capacitor; `startWebGeoInterval()` (web geolocation `setInterval` at 3s) is the fallback when the native bridge does not deliver a location event within 2 seconds
- Triggers `startExperience()` when within 100m of `targetLat/targetLng`
- Secret: triple-tapping the invisible 100×100px `#secret-override` div (top-right corner) bypasses GPS and enters **test mode**, where the AR group spawns 40m ahead instead of over the real GPS coordinates. Each tap fires `navigator.vibrate(60)` for tactile confirmation; the third tap (which fires `startExperience`) uses pattern `navigator.vibrate([40, 60, 120])` so the wearer feels the bypass succeeded. The vibrate calls are typeof-guarded and no-op when the API is unavailable.
- While polling, the splash status text shows the live distance to the church (10m steps under 1 km, 0.1 km steps above) instead of static "Locating...". Permission/timeout/availability errors from `getCurrentPosition` surface human-readable copy in the same status line so a denied permission no longer looks like a hung app.

**3D scene structure (`createARGroup`):**
- A `THREE.Group` positioned at the GPS-calculated offset from the user (X=East, Z=North in Three.js)
- Pylon monument at shrine scale (~Quezon Memorial Shrine, ~66m): a "crown" message cylinder (`CylinderGeometry(35,35,12)`, 4096×512 canvas texture of the active site's marquee, `DoubleSide`) floating at world y≈50–62, capping four vertical stat **pylons**
- Four `PlaneGeometry` stat pylons (Years/Months/Hours/Days from the active site's anchor date) — tall columns of upright stacked glyphs (`_createPylonTexture`), ringed at 0°/90°/180°/270°, `translateZ(35)`, 50m tall (ground to world y≈50). An earlier "Tower of Light" tractor-beam cone was rejected as tacky — do not reintroduce light-beam forms
- `DeviceOrientationControls` ties the virtual camera to the phone's physical compass + gyroscope
- The compass-calibration banner is bounded by an 8-second timer; if `deviceorientationabsolute` never arrives the banner switches to "heading active" (when any `deviceorientation` event has been observed) or fades silently. The Three.js `DeviceOrientationControls` continues to drive the camera regardless of banner state.

**Fireworks system (`Firework` class):**
- Rockets use `THREE.LineSegments` for streak trails; explosions spawn 250–400 particles also rendered as line segments
- Three shell types: Peony, Willow, Chrysanthemum (affect particle count, velocity, gravity, streak length)
- `textIllumination` is a float that pulses the marquee/stat opacity brighter when a firework explodes within 150 units of the AR group

**`public/boids-app/` and `public/emitter-app/`** are completely independent static apps with no connection to the main experience. They are included in the Capacitor `webDir` but the main entry point is `public/index.html`.

## Key Constants

- `targetLat = 14.658888478751235`, `targetLng = 121.071173166497` — the church location (site id: `church`)
- `originLat = 14.651726103123695`, `originLng = 121.05472805488795` — the origin location (site id: `origin`); anchor date `2005-10-15`, marks when Alfie and Bhazel became a couple — the "monthsary" site, distinct from the 2008-05-15 wedding
- Trigger radius: 1800 m per site; the two sites are ~1.94 km apart so ranges overlap slightly — `nearestSite()` + the active-site lock guarantee exactly one site triggers
- The app is now multi-site: `SITES` array in `public/js/scene.js`, nearest in-range site (≤1800 m) triggers and locks for the session via `getActiveSite()`
- AR group spawn height: 15 meters (Y-axis); ground is at local y=−15
- Crown cylinder: radius 35 m, height 12 m, centered local y=41 (world ≈50–62 m)
- Stat pylons: `translateZ(35)`, height 50 m, centered local y=10 (ground to world ≈50 m)
- Wedding date: `new Date('2008-05-15T00:00:00')` (church site anchor)
- Monthsary date: `new Date('2005-10-15T00:00:00')` (origin site anchor)

## Capacitor Notes

- `capacitor.config.json`: `appId = com.alfie.anniversary`, `webDir = public`
- Do not manually edit files inside `android/` that Capacitor manages (e.g. `capacitor.settings.gradle`, `app/capacitor.build.gradle`). Run `npx cap sync` instead.
- The project uses `@capacitor/android` v8 with `@capacitor/core` v8, but `@capacitor/cli` v7—these are intentionally mismatched; do not "fix" them without testing.
