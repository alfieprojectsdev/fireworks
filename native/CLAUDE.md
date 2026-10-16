# CLAUDE.md: native/

Guidance for work inside `native/`, the Kotlin/GLES port of the web experience.
Read `docs/HANDOVER.md` first for the current state and next action.

## Commands

```bash
./gradlew :core:test            # JVM only, no SDK needed - run after every change to core/
./gradlew :app:assembleField    # what goes on phones (non-debuggable); needs an Android SDK
./gradlew :app:lintDebug
./gradlew :app:connectedFieldAndroidTest   # phone attached; tests run against `field`
```

## The rule

**Anything that decides where a pixel goes, when an act fires, or what a sample
sounds like belongs in `:core`, with a test.** `:core` has no Android imports;
keep it that way. `:app` only touches hardware (Camera2, SensorManager, GLES,
AudioTrack, Views) and routes events into `:core`.

## Landmines

- **Frames.** Android rotation vectors are device->ENU (X east, Y north, Z up).
  The scene is X east, Y up, Z south. Always go through `Frames.enuToScene`.
  Skipping it (as the web build's native path did) aims the camera at the sky.
- **Pose timing.** The overlay is posed at the camera frame's mid-exposure
  instant, not "now". Don't "simplify" `PoseSampler` to the newest sample; that
  brings back the swim. Test timing changes with *oscillatory* motion: a
  constant rotation rate hides clock offsets (stable-still lesson).
- **Camera latch.** `ArRenderer` counts pending frames and calls
  `updateTexImage` once per frame. A boolean flag leaves the texture one frame
  behind forever.
- **Render-thread confinement.** `ExperienceDirector` and `FireworkSystem` are
  touched only on the GL thread. From the main thread, use `glView.queueEvent`.
- **No per-frame allocation** on the render, sensor or audio threads. Reuse
  arrays (see `PoseSampler`, `SpatialMixer`).
- **Parity.** Constants and formulas mirror `../public/js/*.js`. Change them
  deliberately and record it in `docs/PARITY.md`. Any function that maps a
  distance through cascading comparisons gets a test at every band edge.
- **Never judge performance on a debuggable build.** ART's debug mode halved
  the A07's frame rate and tripled its latency. Use `field`, which the deploy
  scripts and the instrumented tests already do.
- **Shaders** live in `core/src/main/resources/shaders/`. `ShaderSourcesTest`
  compiles them with glslang when it's installed and checks the uniform names
  `ArRenderer` binds.
- **The render loop idles before the trigger** (`RenderPace.IDLE`) and may be
  camera-paced after it (the thermal ladder). Anything the main thread needs
  continuously, like the HUD's camera heading, must come from
  `OrientationHistory`, not from `RenderTelemetry`, which only moves when a
  frame is drawn.
- **Field tests:** phases, poses, scoring and log lines live in
  `core/fieldtest/` with tests. `:app/fieldtest/` only draws, buzzes and writes.
  A new check is a change to `FieldTests` plus a test, not UI code.
- **The beacon is a thin pillar.** Don't reintroduce light-beam or tractor-beam
  forms (rejected as tacky).

## Conventions

Conventional-ish commit subjects in the imperative; the body explains why.
Comments explain *why*, at the density of the surrounding file.
