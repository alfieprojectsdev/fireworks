# Handover

The current state and the next action. Written at the end of the cloud session
that built the port (25 September 2026) for the local session with the laptop
and phones.

## State in one table

| | State |
|---|---|
| `:core` (story, fireworks, pose maths, camera maths, audio DSP) | **Done, 138 JVM tests passing** |
| GLSL shaders | **Validated** with glslang 15 (`ShaderSourcesTest`) |
| `:app` debug APK | **Builds** (AGP 8.7.3, compileSdk 35), lint has no errors |
| Instrumented tests (`DeviceSmokeTest`) | **Compile**. Not run yet: they need a phone |
| On a real phone | **Not yet run.** The cloud box has no GPU, camera or KVM |

Everything that could be verified without hardware has been. The rest is the
local session's job, in the order below.

## Do this first (local session, Windows laptop `admin@REESE`)

**1. Toolchain.** Same as stable-still: `JAVA_HOME` on **Temurin 21** (Android
Studio's JBR 25 can't run Gradle 8.14). SDK platform 35 and build-tools 34 are
already installed on the laptop. Android Studio writes `native/local.properties`
when it opens the `native/` folder. Otherwise, create it with
`sdk.dir=C\:\\Users\\admin\\AppData\\Local\\Android\\Sdk`.

```powershell
cd fireworks\native
.\gradlew.bat :core:test          # 138 tests, ~10 s
.\gradlew.bat :app:assembleDebug
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

**3. Run the on-device checks.** These cover what the JVM can't: shaders
compiling on the Mali GPU, sensor rates, camera facts, and audio render time.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
adb logcat -d -s LotusSmoke
```

**4. Install, probe, and look.** `adb shell input tap` does nothing on the A07,
so everything is driven by launch switches:

```powershell
.\scripts\deploy.ps1 --ez testMode true --ez hud true
adb logcat -d -s LotusProbe Lotus LotusCamera
```

Paste the `LotusProbe` lines into this file (camera timestamp source, sensor
names and rates, refresh rate). Then do the same on the **A56**.

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
