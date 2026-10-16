# PR Rules — Common

Rules that apply to every PR in this repository.

## Architecture

- All JS logic lives in ES6 modules under `public/js/`. The only allowed entry point is `public/index.html`, which imports from `./js/scene.js`, `./js/sensors.js`, `./js/audio.js`, and `./js/session-recorder.js`.
- Module dependency direction: `sensors.js` → `scene.js`; `scene.js` → `session-recorder.js`; `session-recorder.js` → nothing. A PR that introduces an import in the reverse direction creates a cycle and must be fixed before merge.
- No CDN dependencies. All third-party libraries must be vendored under `public/lib/` and loaded via `<script src="lib/...">` in `index.html`. The project was burned by CDN unavailability at the venue; this is a hard rule.
- Do not manually edit files under `android/` that Capacitor manages (`capacitor.settings.gradle`, `app/capacitor.build.gradle`). Run `npx cap sync` instead.
- `@capacitor/cli` v7 with `@capacitor/android` v8 and `@capacitor/core` v8 is an intentional mismatch. Do not "fix" it.

## Android / WebView

- Any `<a download>` triggered programmatically must be appended to `document.body` before `.click()` and removed immediately after. A detached anchor does not trigger a download in Android WebView — it silently does nothing.
- `navigator.vibrate` must be typeof-guarded before every call. The API is absent on some Android WebView configurations without the `VIBRATE` permission declared in `AndroidManifest`.

## Three.js

- Do not allocate `new THREE.Vector3()` (or any `THREE.*` geometry object) inside a function that is called every animation frame. Use a module-level scratch object and `.set()` it on each call. Per-frame allocation causes GC pressure on mobile.

## Distance / stage logic

- Any function that maps a distance (metres) to a label or opacity via cascading comparisons must cover every boundary explicitly. After writing or editing such a function, enumerate all distance bands and confirm none overlaps or is missing. The `_stageLabel` function was previously missing the 40–100m `stats_fading_in` band.

## Exports / data

- Before triggering any `navigator.share` or blob download, guard against empty state. An export of zero events should alert the user, not silently share an empty payload.

## Lessons learned

- **Circular import through shared constants (2026-05-20):** `session-recorder.js` imported `TARGET_LAT`/`TARGET_LNG` from `scene.js`. When `scene.js` needed to import `recordActEvent` from `session-recorder.js`, the cycle was discovered. Fixed by removing the import and calling `initRecorder(TARGET_LAT, TARGET_LNG)` from `sensors.js` at startup. Pattern: if module A needs a constant from B, and B later needs a function from A, extract the constant to a third module or pass it via an init call rather than importing across the cycle.
- **Android WebView blob download (2026-05-20):** Initial implementation called `a.click()` on a detached element. Caught in quality review. Fix: `document.body.appendChild(a)` → `a.click()` → `document.body.removeChild(a)`. Apply this pattern to every programmatic download in WebView contexts.
- **Missing stage band (2026-05-20):** `_stageLabel` had a 40–100m gap where the function returned `marquee_full` instead of the correct `stats_fading_in`. Caught in quality review. Lesson: write distance-band functions with explicit boundary tests at all threshold values before committing.
- **Hot-path Vector3 allocation (2026-05-20):** `getAlignmentData()` originally constructed `new THREE.Vector3(0, 0, -1)` on every call (called at 1 Hz from GPS handler). Promoted to module-level `_fwdScratch` reused via `.set()`. Lesson: any Three.js utility called from a sensor callback or animation loop must be allocation-free.
- **Startup path read a pose before any sensor sample (2026-09-25):** In the native port (`native/`), `--ez testMode true` started test mode from `onCreate`, before the orientation sensors were even registered. It read the renderer's default forward (north), so the monument always appeared 40 m north whichever way the phone faced. Caught in the PR #8 medium-effort review. Waiting for *any* sample was not enough either, because until the first compass reading lands, the fused heading is only the gyro's arbitrary yaw. Fixed with `PoseGate`, which waits (bounded, 2 s) for a compass-corrected sample. Lesson: a startup path driven by the launch intent runs before sensor data arrives. Anything it does that depends on the pose or heading must wait for the first *trustworthy* sample, not use a default value.
