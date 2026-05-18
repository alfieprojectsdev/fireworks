# public/

## Overview

All app logic lives in `index.html` — a single ~900-line vanilla JS file. No bundler, no framework. Capacitor copies `public/` verbatim into the Android WebView; a bundler would require a separate build step that complicates `npx cap sync` and the trivial `./serve_apk.sh` debug loop.

## Architecture

**Layered z-stack** (all elements `position: fixed`, full-viewport):

- `#camera-bg` (z=1): raw `<video>` showing the rear camera via `getUserMedia`
- `#ar-canvas` (z=2): Three.js `WebGLRenderer` with `alpha: true` so the camera feed shows through
- `#ui-layer` (z=3): splash text, status line, secret-override hit area, audio button, compass banner

**Lifecycle**: A 3-second `setInterval` polls `navigator.geolocation` until Haversine distance to `(14.658888, 121.071173)` drops below 100m, then calls `startExperience()`. `startExperience` is the single entry point for the full transition: it clears the GPS interval, hides the splash, acquires the camera, initializes audio, builds the Three.js scene, and starts the RAF loop. The `experienceStarted` guard prevents re-entry from either call site (GPS trigger or triple-tap bypass).

**GPS poll centralization**: `clearInterval` for the GPS poll runs inside `startExperience`, not at each call site. Both the GPS-trigger path and the triple-tap path share one cleanup point. A regression here would leave the interval running while the AR scene is active, draining battery.

**Sensor bridge**: On Capacitor, a native `SensorBridge` plugin delivers GPS and orientation events with lower latency than the WebView APIs. A 2-second watchdog falls back to `startWebGeoInterval()` (web geolocation) if the bridge does not deliver a location event — covering GPS cold-start latency. `_webGeoIntervalStarted` prevents a double-interval if both the bridge fallback path and the watchdog fire.

## Design Decisions

**Single-file vanilla JS**: Constraint. `npx cap sync` copies `public/` verbatim; a build step breaks the loop and adds regression risk on event day. Extract to ES modules post-event (see DEFERRED.md).

**Three.js v0.128 pinned**: `DeviceOrientationControls`, the AR scene geometry, and shader behavior are validated against this version. Do not upgrade without regression-testing the full AR path on a physical device.

**GPS poll every 3s instead of `watchPosition`**: Bounds battery drain and keeps trigger logic synchronous. Up to 3s latency entering the radius is acceptable for an outdoor walk-up.

**Proximity-based marquee opacity**: Marquee visible at <=30m, stat panels at <=20m. The text dramatically reveals as the wearer approaches. The scene looks empty beyond 30m — this is intentional and must be preserved.

**Distance hint bucketing**: 10m steps below 1km (GPS outdoor accuracy is ~100m class; 10m steps change visibly between successive 3s polls at walking pace ~4m/tick). 0.1km steps above 1km (sub-100m differences are within GPS noise). Floor semantics keep the displayed value monotonically decreasing.

**Compass banner 8s timeout**: Typical mid-range Android magnetometer lock is 3-6s. Doubling the upper bound covers cold-start near reinforced concrete. The `onBannerOrientation` flag distinguishes "heading functional but not absolute" from "no sensor" — both cases the banner self-dismisses, but only the former relabels to "heading active". The `DeviceOrientationControls` internal listeners must NOT be touched by this teardown; only the named `onBannerOrientation` reference is removed.

**Audio unlocked on `touchstart`**: `AudioContext` must be created/resumed inside a user-gesture handler. Creating it in the GPS callback breaks audio on Chrome WebView. This was a regression fixed previously — do not move the unlock site.

**Audio node cleanup**: Each firework explosion allocates a `PannerNode -> masterComp` graph. The nodes MUST be disconnected after the sizzle tail. `masterComp` (`DynamicsCompressor`) is created once in `initAudioGraph` and reused. A previous regression accumulated nodes without disconnecting — do not reintroduce.

**`reverbGain` wet level**: Defaults to 0. Only raised when earphones are detected. Speaker-mode stays dry to avoid muddy outdoor mix.

**Triple-tap secret haptics**: 60ms per-tap tick (Material Design keyboard-tap baseline). Third-tap success pattern `[40, 60, 120]` (Material action-confirmed short-short-long) is distinguishable from the per-tap tick without looking. All vibrate calls are typeof-guarded — `navigator.vibrate` may be undefined on some Android WebView configs.

**Audio-mode-btn opacity 0.55**: WCAG non-text contrast minimum is 3:1. For a white glyph over a sunlit Manila camera feed (~400-600 nits on OLED), alpha must be at least ~0.5. 0.55 lands above the threshold while remaining visually subordinate to the AR scene.

## Invariants

- **Marquee texture repeats 3×** around the 4096px canvas. Reducing the repeat count reintroduces a visible seam as the cylinder rotates.
- **Marquee `DoubleSide`**: The wearer can walk inside the 20m-radius cylinder and read the text from within. Single-side rendering breaks this.
- **Shaped-shell particles** (Heart/Lemniscate/Lissajous) are pre-positioned along their parametric curve at `explode()` time. Do not switch to emergent/force-based shaping — the silhouette must be visible from frame 1.
- **Willow gravity ~0.05** (stronger droop), **Chrysanthemum ~300 particles** (dense starburst). These are tuned by feel; do not normalize to a single value.
- **`textIllumination`** pulses marquee/stat opacity when any firework explodes within 150 units. Marquee and stat opacity multiply against it — do not replace with additive blending.
- **`renderer.setPixelRatio` capped at 2**: Do not pass `window.devicePixelRatio` unbounded — GPU load on mobile.
- **`fireworks` array capped at 15**: Rockets beyond that are dropped rather than rendered. Do not raise the cap without profiling on a Galaxy A-series device.
- **Personal-message inner cylinder (r=12) counter-rotates at +0.003** against the outer marquee, so it appears stationary relative to the viewer while the outer marquee spins.
- **Months stat uses `monthsRaw`**, computed from raw `getFullYear()`/`getMonth()` fields, not from the post-decrement `years` variable. `years` is conditionally decremented for sub-anniversary positions — reading it would couple the months count to the years boundary and under-count by 12 in certain calendar positions.
- **`camera.quaternion` has exactly one writer per frame**: `controls.update()` when `nativeOrientationActive=false`, the `onNativeOrientationUpdate` handler when `nativeOrientationActive=true`. Both co-writing produces per-frame quaternion flicker.
- **`userDistanceToChurch` has exactly one writer while `experienceStarted=true`**: native `onNativeLocationUpdate` (when `window.__nativeLocationActive=true`) or `watchPosition` (when native is inactive). Both co-writing causes double-updates that corrupt the proximity fade.

## Post-Event Backlog

Captured under DL-007 (deferral policy). Not shipped in the May 15, 2026 build; revisit after the event.

- Extract `Firework` class and `playExplosion` audio graph into ES modules under `public/js/`
- Evaluate Three.js upgrade beyond v0.128
- Add ESLint and Prettier config for `public/`
- Move APK builds to GitHub Actions (signed release pipeline)
- Re-evaluate single-file vanilla JS vs. small bundler (esbuild) once the experience is no longer time-critical

Deferral rationale: project constraint forbids build tooling and Three.js upgrade on event day; structural refactor risks regressing the GPS/AR/audio path on the one day the app is meant to work.
