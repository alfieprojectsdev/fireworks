# public/

Web assets served by Capacitor as the Android app's WebView content.

## Files

| File           | What                                                                 | When to read                                                              |
| -------------- | -------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| `index.html`   | All app logic: GPS trigger, Three.js AR scene, fireworks, audio      | Modifying any app behavior; debugging GPS, camera, audio, or AR rendering |
| `version.js`   | Build version constant                                               | Incrementing version after a build                                        |
| `debug.html`   | Standalone sensor/GPS debug page                                     | Diagnosing GPS or sensor issues on-device                                 |
| `ROLLBACK.md`  | Event-day rollback procedure: backup and one-step revert to prior APK | Pre-event checklist; recovering from a bad sideload                      |

## Subdirectories

| Directory     | What                                               | When to read                               |
| ------------- | -------------------------------------------------- | ------------------------------------------ |
| `boids-app/`  | Independent boids simulation; no connection to AR  | Never — unrelated to anniversary app       |
| `emitter-app/`| Independent particle emitter; no connection to AR  | Never — unrelated to anniversary app       |
| `lib/`        | Vendored JS libraries (Three.js, controls)         | Checking library versions; never edit directly |
