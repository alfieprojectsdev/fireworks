# Mirrored Lotus: native Android build

A native port of the GPS-triggered AR anniversary experience in `../public/`,
rebuilt to remove the latency that made the WebView/Capacitor build jarring in
the field. Same sites, acts, monument, fireworks and sounds. The camera picture
and the overlay are now drawn in one GL pass, posed for the same instant, from
sensors read directly at 200 Hz.

- **Why it's faster**: `docs/ARCHITECTURE.md`
- **What each web feature became**: `docs/PARITY.md`
- **What to do next on the phones**: `docs/HANDOVER.md`

## Build and test

```bash
./gradlew :core:test            # 135 JVM tests - no Android SDK needed
./gradlew :app:assembleDebug    # needs the SDK (local.properties sdk.dir or ANDROID_HOME)
./gradlew :app:connectedDebugAndroidTest   # on-device smoke tests (phone connected)
```

`:app` is only configured when an Android SDK is found (the stable-still rule),
so `:core:test` runs on any JDK 17 to 21. On Windows, use `gradlew.bat`, and
point `JAVA_HOME` at Temurin 21. Android Studio's bundled JBR 25 can't run
Gradle 8.14.

## Install and run

```bash
scripts/deploy.sh --ez testMode true --ez hud true     # Git Bash / Linux / macOS
.\scripts\deploy.ps1 --ez testMode true --ez hud true  # PowerShell
scripts/serve-apk.sh                                   # WiFi sideload, like ../serve_apk.sh
```

The app installs as **Lotus Native** (`dev.alfieprojects.mirroredlotus`),
alongside the Capacitor build (`com.alfie.anniversary`), so they can be
compared side by side.

## Launch switches

Synthetic taps are blocked on the Galaxy A07, so every test control is a
launch extra (`adb shell am start -n dev.alfieprojects.mirroredlotus/.MainActivity ...`):

| Extra | Effect |
|---|---|
| `--ez testMode true` | Start in test mode: monument 40 m ahead, every act revealed (the triple-tap) |
| `--ez hud true` | Show the debug HUD from launch |
| `--es simulate church` (or `origin`) | Replace GPS with a scripted walk-in toward that site |
| `--ef simSpeed 20` | Walk-in speed, m/s (default 1.4) |
| `--ef simStartM 2000` | Walk-in start distance, m |
| `--ef simBearing 225` | Direction the walker comes from, degrees from the site |
| `--ez syncCamera false` | Pose the overlay with the newest sensor sample instead of the camera frame's exposure time (A/B) |
| `--ef headingTau 2.0` | Compass smoothing time constant, seconds |

Logs: `adb logcat -s Lotus LotusProbe LotusCamera LotusRenderer LotusAudio LotusLocation`.

## In the field

The same gestures as the web build: triple-tap the top-right corner for test
mode, triple-tap the bottom-right corner for the HUD, tap ♪ for earphone mode.
**Export Session** in the HUD opens the share sheet with the session JSON (same
format as the web export, plus latency samples). A copy is also saved to
`Android/data/dev.alfieprojects.mirroredlotus/files/sessions/`.

## Layout

```
core/   pure Kotlin, no Android imports - everything with a test
  site/         Sites, TriggerPolicy, Checkpoints
  story/        Acts, AnniversaryStats, ExperienceDirector (the per-tick story)
  fx/           FireworkSystem (SoA particles), Hsl, Rng
  orientation/  Frames, HeadingFusion, OrientationHistory, PoseSampler
  camera/       CameraTiming, CameraProjection, PreviewSizeSelector, FpsRangeSelector
  render/       Meshes, SceneLayout, MonumentPlacement, TextSpecs, ShaderSources (+ GLSL resources)
  audio/        ExplosionVoice, SpatialMixer, Compressor, Reverb, Biquad
  ui/ record/ telemetry/ sim/ geo/ math/
app/    Android: Camera2, sensors, GLES renderer, AudioTrack, location, Views
  gl/ArRenderer.kt        one GL pass: camera + monument + fireworks
  camera/CameraFeed.kt    Camera2 -> SurfaceTexture, REALTIME timestamps
  sensors/OrientationSource.kt   200 Hz rotation vectors -> heading fusion -> history
  MainActivity.kt         wiring, splash, HUD, taps, export
```
