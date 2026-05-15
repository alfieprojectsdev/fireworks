# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

A GPS-triggered AR anniversary app deployed as an Android APK via Capacitor. When the user is within 100 meters of a specific church in Manila (14.658888, 121.071173), the app activates a Three.js AR scene overlaid on the camera feed, showing fireworks and a spinning cylindrical marquee anchored to the church's physical location. The occasion: May 15, 2008 (wedding anniversary with "Bhaze").

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

All meaningful logic lives in one file: **`public/index.html`**. It is entirely vanilla JS with Three.js loaded from CDN.

**Layered rendering (z-index stack):**
- `#camera-bg` (z=1): raw `<video>` element showing the rear camera
- `#ar-canvas` (z=2): Three.js WebGLRenderer canvas (alpha: true, so camera shows through)
- `#ui-layer` (z=3): "Locating…" status text shown before the experience triggers

**Experience trigger logic:**
- `setInterval` polls `navigator.geolocation` every 3 seconds
- Triggers `startExperience()` when within 100m of `targetLat/targetLng`
- Secret: triple-tapping the invisible 100×100px `#secret-override` div (top-right corner) bypasses GPS and enters **test mode**, where the AR group spawns 40m ahead instead of over the real GPS coordinates

**3D scene structure (`createARGroup`):**
- A `THREE.Group` positioned at the GPS-calculated offset from the user (X=East, Z=North in Three.js)
- Cylindrical marquee: `CylinderGeometry` with a 4096px canvas texture wrapping "Happy Anniversary, Bhaze!" four times, with `DoubleSide` rendering so the user can walk inside it
- Four `PlaneGeometry` stat panels (Years/Months/Weeks/Days since 2008-05-15) arranged in a square at 0°/90°/180°/270° facing outward, 20m radius
- `DeviceOrientationControls` ties the virtual camera to the phone's physical compass + gyroscope

**Fireworks system (`Firework` class):**
- Rockets use `THREE.LineSegments` for streak trails; explosions spawn 250–400 particles also rendered as line segments
- Three shell types: Peony, Willow, Chrysanthemum (affect particle count, velocity, gravity, streak length)
- `textIllumination` is a float that pulses the marquee/stat opacity brighter when a firework explodes within 150 units of the AR group

**`public/boids-app/` and `public/emitter-app/`** are completely independent static apps with no connection to the main experience. They are included in the Capacitor `webDir` but the main entry point is `public/index.html`.

## Key Constants

- `targetLat = 14.658888478751235`, `targetLng = 121.071173166497` — the church location
- Trigger radius: 0.1 km (100 meters)
- AR group spawn height: 15 meters (Y-axis)
- Marquee cylinder radius: 20 meters, height: 4 meters
- Wedding date: `new Date('2008-05-15T00:00:00')`

## Capacitor Notes

- `capacitor.config.json`: `appId = com.alfie.anniversary`, `webDir = public`
- Do not manually edit files inside `android/` that Capacitor manages (e.g. `capacitor.settings.gradle`, `app/capacitor.build.gradle`). Run `npx cap sync` instead.
- The project uses `@capacitor/android` v8 with `@capacitor/core` v8, but `@capacitor/cli` v7—these are intentionally mismatched; do not "fix" them without testing.
