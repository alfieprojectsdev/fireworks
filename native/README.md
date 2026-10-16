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
./gradlew :core:test            # 184 JVM tests - no Android SDK needed
./gradlew :app:assembleField    # the build for phones: non-debuggable, full speed (needs the SDK)
./gradlew :app:assembleDebug    # debuggable, for a debugger - runs at about half speed on the A07
./gradlew :app:connectedFieldAndroidTest   # on-device smoke tests (phone connected)
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

All three install the non-debuggable `field` build; `LOTUS_VARIANT=debug`
switches the deploy scripts to the debuggable one. Debug and field builds are
signed with the committed `debug.keystore`, so installs from any machine update
each other. It is a debug key and must never sign a release.

`./gradlew :app:assembleRelease` builds the one that gets published. It has
`BuildConfig.DEV_TOOLS = false`, so the launch switches below, the test-mode
triple-tap, the HUD, session export, the field-test menu and the build label
are all off, and R8 strips that code and every `Log` call. It's signed with a
key kept outside the repo, named by `lotus.release.*` in
`~/.gradle/gradle.properties`; without those it builds unsigned. A release
install and a field install have different signers, so switching between them
means uninstalling first.

The app installs as **Lotus Native** (`dev.alfieprojects.mirroredlotus`),
alongside the Capacitor build (`com.alfie.anniversary`), so they can be
compared side by side.

## Launch switches

Synthetic taps are blocked on the Galaxy A07, so every test control is a
launch extra (`adb shell am start -S -n dev.alfieprojects.mirroredlotus/.MainActivity ...`).
Keep the `-S`: it force-stops the running app first. Without it, the running
app only rereads `testMode`, `hud`, `syncCamera` and `fieldTest`.

| Extra | Effect |
|---|---|
| `--ez testMode true` | Start in test mode: monument 40 m ahead, every act revealed (the triple-tap). Waits up to 2 s for a compass-corrected heading first. |
| `--ez hud true` | Show the debug HUD from launch |
| `--es simulate church` (or `origin`) | Replace GPS with a scripted walk-in toward that site |
| `--ef simSpeed 20` | Walk-in speed, m/s (default 1.4) |
| `--ef simStartM 2000` | Walk-in start distance, m |
| `--ef simBearing 225` | Direction the walker comes from, degrees from the site |
| `--ez syncCamera false` | Pose the overlay with the newest sensor sample instead of the camera frame's exposure time (A/B) |
| `--ef headingTau 2.0` | Compass smoothing time constant, seconds |
| `--es fieldTest t2` | Start a guided field test (`t1` to `t6`, see below) |

Logs: `adb logcat -s Lotus LotusProbe LotusCamera LotusRenderer LotusAudio LotusLocation`.

## In the field

The same gestures as the web build: triple-tap the top-right corner for test
mode, triple-tap the bottom-right corner for the HUD, tap ♪ for earphone mode.
**Export Session** in the HUD opens the share sheet with the session JSON (same
format as the web export, plus latency samples). A copy is also saved to
`Android/data/dev.alfieprojects.mirroredlotus/files/sessions/`.

## Field tests on the phone, no laptop needed

Long-press the build label under the splash (or the HUD text) for the
**Field tests** menu. Each test puts an instruction card on screen, times
its own phases, buzzes at each change (a tick 3 s before a timed phase ends),
and ends with a PASS/FAIL card. Every run writes a log, one JSON object per
line, flushed every 5 s: `Download/LotusTests/<yyyyMMdd-HHmmss>_<id>.json` on
Android 10+ (visible in Files; `adb pull /sdcard/Download/LotusTests/`), or
`Android/data/dev.alfieprojects.mirroredlotus/files/LotusTests/` below that.
Hold "hold to abort" to stop a run.

| Test | Asks | Pass |
|---|---|---|
| T1 Face-up placement | Lay it face-up pointing at something, lift it and face that thing; 3 rounds | \|Δ\| < 15° each round |
| T2 Stand still | 45 s each: hold steady, pan slowly, handle normally, feet still | ≤ 2 steps per part |
| T3 Step count | 20 steps phone held up, then 20 at your side | 20 ± 2 each |
| T4 Swim A/B | Pan briskly twice, blind to which trial is camera-synced; pick the steadier | picked the synced one |
| T5 Thermal soak | The full show for 20 minutes | photon ≤ 100 ms 90% of the time; logs time to MODERATE/SEVERE |
| T6 Compass steadiness | Hold on one spot for 60 s | informational: heading, compass and correction spread |

The logic (phases, poses, scoring, log lines) is in `core/fieldtest/` with its
tests; the app only draws the card and writes the file.

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
  track/        PositionTracker, StepDetector, SwingGate, WalkHeading
  perf/         ThermalPolicy (the degrade ladder)
  fieldtest/    FieldTests (T1-T6), FieldTestRunner, FieldTestLog
  ui/ record/ telemetry/ sim/ geo/ math/
app/    Android: Camera2, sensors, GLES renderer, AudioTrack, location, Views
  gl/ArRenderer.kt        one GL pass: camera + monument + fireworks
  camera/CameraFeed.kt    Camera2 -> SurfaceTexture, REALTIME timestamps
  sensors/OrientationSource.kt   200 Hz rotation vectors -> heading fusion -> history
  fieldtest/              instruction card, MediaStore log writer
  perf/ThermalMonitor.kt  thermal status and headroom
  MainActivity.kt         wiring, splash, HUD, taps, export
```
