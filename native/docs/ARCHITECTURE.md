# Architecture

How the native build removes the latency the WebView build had, and how the
pieces fit. For what each web feature became, see `PARITY.md`; for what to do
next, `HANDOVER.md`.

## Where the web build's lag came from

Every stage between the phone moving and the picture changing had its own
queue, and the camera and the overlay went through *different* ones:

```
WebView build
  rotation vector ─► Java ─► JSON ─► Capacitor bridge ─► JS event loop ─► Three.js ─► WebGL canvas ┐
                   50 Hz, delta-gated        (tens of ms, jittery)                                  ├─► compositor ─► screen
  camera ─► getUserMedia ─► Chromium media pipeline ─► <video> element ────────────────────────────┘
```

Two things made it *feel* bad rather than merely late:

1. **The overlay and the picture moved at different times.** The video layer
   and the WebGL layer were composited separately, each with its own delay, so
   whenever the phone turned the monument slid across the church and snapped
   back ("swim"). The eye forgives a whole scene being late far more readily
   than two layers disagreeing.
2. **The virtual camera did not match the real one.** A fixed 60 degree
   vertical field of view for every phone meant the overlay rotated at a
   different rate from the image - indistinguishable, in the field, from lag.

There was also a frame bug. The native path wrote Android's rotation-vector
quaternion (east-north-up world) straight into the Three.js camera (east-up-
south world) without the -90 degree X rotation that `DeviceOrientationControls`
applies internally, which aims the camera at the sky when the phone is held
upright. The web-only fallback path never showed it; the native path only
started running after the `registerPlugin` fix. `FramesTest` documents it.

## The native pipeline

```
orientation thread (URGENT_DISPLAY)
  GAME_ROTATION_VECTOR 200 Hz ─┐
  ROTATION_VECTOR 50 Hz ───────┴─► HeadingFusion ─► OrientationHistory (timestamped ring)
                                                           │
camera thread                                              │  sampled at the frame's
  Camera2 ─► SurfaceTexture (OES) ── REALTIME timestamps ──┤  mid-exposure instant
                                                           ▼
render thread (GLSurfaceView)                         PoseSampler
  latch newest camera frame ─► pose @ exposure ─► ExperienceDirector.tick() x N (fixed 60 Hz)
      ─► one GL pass: camera quad, then monument + fireworks (additive) ─► screen
                              │
                              └─ bursts ─► TriggerQueue (lock-free) ─► audio thread
audio thread (URGENT_AUDIO)                                            SpatialMixer ─► AudioTrack (low latency, float)
main thread
  GPS (fused, 1 Hz) ─► TriggerPolicy / director.onLocation ; splash, HUD, taps, export
```

### 1. One pass, one pose: the camera-synchronised overlay

The camera picture and the monument are drawn in the same GL pass, from the
same pose, for the same instant. `ArRenderer.latchCamera()` latches the newest
camera buffer and computes when it was exposed (`CameraTiming`): the frame's
sensor timestamp plus half the exposure plus half the rolling-shutter readout
(27 ms on the A07). `PoseSampler` slerps the orientation *at that instant*
out of `OrientationHistory`. The monument is therefore posed exactly as the
world was when the photons in the picture were captured, and the two cannot
drift apart while the phone moves.

This works because the camera and sensors share a clock: the A07 reports
`REALTIME` (stable-still `DEVICE-A07.md`), and on API 33+ the session asks for
`TIMESTAMP_BASE_REALTIME` outright. On an older device reporting `UNKNOWN`,
timestamps are converted from `CLOCK_MONOTONIC`, and any timestamp that lands
outside a plausible window is refused - the renderer then falls back to the
newest pose rather than syncing to a wrong clock.

`--ez syncCamera false` switches to "newest pose" for an A/B comparison in the
field. That mode has lower latency for the overlay alone, but the overlay then
leads the picture during a pan.

### 2. Orientation without a bridge

`OrientationSource` registers the sensors on its own high-priority thread and
writes straight into `OrientationHistory` - a preallocated ring the render and
audio threads read under a lock held for a few array copies. No IPC, no JSON,
no event loop. It runs at 200 Hz, the ceiling Android 12+ allows without the
`HIGH_SAMPLING_RATE_SENSORS` permission.

`HeadingFusion` addresses compass jitter (the web research doc's Path 1).
`GAME_ROTATION_VECTOR` (gyro + accelerometer) is smooth but its yaw drifts;
`ROTATION_VECTOR` adds the magnetometer, so it points north but jitters near
steel and concrete. Their difference is a rotation about the vertical, so the
fusion low-passes that yaw offset (time constant 2 s, `--ef headingTau`) and
applies it to every game sample. The output has the gyro's smoothness and the
compass's north. `HeadingFusionTest` checks that 6 degrees of compass jitter
comes out under 1 degree RMS.

### 3. A virtual lens that matches the real one

`CameraProjection.fit()` builds the projection from the lens's focal length
and the sensor size, following the three crops between sensor and screen:
the HAL's crop to the stream's aspect ratio, the rotation to portrait, and the
cover-fit crop to the screen. The same computation sets the camera quad's
texture window, so the picture and the projection crop identically. On the
A07 this gives a 66.5 degree vertical field, matching the probe's measurement.

Electronic stabilisation is turned off (`CONTROL_VIDEO_STABILIZATION_MODE_OFF`).
EIS crops and re-warps each frame, which would change the field of view the
projection assumes and add a frame of delay.

### 4. Fixed-step story, rate-independent rendering

`ExperienceDirector` is everything `_animate()` did per frame: anchor glide,
act fades, the illumination pulse, the spawner, and the timed message. It is
advanced in fixed 60 Hz ticks from an accumulator, so it plays at the same
speed on the A56's 120 Hz panel, the A07's 60 Hz one, and in a JUnit test.
The render surface asks for 60 Hz (`Surface.setFrameRate`), since 120 Hz frames
would only repeat the 60 Hz story and the 30 fps camera while costing battery.

### 5. Fireworks with no per-burst allocation

`FireworkSystem` keeps every rocket and spark in preallocated
structure-of-arrays slots and writes one interleaved line buffer each frame:
one upload (orphaned `GL_STREAM_DRAW`) and one draw call. The web build
allocated a geometry, a material and hundreds of vectors per burst, which gave
the GC work to do.

### 6. Audio

`SpatialMixer` synthesises every burst with the same layers and constants as
`audio.js` (thump, body, sizzle, crackle, soft-clip curve, compressor), but
as streaming voices from a fixed pool on a dedicated audio thread, into a
low-latency float `AudioTrack`. Bursts reach it through a wait-free
single-producer single-consumer queue from the render thread. Spatialisation
uses Web Audio's own `equalpower` law and `inverse` distance model on the
burst's position relative to the current phone orientation, plus a head-shadow
low-pass on the far ear. Earphone mode adds a small Freeverb in place of the
2 s convolver.

## Threads and ownership

| Thread | Owns | Talks to others via |
|---|---|---|
| main | GPS feed, trigger decision, Views, recorder writes | `glView.queueEvent` into render; volatile reads of `RenderTelemetry` |
| orientation | `HeadingFusion` | `OrientationHistory` (locked ring) |
| camera | Camera2 session | `SurfaceTexture` buffers; volatile exposure and skew |
| render | `ExperienceDirector`, `FireworkSystem`, all GL | `TriggerQueue` to audio; `ui.post` for act events |
| audio | `SpatialMixer` voices | reads `OrientationHistory` once per buffer |

`ExperienceDirector` is confined to the render thread. Nothing reaches into it
except through `queueEvent`.

## Module rule

Like stable-still, the build has two modules. **Anything that decides where a
pixel goes, when an act starts, or what a sample sounds like belongs in
`:core`, with a test.** `:core` is plain Kotlin with no Android imports, so its
tests run on any JVM in seconds. `:app` holds only what needs hardware:
Camera2, SensorManager, GLES, AudioTrack and Views.

The GLSL lives in `:core` resources for the same reason: `ShaderSourcesTest`
runs every shader through `glslangValidator` when it is installed, and checks
that every uniform the renderer binds is declared.
