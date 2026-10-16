# Handover

The current state and the next action. Written by the cloud session that built
the port (25 September 2026), updated with the laptop session's first A07 results.

## State in one table

| | State |
|---|---|
| `:core` (story, fireworks, pose maths, camera maths, audio DSP, tracking, thermal ladder, field tests) | **Done, 190 JVM tests: 189 pass, 1 skipped without glslang** |
| GLSL shaders | **Validated** with glslang 15, and **compiled on the A07's Mali-G57** |
| `:app` | **Builds** (AGP 8.7.3, compileSdk 35), lint has no errors. **Field tests use the `field` build** (see below) |
| `DeviceSmokeTest` on the A07 | **4/4 pass** on the field build: three runs on `4e59787` and one on `fbc7c77`, which includes the back-face shader (`gl_FrontFacing`, `discard`, `uBackFaces`), compiled and linked on the Mali-G57 with `GL_NO_ERROR` |
| On the A07, field build, test mode | **60 fps render, 30 fps camera, 58 ms photon age.** Camera upright, vfov 66.5°. Crown and pylons render and track |
| Digits-only pylons + date greeting (16 Oct) | **Built; checked on the A07** with a fixed simulated position east of the origin (`--es simulate origin --ef simStartM 250 --ef simSpeed 0.001 --ef simBearing 90`), phone held upright. "252" + sideways "MONTHS" render; just legible at 250 m, clear at 150 m, whole number in frame at 60 m. Crown reads "Happy Anniversary, Bhaze!" in October. The far leg shows through behind the digits (legs are double-sided and additive). Still to do: a drive along Maginhawa |
| Next on the A07 | Install the new build, run `connectedFieldAndroidTest` (now 5 tests, incl. the field-log writer), then the on-phone field tests: T2, T5 and T1 first (see "Field tests on the phone") |
| Still to do | Heading check, walk-in (including inside the church, watching the HUD `track:` line), back-face check on the phone, side-by-side with the web build, the A56 |

## Always install the `field` build

A debuggable APK runs under ART's debug mode. On the A07 the same code gave:

| Build | Render | Camera | Photon age | Pose lag |
|---|---|---|---|---|
| `debug` (debuggable) | 31.5 fps | 14.6 fps | 155 ms | 146 ms |
| `field` (not debuggable) | 58.6 fps | 29.7 fps | 58 ms | 53 ms |

`field` is `debug` with `isDebuggable = false`. It has the same app id and the
same committed signing key (`native/debug.keystore`), so either build replaces
the other. The deploy and serve scripts install `field`. Set
`LOTUS_VARIANT=debug` only when you need a debugger. Instrumented tests also
run against `field` by default (`-Plotus.testBuildType=debug` for the other).

The committed key means builds from the laptop, the cloud and any clone can
all update each other. **One-time step:** the copy on the A07 was signed with
the laptop's own key, so uninstall it once before the first install from this
commit.

## Do this first (local session, Windows laptop `admin@REESE`)

**1. Toolchain.** Same as stable-still: `JAVA_HOME` on **Temurin 21** (Android
Studio's JBR 25 can't run Gradle 8.14). SDK platform 35 and build-tools 34 are
already installed on the laptop. Android Studio writes `native/local.properties`
when it opens the `native/` folder. Otherwise, create it with
`sdk.dir=C\:\\Users\\admin\\AppData\\Local\\Android\\Sdk`.

```powershell
cd fireworks\native
.\gradlew.bat :core:test          # 184 tests, ~10 s
.\gradlew.bat :app:assembleField
```

**2. Connect the A07.** It is already paired with this laptop. Its wireless
debugging screen showed `100.79.189.46`, a Tailscale address, which stable-still
found to be the most reliable route to the phone. The **port changes every time
wireless debugging is switched on**, so read it off the phone each time:

```powershell
adb connect 100.79.189.46:<port from the Wireless debugging screen>
adb devices
```

`adb` isn't on PATH on this laptop. It lives at
`C:\Users\admin\AppData\Local\Android\Sdk\platform-tools\adb.exe`. The
deploy scripts find it through `sdk.dir` or `ANDROID_HOME`; for manual commands,
add that folder to PATH or call it by full path.

Wireless adb snags the laptop session hit:

- **Paired but untrusted.** The phone listed `admin@REESE` under paired
  devices, yet the connection failed with `SSLV3_ALERT_CERTIFICATE_UNKNOWN` in
  `%TEMP%\adb.log`. Fix: pair again with a pairing code
  (`adb pair <ip>:<pairing port>`).
- **The phone appears twice.** After pairing, mDNS adds a second transport
  for the same phone (`adb-<serial>._adb-tls-connect._tcp`), and Gradle then runs
  every instrumented test twice. Set `ANDROID_SERIAL` to one of them, or
  `adb disconnect` the mDNS entry.
- **Secure Folder.** The A07 has a Secure Folder user (150). `pm list packages`
  without `--user 0` errors on it, and adb can't uninstall from it: an app copy
  there has to be removed by hand on the phone.

**3. Run the on-device checks.** These cover what the JVM can't: shaders
compiling on the Mali GPU, sensor rates, camera facts, and audio render time.

```powershell
.\gradlew.bat :app:connectedFieldAndroidTest
adb logcat -d -s LotusSmoke
```

**4. Install, probe, and look.** `adb shell input tap` does nothing on the A07,
so everything is driven by launch switches:

```powershell
.\scripts\deploy.ps1 --ez testMode true --ez hud true
adb logcat -d -s LotusProbe Lotus LotusCamera
```

Paste the `LotusProbe` lines below. Then do the same on the **A56**.

### Galaxy A07 5G (SM-A076B), 25 September 2026

```
device samsung SM-A076B (a07x) Android 16 API 36
display refresh 120.0 Hz (modes: 120, 90, 60)
sensor android.sensor.game_rotation_vector: game_rotvec / mtk minDelay=5000us fifo=4500
sensor android.sensor.rotation_vector: rot_vec / mtk minDelay=5000us fifo=4500
sensor android.sensor.gyroscope: bmi3xy gyro / bosch minDelay=5000us fifo=4500
sensor android.sensor.magnetic_field: qmc6308 / qst minDelay=20000us fifo=4500
camera 0 back: ts=REALTIME focal=[3.98]mm sensor=5.22x3.93mm orientation=90 ois=[0] fps=[[10, 10], [15, 15], [15, 20], [20, 20], [24, 24], [5, 30], [30, 30]]
app: camera cam 0 1280x720 @30-30 f=3.98mm ts=REALTIME
smoke: orientation game+mag at 201.3 Hz; GL Mali-G57 MC2 / OpenGL ES 3.2 v1.r38p1
```

Every assumption the design rests on held:
- the camera clock is REALTIME
- a fixed [30, 30] fps range is offered
- 200 Hz rotation vectors are delivered
- the SurfaceTexture transform includes the 90 degree sensor rotation (the picture is upright and not mirrored)
- the lens-derived 66.5 degree field matches

**Audio budget.** 15 voices (the spawner's ceiling), one second of audio:

| Build | Cold | Warm |
|---|---|---|
| debuggable, before `4e59787` | 830-4650 ms | not measured |
| non-debuggable, before `4e59787` | 173-301 ms | not measured |
| field, `4e59787` and `fbc7c77` (4 runs) | 89-159 ms | **28-36 ms** |

Warm is about 3% of real time: roughly 30x headroom on the A07, and about 1.3x
the JVM figure (22 ms). The cold second is mostly ART warm-up, and the first
run after an install is the slowest. The app's audio thread is warm long
before the first burst.

**Mirrored glyphs: decided.** A test-mode screenshot showed the crown's far
side and a pylon's back reading mirrored, because every material is double-sided
additive with no depth test (as in the web build). Alfie chose to show only the
side facing the viewer. From outside, back faces of text are now discarded. From
inside a text cylinder (standing within the crown's 35 m, or the message's
12 m), its wall draws mirrored so it reads correctly; otherwise walking in would
make the crown vanish. `BackFacesTest` pins the winding this relies on. **On the
phone:** in test mode, circle a pylon (no mirrored text from any side), then
confirm the crown still reads from inside it with `--es simulate church`.

**Test mode from a face-up phone: fixed.** Alfie's first session export showed
a desk launch placing the monument along the 2 degree tilt of a phone lying
face-up, which is a random bearing. Placement (and each dead-reckoned step) now
uses `WalkHeading`. When the camera is within about 60 degrees of vertical, it
takes the screen's top edge: forward for a face-up phone, reversed for one
tilted up at the sky.

**Indoor position.** From the trigger on, the monument is anchored from
`PositionTracker`, not raw fixes. Outdoors it moves with the fix's Doppler
velocity; indoors, where GPS has none, footsteps take over. fireworks-local's
review of the first version (lag of ~9x walking speed with steps off, a
reject/reset sawtooth at vehicle speed, phantom steps from panning) is fixed and
covered by tests at 1.4, 5, 12 and 20 m/s.

The HUD's `track:` line shows the fused distance, its uncertainty, what is
moving the estimate (`by gps 1.4m/s` or `by steps`), the step count and rejected
fixes. **On the phone:**

1. **Standing still at a site.** A non-test launch at home triggers `origin`.
   Hold the phone still for a minute: `by gps 0.0m/s`, the fused distance
   steady, the monument not drifting.
2. **Standing and watching.** Pan up and round after the bursts: the step count
   should not climb. It is gated on rhythm (a lone jolt isn't a step) and on the
   phone turning faster than 1.5 rad/s.
3. **The walk-in.** Outdoors `by gps`; through the door it should switch to
   `by steps` within about 2.5 s. Inside, standing still, the monument should
   not drift even though raw GPS wanders.

Stride is fixed at 0.7 m. If indoor walks consistently over- or undershoot,
that's the constant to tune (`PositionTracker(strideM)`). A step goes where the
camera faces, not where your feet go: walking in while filming off to one side
dead-reckons sideways. Keep the camera roughly ahead while walking indoors.

**Thermal throttling: a ladder, measured next.** fireworks-local's A07 run
reached thermal status SEVERE (skin 51 °C) after 25 minutes of handling in
Manila daytime: 34 fps and a 289 ms photon age, against 60 fps and 62 ms cool.
The walk-in from the 1.8 km trigger is 20+ minutes, so `ThermalPolicy` now
gives ground first. At MODERATE (or a 10 s forecast headroom of 0.85) it
renders once per camera frame and caps fireworks at 9. At SEVERE it also
renders at 75% size with 6 fireworks, and at CRITICAL 50% with 4. Camera-paced
rendering costs nothing in lock, since the pose is synced to the frame anyway.
Before the trigger the render loop now idles behind the splash. The HUD has a
`thermal:` line, and every perfSample and field-test perf line records status,
headroom and rung. **T5** measures the result.

## Field tests on the phone

Alfie can run these alone, indoors, with no laptop: long-press the build
label under the splash (or the HUD text) and pick one, or
`adb shell am start -n dev.alfieprojects.mirroredlotus/.MainActivity --es fieldTest t2`.
The README lists all six. Each run writes
`Download/LotusTests/<yyyyMMdd-HHmmss>_<id>.json`, one JSON object per line.
Every line has `type` and `t` (ms since the run started):

| `type` | Carries |
|---|---|
| `run` | test id, app version, variant, device, launch options, the phase list |
| `phase` | each phase start, its text and actions (`placeAhead`, `startShow`, `syncCamera:on/off`) |
| `ori` | 10 Hz: device-to-ENU quaternion `q` `[w,x,y,z]`, walking `heading`, `rate` (rad/s), raw `compass` heading, fusion `correction` |
| `step` | every credited footstep, with the tracker's state |
| `fix` | every GPS fix (incl. Doppler speed/bearing), the tracker's verdict (`FUSED`, `REJECTED_JUMP`...) and state |
| `perf` | 1 Hz: fps, camFps, photonMs, poseLagMs, sensorHz, thermal, headroom, quality |
| `input`, `note` | taps, choices, aborts, pauses, thermal-ladder changes |
| `result` | every check with value, threshold and PASS/FAIL/info |

The order that answers the open questions: **T2** (phantom steps; the 70
"steps" in the last run were on the old detector, before the cadence and swing
gates), **T5** (thermal, with the ladder), **T1** (face-up placement, with
numbers). Then T3 and T4. T4 is blind: the run picks at random which trial is
camera-synced and only the log and the result card say which.

## What to look at first on the phone, and what to try if it's wrong

These are the parts that could not be exercised off-device. Each has a known
first thing to try.

| Symptom | Most likely cause | First fix to try |
|---|---|---|
| Test-mode monument not ahead of the camera | Compass heading wasn't ready within the 2 s wait (`PoseGate`), so it fell back to north | Check the HUD `heading:` line. If it isn't `game+mag`, the compass hasn't reported. |
| Camera picture sideways or mirrored | The SurfaceTexture transform didn't include the sensor rotation on this HAL | Rotate `uv` by the sensor orientation in `camera.vert` before applying `uTexMatrix`. Log `st` once to confirm. |
| Black screen, HUD says `cam: 0.0` | Camera session failed | `adb logcat -s LotusCamera Lotus`. The failure reason is logged. |
| Overlay swims against the church while panning | Pose not synced (HUD shows `pose: latest`) or a wrong timestamp base | Check the HUD `pose:` line and `LotusProbe ts=`. Compare with `--ez syncCamera false`. |
| Monument is the right way round but turns faster or slower than the world | Field of view is off | The HUD prints `vfov`. Compare it with the camera's real field. Intrinsics come from `CameraFeed.intrinsicsOf`. |
| Heading wanders or is slow to settle | Heading fusion time constant | `--ef headingTau 1.0` (faster) or `4.0` (steadier). The HUD shows the source and `±accuracy`. |
| No sound | AudioTrack low-latency path refused | `adb logcat -s LotusAudio`. |
| Text too dim or too bright compared with the web build | Premultiplied-alpha handling in `mesh.frag` | Put the phones side by side in test mode and compare. |

## Field test: A/B against the web build

Both apps install side by side ("Mirrored Lotus" is the web build, "Lotus
Native" is this one), so every comparison can be done in the same spot and the
same minute. Do it at night, as the experience requires.

1. **Swim test (the main complaint).** Test mode in each app. Pan left and
   right at a brisk walk-turn pace. In the native app, the monument should stay
   locked to whatever real object it covers. Repeat the native app with
   `--ez syncCamera false` to feel what camera sync is worth.
2. **Latency numbers.** The HUD shows `photon` (camera frame age when drawn,
   typically 50 to 100 ms on phone cameras, and the same for the overlay since
   they're synced) and `lag` (how far the synced pose trails the newest
   sample). Export a session: `perfSamples` records these every 5 s.
3. **Heading.** Stand where the church is visible. The crown should sit over
   it. `HeadingFusionTest` says compass jitter should be smoothed to under
   1 degree RMS; check that the monument no longer shivers.
4. **Walk-in.** From beyond 1.8 km, or with synth-loc, which drives the fused
   provider this app reads. Watch each act fire, then export the session and
   check `actEvents[].deltaM`. The acts are designed to fire a few metres
   inside their thresholds (see `ExperienceDirectorTest`).
5. **Desk rehearsal without walking.**
   `--es simulate church --ef simSpeed 20 --ez hud true` walks a virtual
   wearer in from 2 km at 20 m/s, so every act plays in under two minutes.

Worth knowing before judging the fireworks: the portrait camera field is only
about 33 degrees wide, and rockets launch across ±43 degrees around the
monument, so part of the show is always off to the side. The web build had
the same field (a 60 degree vertical FOV on a tall screen is about 30 degrees
wide).

## Decisions already made

Don't reopen these without a reason. The reasoning is in `ARCHITECTURE.md`.

- **Kotlin + GLES 3 + Camera2, no WebView, no game engine, no NDK.** The
  latency lived in the bridge and the compositor, not in JS speed. Plain
  Kotlin on the JVM keeps `:core` testable in seconds.
- **Camera-synchronised pose by default.** Drawing the overlay late with the
  picture beats drawing it early against the picture.
- **Game rotation vector + compass yaw correction**, not the raw rotation
  vector.
- **Separate package** (`dev.alfieprojects.mirroredlotus`), so the web build
  stays installed as the rollback.
- **Portrait lock.** The pose maths assumes the device frame is the camera frame.

## Found in the web build along the way

- The native orientation path wrote the ENU quaternion into the Three.js camera
  without the ENU-to-scene rotation. With the phone upright, the virtual camera
  looks at the zenith. It stayed hidden until the `registerPlugin` fix made the
  path run. The one-line fix in `sensors.js` is to premultiply by a -90 degree
  X rotation, which is what `DeviceOrientationControls` does. Worth doing if
  the web build stays in use for A/B.
- The HUD toggle's 80 px hit area covers the Export Session link.
- "Tap the upper-right corner three times to skip" did nothing once the
  experience had started.

## Environment the cloud session used

JDK 21, Gradle 8.14.3 (wrapper), Android SDK platform 35 and build-tools 34/35
(installed with `sdkmanager`), glslang 15 for shader validation. Maven Central
rate-limited the first dependency fetch (HTTP 429). A retry got through.
