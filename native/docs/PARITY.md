# Parity with the web build

Every behaviour of `public/` (index.html, js/*.js) and `SensorBridgePlugin`,
where it lives now, and each deliberate difference. "Same" means the same
constants and formulas, ported line for line and covered by a `:core` test.

## Trigger and location

| Web | Native | Notes |
|---|---|---|
| `SITES`, `nearestSite()` | `site/Sites.kt` | Same coordinates, anchors and marquee lines. |
| Trigger radius 1800 m, nearest site wins, locked for the session | `TriggerPolicy`, `MainActivity.startExperience` | Same. |
| Native FLP 1 Hz high accuracy, web geolocation fallback | `DeviceLocationFeed` | FLP 1 Hz as before. The fallback is now the platform GPS provider, used when Play services are missing. |
| Distance hint: 10 m steps under 1 km, 0.1 km above | `DistanceHint` | Same, including JS number printing ("2km", not "2.0km"). |
| Permission denied, unavailable, and timeout messages | `strings.xml`, `LocationProblem` | Same copy. **New:** "Location is off" when location services are disabled. The web build showed "Step outside for clearer sky" in that case. |
| Geo re-anchoring on every fix, lerp 0.02 per tick | `ExperienceDirector.onLocation/tick`, `PositionTracker` | Same lerp. **Changed:** after the trigger, the anchor comes from GPS position and Doppler velocity, fused with footstep dead reckoning indoors (accuracy-weighted, jump-gated), instead of each raw fix. Indoor GPS wander no longer drags the monument, and it stays put or follows your steps when GPS drops out. |
| `CHECKPOINTS` in the HUD | `Checkpoints` | Same. |

## Scene

| Web | Native | Notes |
|---|---|---|
| Group 15 m up, X = east, Z = south | `SceneGeo`, `ExperienceDirector` | Same axes. |
| Crown: cylinder r 35, h 12, 64 segments, y 41, spin -0.002 per tick | `SceneLayout`, `Meshes.cylinder`, `MonumentPlacement.crown` | Same geometry and UVs (Three r128 layout). |
| Marquee texture: 144 px, 3 copies, 300 px gap, power-of-two width | `TextSpecs.marquee`, `TextTextures` | Same proportions. The texture is capped at 4096 px with glyphs pre-squeezed horizontally, so it uses 8 MB instead of 16 MB with no visible change. |
| Beacon: r 8, h 2000, colour (0.85, 0.95, 1.0), max opacity 0.7 | same | Same. |
| Four stat pylons: Years, Months, Hours, Days; stacked glyphs; 50 m tall | `AnniversaryStats`, `TextSpecs.pylon`, `MonumentPlacement.pylon` | Same maths, including the DL-001 months fix and the 10:00 anchor for Hours. |
| Ground ring 12 to 24 m, breathing opacity 0.025 ± 0.015 | same | Same, on story time. |
| Personal message: arms at ≤ 100 m, appears 60 s later, fades to 0.65, spins +0.003 | `ExperienceDirector.tickMessage` | Same. |
| Act envelopes (beacon, fireworks, marquee, stats) and lerps | `Acts`, `ExperienceDirector.tick` | Same, including the web build's marquee and stats overshoot inside 150 m and 40 m (see `Acts` KDoc). |
| Illumination pulse from bursts within 150 m, scaled by act opacity | `FireworkSystem`, director | Same. |
| Additive blending, depthWrite off, double-sided | `mesh.frag` / `lines.frag`, blend `ONE, ONE`, `BackFaces` | Additive blending and WebGL's alpha × opacity clamping are reproduced. **Changed (Alfie's call, 25 Sep):** text no longer shows through mirrored. From outside, the crown and message cylinders draw only their near side, and pylons only their front. Once the wearer is inside a cylinder, its wall reads the right way round (back faces drawn with the texture mirrored), so walking into the crown still works. The beacon and ground ring stay double-sided. |
| Camera fades in over 2 s, starting 1 s after the trigger | `ExperienceDirector.cameraFade` | Same timing (smoothstep instead of CSS ease-in-out). |
| Camera FOV fixed at 60 degrees vertical | `CameraProjection.fit` | **Changed:** computed from the real lens, so the overlay stays locked while panning. |
| Far plane 4000 m | `ArRenderer.FAR` | Same. |

## Fireworks

| Web | Native | Notes |
|---|---|---|
| Spawner: p 0.1, or p 0.02 for a pair; cap 15 | director | Same. |
| Rocket physics, wind (0.015, 0, -0.005), burst when vy ≤ 1 | `FireworkSystem` | Same, including the order of operations. |
| Six shells, weights 10/8/8 % and 74 % split three ways, counts, colours, curves | `FireworkSystem` | Same formulas (heart, lemniscate, Lissajous 3:2). |
| Streak lengths, gravity, drag, lifespans, opacity flicker | same | Same. |
| `LineSegments`, 1 px at min(dpr, 2) | `GL_LINES`, width = density / 2 | Same apparent thickness. |

## Audio

| Web | Native | Notes |
|---|---|---|
| Thump, body, sizzle, crackle; frequencies and gains per shell | `ExplosionVoice` | Same constants. |
| Soft-clip `WaveShaper` curve | `SoftClip` | Same 256-point curve and interpolation. This includes its -0.169 offset at 0, a quirk of the web curve that is kept. |
| `PannerNode` HRTF, inverse distance, ref 30 | `SpatialMixer` | **Approximated:** Web Audio's `equalpower` law plus a head-shadow low-pass, with the same distance model. Real HRTF is too heavy for a phone audio thread. |
| Master `DynamicsCompressor` (-6 dB, knee 3, 4:1, 1 ms, 100 ms) | `Compressor` | Same parameters. No automatic make-up gain. |
| Earphone mode: 2 s convolution reverb at 0.28, lower thump | `Reverb`, `EARPHONE_WET` | Freeverb with similar energy instead of 96k-tap convolution. Same gain and thump table. |
| Earphone auto-detect by device label | `EarphoneDetector` | Uses the audio route types (wired, A2DP, USB, BLE, hearing aid) instead of guessing from device names. |
| ♪ / 🎧 toggle, opacity 0.55 / 0.6 | `audio_mode` view | Same. |

## Controls and diagnostics

| Web | Native | Notes |
|---|---|---|
| Triple-tap top-right 100 px, 600 ms window, 60 ms tick, [40, 60, 120] confirm | `MultiTapDetector`, `Haptics` | Same. |
| Test mode: group at (0, 15, -40) | director `start(testMode)`, `PoseGate` | **Changed:** 40 m along the camera's current heading, not always north. The README said "straight ahead", and only facing north matched that. The start waits (up to 2 s) for a compass-corrected heading, because a launch-intent start runs before any sensor sample arrives. |
| Camera failure copy, "tap three times to skip" | `showCameraFailure`, `onSecretTap` | Same copy. The triple-tap now actually skips (hides the splash). In the web build it did nothing once the experience had started. |
| Compass banner: calibrating, locked or active, 8 s bound | `CompassBanner` | Same states and timings. **New:** "needs calibration (figure-8)" when the compass reports itself unreliable. |
| Debug HUD, triple-tap bottom-right 80 px | `HudFormatter`, `debug_hud` | Same lines, plus fps, camera fps, photon age, pose mode and lag, heading source, FOV. The HUD sits above the toggle's hit area. In the web build the toggle covered the Export link. |
| Session export (share, else clipboard, else blob) | `SessionRecorder`, `SessionExporter` | Same JSON keys, plus `app` and `perfSamples`. Exported as a real file via the share sheet, with a copy under `Android/data/<pkg>/files/sessions/` for `adb pull`. |
| Keep screen on, portrait, fullscreen | `MainActivity` | Same, and the view extends under the display cutout. |
| Build version on splash | `BUILD_STAMP`, versionCode = build minute | Same idea. Every build upgrades without editing files. |

## Not ported

- `public/debug.html`: the HUD and `LotusProbe` logcat lines cover it.
- `public/boids-app/`, `public/emitter-app/`: unrelated apps.
- `ROLLBACK.md` procedure: the native app has its own package
  (`dev.alfieprojects.mirroredlotus`), so both builds install side by side and
  each is the other's rollback.
