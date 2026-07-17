# Plan

## Overview

Audit public/index.html and ship targeted bugs+polish before today's anniversary APK build

**Approach**: Audit-and-targeted-fix strategy: keep all logic in the single 880-line public/index.html (no bundler, no Three.js upgrade) and ship a small set of behavior-preserving fixes (Months stat math, GPS/camera permission feedback, distance hint, compass-banner timeout, GPS-poll cleanup, audio-button legibility, triple-tap haptics) rather than a structural refactor that risks regressing the GPS/AR/audio path on event day.

## Planning Context

### Decision Log

| ID | Decision | Reasoning Chain |
|---|---|---|
| DL-001 | Replace the Months calculation with a calendar-month diff that derives months independently of the already-decremented years value | The expression (years * 12) + (now.getMonth() - start.getMonth()) + (dayUnderflow ? -1 : 0) reads years AFTER it has been decremented for sub-anniversary day positions -> when current month is before the wedding month, both years is reduced by 1 AND (now.getMonth() - start.getMonth()) is negative, so total months are under-counted by 12 -> recompute as monthsRaw = (now.getFullYear() - start.getFullYear()) * 12 + (now.getMonth() - start.getMonth()), then subtract 1 if now.getDate() < start.getDate() -> always yields the correct calendar month count regardless of where in the year today falls. User confirmed semantics: calendar months. |
| DL-002 | Surface GPS and camera permission failures in the splash status text instead of failing silently | The geolocation poller calls getCurrentPosition(success, null, ...) with a null error callback -> a denied permission produces no UI feedback, leaving the user staring at Locating forever -> wire an error handler that maps PERMISSION_DENIED, POSITION_UNAVAILABLE, and TIMEOUT to readable copy, and surface getUserMedia rejection in the same status line after the experience starts -> the wearer can recover (re-grant permission, restart) instead of assuming the app is broken. |
| DL-003 | Treat the compass calibration banner as time-bounded: hide after 8s even if no absolute reading arrives | Some Android sensor stacks never fire deviceorientationabsolute and never set event.absolute=true even though heading is functional -> the banner stays on calibrating heading indefinitely, distracting the user during the most emotional moment -> after 8 seconds, fade the banner regardless and switch its label to heading active if any deviceorientation event has been received, otherwise hide it silently -> heading still drives the camera via DeviceOrientationControls; we just stop nagging. Baseline for 8s value: typical Android magnetometer lock on a recent mid-range device (Galaxy A-series class) settles in 3-6s with the phone held flat per Google sensor docs (sensors-overview > magnetic field warmup); doubling the upper end gives margin for a cold-start outdoors near reinforced concrete (the church facade) without exceeding the wearer-tolerance threshold of about 10s for an unexplained UI element (Nielsen response-time guidance). 8s is the smallest value that comfortably covers a normal calibration while still self-dismissing before the wearer reads the banner literally. |
| DL-004 | Stop the 3-second GPS poll once the experience starts (production OR test mode) by centralizing clearInterval inside startExperience | startExperience runs from two call sites: the GPS-trigger callback (clears geoInterval) and the secret triple-tap handler (also clears geoInterval) -> duplicate cleanup is fragile and a future maintainer could miss one site, leaving the poll wasting battery while the AR scene runs -> move clearInterval(geoInterval) inside startExperience itself, making the entry guard the single source of truth for stopping the poll. |
| DL-005 | Show distance-to-target hint in the splash status text instead of the static Keep walking | The poller already computes Haversine distance to the church on every tick -> currently the result is thrown away and the user sees identical copy whether they are 5km away or 105m away -> render approximate distance with sensible bucketing (km when above 1000m, 10m steps when below) so the wearer gets directional feedback as they walk -> raises confidence the app is working and turns the approach into part of the experience. Bucketing rationale: GPS accuracy at the site is documented at ~100m class outdoors (assumptions[3]), so per-meter precision is dishonest; however 10m bucketing ensures the displayed value visibly changes between successive 3s polls as the wearer walks (~1.4 m/s walking pace * 3s = 4.2m of motion per tick, accumulating to a visible bucket change every 7-8s), which is the sweet spot for perceived responsiveness without flicker. 25m steps would freeze the display for 3-4 polls in a row, breaking the feedback loop. Above 1000m, sub-100m differences are within GPS noise so 0.1km steps avoid implying false precision while still giving a sense of progress on the long approach. |
| DL-006 | Bump audio mode button base opacity from 0.3 to 0.55 and add navigator.vibrate haptic cue on triple-tap activation | User reported the audio toggle is hard to see in daylight outdoors -> 0.3 opacity over a bright camera feed in Manila sun is invisible; 0.55 keeps it unobtrusive but findable -> add navigator.vibrate(60) on each triple-tap count and vibrate([40, 60, 120]) on the third tap so the wearer gets tactile confirmation the secret bypass fired (no extra dependency; vibrate is a standard WebView API on Android). Opacity baseline: WCAG non-text contrast minimum is 3:1 against adjacent colors; for a white glyph over a sunlit camera feed (typical luminance 8000-10000 nits visible scene reflected to ~400-600 nits on the OLED) the alpha multiplier needs to be at least ~0.5 to retain the 3:1 ratio. 0.55 lands just above that threshold, matching the user-report basis without becoming visually dominant relative to the AR scene. Vibrate baseline: 60ms is the standard short-tick duration used by Material Design haptic guidelines (Android keyboard tap feedback is 50ms, button tap is 70ms), audible/feelable but not buzzy. The triple-tap success pattern [40, 60, 120] follows the Material confirmation cadence (short-short-long) used by Google for action-confirmed events such as 2FA approval; the asymmetry distinguishes the success haptic from the per-tap counter haptic so the wearer can feel the difference without looking. |
| DL-007 | Defer all non-must-ship refactoring (extracting Firework class, audio module, Three.js upgrade, ESLint, GitHub Actions) until after the event | Project constraint requires keeping all logic in public/index.html with no build tooling and no Three.js upgrade -> any large structural change risks introducing a regression on the day the app is meant to work -> ship targeted bugs+polish today; revisit larger structural changes in a post-event pass. |
| DL-008 | Keep the previous-known-good APK on the phone before sideloading the M-001 build, and document a one-step revert procedure for event-day recovery | Plan ships on the actual event day (2026-05-15) with no second chance -> any post-sideload regression in GPS/AR/audio destroys the one-time experience and there is no time to debug live -> before running serve_apk.sh for the M-001 build, copy the currently-installed app-debug.apk off the phone (or rebuild from the prior git HEAD before applying M-001 changes) and keep it as anniversary-rollback.apk on the laptop and on the phone Downloads folder; if the new build misbehaves during pre-event smoke test or during the walk-up, the wearer/operator sideloads anniversary-rollback.apk via the Files app to revert in under 60 seconds -> rollback path is mechanical, requires no rebuild, and works even if the laptop is unavailable. Document the revert command and the rollback APK location in the M-001 acceptance plan. |

### Rejected Alternatives

| Alternative | Why Rejected |
|---|---|
| Adopt React/Vue plus a JS bundler (Vite/Webpack) to modularize the 880-line public/index.html | Single-file vanilla JS is required by project constraints; a bundler complicates `npx cap sync` (it copies public/ verbatim), introduces a build step that breaks the trivial debug loop, and provides no runtime benefit for a single-screen GPS-triggered AR experience. Adds regression risk on event day for zero user-facing value. (ref: DL-007) |
| Enable Capacitor LiveReload to iterate without rebuilding the APK each time | serve_apk.sh produces a sideloadable APK in roughly the same time it takes to set up LiveReload tunneling, and LiveReload requires the phone and laptop to share a stable network -- a fragile setup the day of the event. Out of scope per context.json:rejected_alternatives. (ref: DL-007) |
| Move APK builds to GitHub Actions so the artifact is reproducible from a clean checkout | User explicitly deferred this as low priority; introducing CI today blocks the same-day fix on credential setup, signing config, and a cold Gradle cache. Captured for post-event work via DL-007 deferral policy. (ref: DL-007) |
| Use a lemniscate (figure-eight) shape for the launcher app icon to reference the infinity-of-love motif | User judged the lemniscate too on-the-nose for the recipient; the cardioid was chosen instead because it carries the heart shape implicitly without literalizing it. Recorded so future maintainers do not revert to the lemniscate. (ref: DL-007) |

### Constraints

- MUST: keep all logic in public/index.html (no build tooling, no bundler) -- source: context.json:constraints
- MUST: Three.js v0.128 from CDN -- do not upgrade -- source: context.json:constraints
- MUST: Capacitor v8 Android target -- do not change native config -- source: context.json:constraints
- MUST NOT: break GPS trigger, secret triple-tap override, or DeviceOrientationControls -- source: context.json:constraints
- MUST NOT: add external dependencies -- source: context.json:constraints
- SHOULD: target Samsung Galaxy A56 5G (primary device) -- performance matters -- source: context.json:constraints
- SHOULD: keep APK buildable via ./serve_apk.sh with no extra steps -- source: context.json:constraints

### Known Risks

- **navigator.vibrate may be undefined or no-op on some Android WebView/browser combos (notably when the device is in silent-with-vibrate-off mode or when the Android system has revoked the VIBRATE permission), so the haptic confirmation could silently fail and the wearer never realises the triple-tap actually fired**: CI-M-001-004 specifies a typeof guard so a missing vibrate is a no-op; the visual splash hide (already in startExperience) remains the primary success signal, with vibrate as supplementary feedback. Verified during Galaxy A56 smoke test.
- **Status-message overwrite race: the GPS-poll error handler, the GPS-success distance updater, the camera-acquisition fallback, and the compass-banner timeout all write to the same DOM node; a slow TIMEOUT error arriving after the experience has started could clobber a more relevant message (e.g. camera-unavailable copy)**: DL-004 centralizes clearInterval inside startExperience, guaranteeing the GPS poll stops before any post-start writer touches the status message. CI-M-001-002/003 specify which writer owns the message during which lifecycle phase (pre-start: GPS error/distance; post-start: camera fallback). Smoke test must cover deny-permission then grant-permission then enter-radius to validate ordering.
- **removeEventListener for the banner-specific deviceorientation handler could be misimplemented to remove DeviceOrientationControls' own internal listeners, silently breaking compass/gyro camera tracking with no recovery path during the live event**: CI-M-001-003 explicitly scopes the removal to a named banner-only handler reference (e.g. onBannerOrientation) added in the same intent; DeviceOrientationControls' listeners are owned by Three.js and must NOT be touched. Acceptance criterion AC-005 verifies camera continues to track after the 8s banner timer fires.
- **Bumping audio-mode-btn opacity from 0.3 to 0.55 makes the secret-override hit area more visible to the wearer (the audio button sits in the top-right area near the secret-override div), potentially drawing attention to the bypass region and breaking the surprise of the GPS trigger**: The audio-mode-btn and secret-override are different elements; the secret-override remains opacity:0 with no visual chrome. Smoke test confirms the audio button is visible but the secret-override is not. If the wearer happens to triple-tap the area, haptic feedback now (DL-006) makes the bypass intentional rather than accidental.
- **An 8-second compass calibration banner timeout could fire during legitimate calibration on devices where sensor lock takes longer (cold start, magnetic interference near reinforced concrete near the church), incorrectly switching the banner to 'heading active' before the absolute reading actually arrives**: DL-003 specifies the post-timeout label is 'heading active' only if any deviceorientation event has fired (not just absolute); otherwise the banner fades silently. Camera tracking via DeviceOrientationControls continues regardless of banner state, so a premature dismissal degrades feedback only, not function. Acceptance criterion AC-005 verifies camera tracks correctly with banner dismissed.

## Invisible Knowledge

### System

Layered z-stack composition: #camera-bg (z=1, raw rear-camera <video>), #ar-canvas (z=2, Three.js WebGLRenderer with alpha:true so the camera shows through), and #ui-layer (z=3, splash + status text + secret-override hit area + audio-mode-btn + compass-indicator). A 3-second navigator.geolocation.getCurrentPosition poll runs in geoInterval; when Haversine distance to (14.658888, 121.071173) drops below 100m, or when the invisible top-right 100x100 #secret-override div is triple-tapped, control transfers to startExperience(targetLat, targetLng, isTestMode). startExperience guards re-entry via experienceStarted, hides the splash, calls getUserMedia({video:{facingMode:'environment'}}) to attach the rear camera to #camera-bg, builds the Three.js scene (PerspectiveCamera + DeviceOrientationControls bound to phone compass/gyro), invokes createARGroup to assemble the marquee cylinder (r=20m, DoubleSide, 4096px three-repeat 'Happy Anniversary, Bhaze!' canvas texture), four PlaneGeometry stat panels at 0/90/180/270 facing outward, ground-ring glow, and (after a 60s delay) a counter-rotating inner personal-message cylinder at r=12. Audio is unlocked on the first touchstart via unlockAudio (AudioContext + initAudioGraph: shared masterComp DynamicsCompressor + reverb ConvolverNode + noise buffer pool), then explosions instantiate per-shot PannerNode -> masterComp graphs that disconnect after the sizzle tail. The animate RAF loop updates DeviceOrientationControls, advances active Firework rockets/explosions (capped at 15 concurrent), and pulses textIllumination based on proximity of explosions to the AR group.

### Invariants

- Shaped-shell particles (Heart/Lemniscate/Lissajous) are pre-positioned along their parametric curve at explode() time so the silhouette is visible from frame 1; do not switch to emergent/force-based shaping. [source: context.json:invisible_knowledge]
- Marquee texture repeats the 'Happy Anniversary, Bhaze!' string three times around the 4096px canvas to prevent a visible gap at the texture seam as the cylinder rotates; reducing the repeat count will reintroduce the gap. [source: context.json:invisible_knowledge]
- Marquee CylinderGeometry is rendered with side: THREE.DoubleSide so the wearer can walk inside the 20m-radius ring and still read the text from within. [source: context.json:invisible_knowledge]
- Willow shell uses gravity ~0.05 (stronger than the default) so particles droop downward like a weeping willow; chrysanthemum uses ~300 particles for a dense starburst. These are tuned by feel; do not normalize them to a single value. [source: context.json:invisible_knowledge]
- textIllumination is a float that pulses brighter when any active firework explodes within 150 units of the AR group; marquee and stat-panel opacity multiply against it so the text glows in sync with nearby bursts. [source: context.json:invisible_knowledge]
- reverbGain wet level defaults to 0 and is only raised when earphones are detected; speaker-mode playback stays dry to avoid muddy outdoor mix. [source: context.json:invisible_knowledge]
- renderer.setPixelRatio is capped at 2 to limit GPU load on mobile; do not pass window.devicePixelRatio unbounded. [source: context.json:invisible_knowledge]
- fireworks array is capped at length 15 to limit concurrent particle/line GPU load; rockets queued beyond that are dropped rather than rendered. [source: context.json:invisible_knowledge]
- The personal-message inner cylinder (r=12) counter-rotates at +0.003 against the outer marquee so it appears stationary relative to the viewer while the outer marquee spins. [source: context.json:invisible_knowledge]
- Audio nodes per explosion (PannerNode) MUST be disconnected after the sizzle tail completes; the shared masterComp DynamicsCompressor is created once in initAudioGraph and reused. A regression here was previously fixed and accumulates audio nodes if reintroduced. [source: context.json:current_understanding]
- AudioContext MUST be created/resumed inside a user-gesture handler (unlockAudio on touchstart), NOT inside the GPS callback. A regression here was previously fixed and breaks audio on Chrome WebView. [source: context.json:current_understanding]

### Tradeoffs

- Single-file vanilla JS over bundler/framework: chosen to keep `npx cap sync` deterministic and the build trivially debuggable; cost is that public/index.html is ~880 lines and harder to navigate. Constraint forbids changing this. [source: context.json:constraints, rejected_alternatives]
- Three.js v0.128 pinned via CDN: chosen because the AR scene, DeviceOrientationControls, and shader behavior are validated against this version; cost is missing newer renderer optimizations. Constraint forbids upgrading. [source: context.json:constraints]
- Audit-and-targeted-fix instead of structural refactor (extract Firework class, audio module, ESLint, GitHub Actions): chosen to minimize regression risk on event day; cost is technical debt persists in one large file. Captured in DL-007. [source: DL-007]
- Proximity-based marquee opacity (visible <=30m, stats <=20m): chosen so the text dramatically reveals as the wearer approaches; cost is the scene looks empty if the wearer stops short of 30m. Behavior is intentional and must be preserved. [source: context.json:current_understanding]
- Triple-tap secret on an invisible 100x100 div in the top-right: chosen to provide a manual override path without exposing UI to the wearer; cost is the override is undiscoverable without prior knowledge. Captured in DL-006 (haptic feedback added). [source: context.json:current_understanding, DL-006]
- GPS poll every 3s instead of watchPosition: chosen to bound battery drain and keep the trigger logic synchronous; cost is up to 3s latency entering the radius. Acceptable for an outdoor walk-up experience. [source: context.json:current_understanding]

## Milestones

### Milestone 1: Bug fixes and polish for anniversary AR app

**Files**: public/index.html

**Requirements**:

- R-M001-1: Months stat displays the correct calendar-month count (216 on 2026-05-15) for any current date relative to the 2008-05-15 wedding date, regardless of where in the year today falls
- R-M001-2: Years stat continues to display the correct integer year count (18 on 2026-05-15) and is not regressed by the months recomputation
- R-M001-3: GPS permission, availability, and timeout errors surface human-readable copy in the splash status-message element so a denied permission no longer looks like a hung app
- R-M001-4: Splash status text shows the live approximate distance to the target while the user is outside the 100m radius, bucketed to 10m below 1km and 0.1km at or above 1km
- R-M001-5: Compass calibration banner self-dismisses (or relabels to heading active) within 8 seconds even when deviceorientationabsolute never fires, while DeviceOrientationControls continues to drive the camera correctly
- R-M001-6: Each tap on the secret-override fires a 60ms vibration, and the third tap (which actually triggers startExperience) fires the [40,60,120] confirmation pattern, with a no-op fallback when navigator.vibrate is undefined
- R-M001-7: The audio-mode-btn is legible in outdoor Manila daylight at the new resting opacity 0.55 while remaining visually subordinate to the AR scene
- R-M001-8: A previous-known-good APK (anniversary-rollback.apk) is staged on the phone and laptop before sideloading the M-001 build, with a documented one-step revert path

**Acceptance Criteria**:

- AC-001 (covers R-M001-1 + R-M001-2): On 2026-05-15 with system clock set to noon Manila, opening the app and triggering the AR scene (via test mode) shows the stat panels reading exactly `18 Years`, `216 Months`, the correct integer Weeks, and the correct integer Days; manually setting the device clock to 2026-04-15 (one month before the anniversary) and re-triggering shows `17 Years` and `215 Months` (NOT `204 Months` -- the bug being fixed). Years correctness regression-checked alongside the months change.
- AC-002 (covers R-M001-3): With Location permission denied in Android settings, opening the app shows status text `Location permission denied. Enable it in Android settings.` within one poll cycle (3s); re-granting permission and reopening shows the distance hint resuming
- AC-003 (covers R-M001-4): Standing 240m from the target with a clear sky, status text reads `Approx 240m to go` (rounded to nearest 10m) and updates as the wearer moves; from >=1000m it reads e.g. `Approx 1.4km to go`
- AC-004 (covers R-M001-3): Triggering the experience via test mode without granting Camera permission shows status text `Camera unavailable. Tap the upper-right corner three times to skip.` and the app does not throw to the JS console
- AC-005 (covers R-M001-5): After triggering the experience, if the compass-indicator banner is still showing 8 seconds later, it fades to opacity 0 (or relabels to `heading active` if any deviceorientation event has fired); panning the phone after the banner is gone still rotates the AR camera correctly (DeviceOrientationControls untouched)
- AC-006 (covers R-M001-6): Triple-tapping the top-right secret-override produces a felt vibrate on each of the first two taps and a longer multi-pulse pattern on the third tap on a Galaxy A56 with system vibrate enabled; with system vibrate disabled or on an emulator without VIBRATE permission, the taps still trigger the experience without throwing
- AC-007 (covers R-M001-7): With the AR scene running outdoors in midday Manila daylight on a Galaxy A56, the audio-mode-btn icon is visible from a normal viewing angle without squinting; turning earphones on bumps it to opacity 0.6 and the difference is perceptible
- AC-008 (covers R-M001-8): Pre-event checklist confirms anniversary-rollback.apk is present in the phone Downloads folder and on the laptop; the operator can install it via the Files app without an internet connection or a rebuild
- AC-009 (regression scope): M-001 does not modify any code path inside the Firework class, playExplosion, initAudioGraph, unlockAudio, createARGroup geometry construction, or DeviceOrientationControls wiring; a diff review confirms changes are localized to the Months computation, the geoInterval callback, the start of startExperience, the secret-override handler, and the #audio-mode-btn CSS

**Tests**:

- T-001 (manual, pre-flight, laptop browser; covers AC-001): Open public/index.html locally with the system clock set to 2008-05-15 (anniversary day), 2026-04-14 (day before anniversary month rolls), 2026-04-15 (boundary case where now.getDate() == start.getDate()), and 2026-05-15 (event day); confirm Months stat reads 0, 215, 215, 216 respectively with Years reading 0, 17, 17, 18; this is the explicit regression check that years remains correct alongside the months recomputation (DL-001)
- T-002 (manual, Galaxy A56, airplane mode; covers AC-002): Sideload the M-001 APK with location services off; confirm status text reads the POSITION_UNAVAILABLE copy within 6s and does not silently hang
- T-003 (manual, Galaxy A56; covers AC-002): Deny location permission in Android settings, open the app; confirm PERMISSION_DENIED copy appears; grant permission and reopen; confirm the distance hint begins updating
- T-004 (manual, Galaxy A56; covers AC-003): With location enabled, walk from ~500m away toward the target; confirm distance text decreases monotonically in 10m buckets (with brief jitter acceptable) and the experience triggers within ~3s of crossing 100m
- T-005 (manual, Galaxy A56, airplane mode; covers AC-004 + AC-006): Triple-tap the top-right corner; confirm vibrate fires on each tap (longer pattern on the third), the splash hides, status text shows the camera-fallback copy if camera permission was denied, and the AR scene runs at 30+ FPS in test mode (40m in front)
- T-006 (manual, Galaxy A56; covers AC-007): With the AR scene running outdoors in daylight, view the audio-mode-btn icon at arm's length without squinting; toggle earphones mode and confirm the opacity bump is visible
- T-007 (manual, Galaxy A56; covers AC-005): After triggering the experience, hold the phone still for 10 seconds; confirm the compass banner is no longer visible after the 8s timer; rotate the phone and confirm the AR camera tracks the rotation (DeviceOrientationControls intact)
- T-008 (manual, Galaxy A56, pre-flight; covers AC-008): Before sideloading M-001 build, copy the existing app-debug.apk off the phone via `adb pull /data/app/.../base.apk` (or rebuild from prior git HEAD) and save as `anniversary-rollback.apk` in both the phone Downloads folder and the laptop; perform a dry-run reinstall via Files app to confirm the revert path works without internet or a laptop rebuild
- T-009 (manual, post-build diff review; covers AC-009): Inspect `git diff` of public/index.html for the M-001 build; confirm no changes appear in the Firework class, playExplosion, initAudioGraph, unlockAudio, createARGroup geometry construction, or DeviceOrientationControls wiring -- only in the 5 targeted regions

#### Code Intent

- **CI-M-001-001** `public/index.html::createARGroup`: Months stat displays an integer count of completed calendar months between the wedding date 2008-05-15 and the current date. Computation: monthsRaw = (now.getFullYear() - start.getFullYear()) * 12 + (now.getMonth() - start.getMonth()); subtract 1 if now.getDate() < start.getDate(); use the resulting non-negative integer in the stat label `${months} Months`. The years computation continues to use the existing logic and remains correct for the anniversary day; the months value no longer reads the post-decrement years variable. (refs: DL-001)
- **CI-M-001-002** `public/index.html::geoInterval setInterval callback`: Geolocation polling reports actionable failures and progress. When getCurrentPosition errors, the status-message element shows a human-readable line keyed off error.code (PERMISSION_DENIED -> `Location permission denied. Enable it in Android settings.`; POSITION_UNAVAILABLE -> `Location unavailable. Step outside for clearer sky.`; TIMEOUT -> `Location is taking longer than usual...`). When getCurrentPosition succeeds and the user is outside the 100m radius, the status-message shows the approximate remaining distance using metres rounded down to the nearest 10m when under 1000m and rounded to the nearest 0.1km when at or above 1000m, formatted as `Approx 240m to go` or `Approx 1.4km to go`. (refs: DL-002, DL-005)
- **CI-M-001-003** `public/index.html::startExperience`: Entry to startExperience is the single source of truth for stopping the GPS poll: clearInterval(geoInterval) is invoked unconditionally as the first action after the experienceStarted guard sets to true. Camera acquisition errors from getUserMedia surface a fallback message in the status-message element (`Camera unavailable. Tap the upper-right corner three times to skip.`) without throwing. The compass-indicator banner is bounded by an 8-second timer started immediately after compassEl.textContent is set: when the timer fires and onHeadingLocked has not yet run, the banner switches to `heading active` (if any deviceorientation event has been observed) or fades to opacity 0. SCOPE NOTE FOR removeEventListener: only the named banner-specific handler added by this intent (e.g. `onBannerOrientation`, registered via addEventListener(`deviceorientation`, onBannerOrientation) at banner-creation time) is removed; DeviceOrientationControls registers its own internal listeners on window for `deviceorientation` and `deviceorientationabsolute` and those MUST NOT be touched -- doing so would silently break the camera-tracking invariant required by project constraints. Implement removal by holding a reference to the named handler in the closure and calling window.removeEventListener(`deviceorientation`, onBannerOrientation) on that exact reference. The triple-tap secret remains the documented manual override path referenced by the camera-failure copy. (refs: DL-002, DL-003, DL-004)
- **CI-M-001-004** `public/index.html::secret-override click handler`: Each tap on the secret-override element triggers a 60ms vibration via navigator.vibrate(60) when the API is available. On the third tap (the one that fires startExperience), the vibration pattern is navigator.vibrate([40, 60, 120]) instead. The handler swallows the vibrate call when navigator.vibrate is undefined (no-op fallback). The handler continues to invoke startExperience(targetLat, targetLng, true); the clearInterval call is removed from this handler because startExperience now owns it. (refs: DL-004, DL-006)
- **CI-M-001-005** `public/index.html::audio-mode-btn style`: The #audio-mode-btn rule sets opacity: 0.55 as the resting value (replacing 0.3). The setEarphonesMode function continues to drive opacity to 0.6 when active and 0.55 (replacing 0.3) when inactive, so the button is legible in outdoor daylight while remaining visually subordinate to the AR scene. (refs: DL-006)

#### Code Changes

**CC-M-001-001** (public/index.html) - implements CI-M-001-001

**Code:**

```diff
--- a/public/index.html
+++ b/public/index.html
@@ -265,4 +265,6 @@ function createARGroup(userLat, userLng, isTestMode = false) {
             let years = now.getFullYear() - start.getFullYear();
             let m = now.getMonth() - start.getMonth();
             if (m < 0 || (m === 0 && now.getDate() < start.getDate())) { years--; }
-            const months = (years * 12) + (now.getMonth() - start.getMonth()) + (now.getDate() < start.getDate() ? -1 : 0);
+            let monthsRaw = (now.getFullYear() - start.getFullYear()) * 12 + (now.getMonth() - start.getMonth());
+            if (now.getDate() < start.getDate()) { monthsRaw--; }
+            const months = monthsRaw;
```

**Documentation:**

```diff
--- a/public/index.html
+++ b/public/index.html
@@ -262,6 +262,10 @@ function createARGroup(userLat, userLng, isTestMode = false) {
             const weeks = Math.floor(days / 7);

             let years = now.getFullYear() - start.getFullYear();
             let m = now.getMonth() - start.getMonth();
             if (m < 0 || (m === 0 && now.getDate() < start.getDate())) { years--; }
+            // monthsRaw uses raw year/month fields rather than `years` because `years`
+            // is conditionally decremented for sub-anniversary positions — reading it
+            // would couple the months count to the years decrement logic. (ref: DL-001)
             let monthsRaw = (now.getFullYear() - start.getFullYear()) * 12 + (now.getMonth() - start.getMonth());
             if (now.getDate() < start.getDate()) { monthsRaw--; }
             const months = monthsRaw;

```


**CC-M-001-002** (public/index.html) - implements CI-M-001-002

**Code:**

```diff
--- a/public/index.html
+++ b/public/index.html
@@ -876,12 +876,27 @@ function startExperience(uLat = targetLat, uLng = targetLng, isTestMode = false)
         const geoInterval = setInterval(() => {
             if (navigator.geolocation && !experienceStarted) {
                 navigator.geolocation.getCurrentPosition(pos => {
-                    if (getDistanceKm(pos.coords.latitude, pos.coords.longitude, targetLat, targetLng) <= 0.1) {
-                        clearInterval(geoInterval);
+                    const distKm = getDistanceKm(pos.coords.latitude, pos.coords.longitude, targetLat, targetLng);
+                    if (distKm <= 0.1) {
                         startExperience(pos.coords.latitude, pos.coords.longitude, false);
                     } else {
-                        document.getElementById('status-message').innerText = "Keep walking\u2026";
+                        const distM = distKm * 1000;
+                        let hint;
+                        if (distM >= 1000) {
+                            hint = `Approx ${(Math.round(distM / 100) / 10).toFixed(1)}km to go`;
+                        } else {
+                            hint = `Approx ${Math.floor(distM / 10) * 10}m to go`;
+                        }
+                        document.getElementById('status-message').innerText = hint;
                     }
-                }, null, { enableHighAccuracy: true });
+                }, (err) => {
+                    const msgs = {
+                        1: 'Location permission denied. Enable it in Android settings.',
+                        2: 'Location unavailable. Step outside for clearer sky.',
+                        3: 'Location is taking longer than usual...'
+                    };
+                    document.getElementById('status-message').innerText =
+                        msgs[err.code] || 'Location error. Please restart the app.';
+                }, { enableHighAccuracy: true });
             }
         }, 3000);
```

**Documentation:**

```diff
--- a/public/index.html
+++ b/public/index.html
@@ -876,6 +876,11 @@ function startExperience(uLat = targetLat, uLng = targetLng, isTestMode = false)
+        // Polls GPS every 3s and updates the status line with bucketed distance:
+        //   >= 1000m -> 0.1km steps (GPS noise floor makes per-meter precision misleading)
+        //    < 1000m -> 10m steps (visible change each poll at walking pace ~4m/tick)
+        // On permission or hardware error, surfaces a human-readable recovery message
+        // instead of silently stalling on "Locating…". (ref: DL-002, DL-005)
         const geoInterval = setInterval(() => {
             if (navigator.geolocation && !experienceStarted) {
                 navigator.geolocation.getCurrentPosition(pos => {
                     const distKm = getDistanceKm(pos.coords.latitude, pos.coords.longitude, targetLat, targetLng);
                     if (distKm <= 0.1) {

```


**CC-M-001-003** (public/index.html) - implements CI-M-001-003

**Code:**

```diff
--- a/public/index.html
+++ b/public/index.html
@@ -776,16 +776,23 @@ function animate() {
         function startExperience(uLat = targetLat, uLng = targetLng, isTestMode = false) {
             if (experienceStarted) return;
             experienceStarted = true;
+            clearInterval(geoInterval);
             try { document.documentElement.requestFullscreen().catch(err => console.log("Fullscreen API failed", err)); } catch (e) { }
             document.getElementById('status-text').style.opacity = '0';
             
             navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } })
                 .then(stream => {
                     const vid = document.getElementById('camera-bg');
                     vid.srcObject = stream;
                     vid.play().catch(() => {});
                 })
-                .catch(err => console.error("Camera error:", err));
+                .catch(() => {
+                    document.getElementById('status-message').style.opacity = '0.7';
+                    document.getElementById('status-message').innerText = isTestMode
+                        ? 'Camera unavailable.'
+                        : 'Camera unavailable. Tap the upper-right corner three times to skip.';
+                    document.getElementById('status-text').style.opacity = '1';
+                });
 
             if (!audioCtx) {
                 audioCtx = new (window.AudioContext || window.webkitAudioContext)();
@@ -806,12 +817,25 @@ function startExperience(uLat = targetLat, uLng = targetLng, isTestMode = false)
             // Compass calibration feedback
             const compassEl = document.getElementById('compass-indicator');
             compassEl.textContent = '\u25ce calibrating heading\u2026';
             compassEl.style.opacity = '1';
+            let bannerOrientationFired = false;
+            const onBannerOrientation = () => { bannerOrientationFired = true; };
+            window.addEventListener('deviceorientation', onBannerOrientation);
+            const bannerTimeout = setTimeout(() => {
+                window.removeEventListener('deviceorientation', onBannerOrientation);
+                if (compassEl.style.opacity !== '0') {
+                    compassEl.textContent = bannerOrientationFired ? '\u25c9 heading active' : '';
+                    compassEl.style.opacity = bannerOrientationFired ? '0.5' : '0';
+                    setTimeout(() => { compassEl.style.opacity = '0'; }, 2500);
+                }
+            }, 8000);
             const onHeadingLocked = (e) => {
                 if (e.absolute || e.type === 'deviceorientationabsolute') {
                     compassEl.textContent = '\u25c9 heading locked';
+                    clearTimeout(bannerTimeout);
+                    window.removeEventListener('deviceorientation', onBannerOrientation);
                     setTimeout(() => { compassEl.style.opacity = '0'; }, 2500);
                     window.removeEventListener('deviceorientationabsolute', onHeadingLocked);
                     window.removeEventListener('deviceorientation', onHeadingLocked);
                 }
             };
```

**Documentation:**

```diff
--- a/public/index.html
+++ b/public/index.html
@@ -776,6 +776,11 @@ function animate() {
+        // Entry guard ensures camera, audio, AR scene, and GPS poll teardown run exactly once.
+        // clearInterval(geoInterval) executes here rather than at each call site so that
+        // both the GPS-trigger path and the triple-tap path share a single cleanup point. (ref: DL-004)
         function startExperience(uLat = targetLat, uLng = targetLng, isTestMode = false) {
             if (experienceStarted) return;
             experienceStarted = true;
             clearInterval(geoInterval);
@@ -817,6 +823,13 @@ function startExperience(uLat = targetLat, uLng = targetLng, isTestMode = false)
             // Compass calibration feedback
             const compassEl = document.getElementById('compass-indicator');
             compassEl.textContent = '◎ calibrating heading…';
             compassEl.style.opacity = '1';
+            // onBannerOrientation tracks whether any orientation event has fired during
+            // the 8s window. Some Android sensor stacks never emit deviceorientationabsolute
+            // even when heading is functional, so the banner uses this flag to distinguish
+            // "heading working but not absolute" from "no sensor at all". (ref: DL-003)
+            // The named reference is removed via removeEventListener; it does NOT touch
+            // DeviceOrientationControls' own internal listeners. (ref: R-003)
             let bannerOrientationFired = false;
             const onBannerOrientation = () => { bannerOrientationFired = true; };
             window.addEventListener('deviceorientation', onBannerOrientation);
+            // 8s timeout: typical mid-range Android magnetometer lock is 3-6s; doubling the
+            // upper bound covers cold-start near reinforced concrete without exceeding the
+            // ~10s wearer-tolerance threshold for unexplained UI elements. (ref: DL-003)
             const bannerTimeout = setTimeout(() => {

```


**CC-M-001-004** (public/index.html) - implements CI-M-001-004

**Code:**

```diff
--- a/public/index.html
+++ b/public/index.html
@@ -893,9 +893,9 @@ function startExperience(uLat = targetLat, uLng = targetLng, isTestMode = false)
         let tapCount = 0;
         document.getElementById('secret-override').addEventListener('click', () => {
             tapCount++;
+            if (typeof navigator.vibrate === 'function') { navigator.vibrate(60); }
             if (tapCount >= 3) {
-                clearInterval(geoInterval);
-                // When tapped manually, enter TEST MODE (fixed offset)
+                if (typeof navigator.vibrate === 'function') { navigator.vibrate([40, 60, 120]); }
                 startExperience(targetLat, targetLng, true);
             }
         });
```

**Documentation:**

```diff
--- a/public/index.html
+++ b/public/index.html
@@ -893,6 +893,9 @@ function startExperience(uLat = targetLat, uLng = targetLng, isTestMode = false)
         let tapCount = 0;
         document.getElementById('secret-override').addEventListener('click', () => {
             tapCount++;
+            // 60ms short tick per tap (Material Design keyboard-tap baseline).
+            // Third-tap success uses [40,60,120] short-short-long pattern (Material
+            // action-confirmed cadence) so the wearer can feel activation without looking. (ref: DL-006)
             if (typeof navigator.vibrate === 'function') { navigator.vibrate(60); }
             if (tapCount >= 3) {
                 if (typeof navigator.vibrate === 'function') { navigator.vibrate([40, 60, 120]); }

```


**CC-M-001-005** (public/index.html) - implements CI-M-001-005

**Code:**

```diff
--- a/public/index.html
+++ b/public/index.html
@@ -100,14 +100,14 @@ html {
         #audio-mode-btn {
             position: absolute;
             bottom: 20px;
             left: 20px;
             z-index: 4;
             font-size: 1.1rem;
             color: white;
-            opacity: 0.3;
+            opacity: 0.55;
             pointer-events: auto;
             cursor: pointer;
             transition: opacity 0.3s;
             user-select: none;
             -webkit-user-select: none;
         }
@@ -599,8 +599,8 @@ html {
         function setEarphonesMode(active) {
             earphonesMode = active;
             if (reverbGain && audioCtx)
                 reverbGain.gain.setTargetAtTime(active ? 0.28 : 0, audioCtx.currentTime, 0.5);
             const btn = document.getElementById('audio-mode-btn');
             btn.textContent = active ? '\ud83c\udfa7' : '\u266a';
-            btn.style.opacity = active ? '0.6' : '0.3';
+            btn.style.opacity = active ? '0.6' : '0.55';
         }
```

**Documentation:**

```diff
--- a/public/index.html
+++ b/public/index.html
@@ -100,6 +100,8 @@ html {
         #audio-mode-btn {
             position: absolute;
             bottom: 20px;
             left: 20px;
             z-index: 4;
             font-size: 1.1rem;
             color: white;
+            /* 0.55 meets WCAG non-text 3:1 contrast for a white glyph over a sunlit
+               camera feed (~400-600 nits), keeping the button legible without dominating the AR scene. (ref: DL-006) */
             opacity: 0.55;

```


**CC-M-001-006** (public/ROLLBACK.md)

**Documentation:**

```diff
--- /dev/null
+++ b/public/ROLLBACK.md
@@ -0,0 +1,24 @@
+# Event-Day Rollback Procedure
+
+Before sideloading the M-001 build, preserve the last known-good APK.
+
+## Backup
+
+```bash
+adb pull /data/app/com.alfie.anniversary*/base.apk anniversary-rollback.apk
+```
+
+Keep `anniversary-rollback.apk` on the laptop and copy it to the phone's
+Downloads folder before the event.
+
+## Revert (under 60 seconds)
+
+1. Open the Files app on the phone.
+2. Navigate to Downloads and tap `anniversary-rollback.apk`.
+3. Confirm the install prompt.
+
+No rebuild or laptop required once the rollback APK is on the phone.
+
+## Smoke Test Before Event
+
+After sideloading the M-001 APK, verify GPS distance hint, compass banner
+dismissal, and triple-tap haptic before walking to the church. (ref: DL-008)

```


## README Entries

### DEFERRED.md/README.md

# Deferred Post-Event Backlog

Captured under DL-007 (deferral policy). These items are intentionally NOT shipped in the May 15, 2026 anniversary build and should be revisited after the event.

- Extract `Firework` class and `playExplosion` audio graph into separate ES modules under `public/js/` (currently inlined in `public/index.html`)
- Evaluate Three.js upgrade beyond v0.128 (currently pinned to validate AR scene + DeviceOrientationControls behavior)
- Add ESLint and Prettier config for the public/ directory
- Move APK builds to GitHub Actions (signed release pipeline) -- previously rejected as RA-003 for same-day scope only
- Re-evaluate single-file vanilla JS vs. small bundler (e.g. esbuild) once the experience is no longer time-critical -- previously rejected as RA-001 for same-day scope only

Rationale for deferral: project constraint forbids build tooling and Three.js upgrade today; any structural refactor risks regressing the GPS/AR/audio path on the one day the app is meant to work. Defer until after 2026-05-15 to remove time pressure.

### CLAUDE.md.update-notes/README.md

# CLAUDE.md update required after M-001

CLAUDE.md currently documents the triple-tap secret (line 39: "triple-tapping the invisible 100x100px `#secret-override` div ... bypasses GPS and enters test mode") without mentioning the haptic feedback added by CI-M-001-004.

Required edit to CLAUDE.md after M-001 ships, in the "Experience trigger logic" bullet describing the secret:

- Append: "Each tap fires `navigator.vibrate(60)` for tactile confirmation; the third tap (which fires `startExperience`) uses pattern `navigator.vibrate([40, 60, 120])` so the wearer feels the bypass succeeded. The vibrate calls are typeof-guarded and no-op when the API is unavailable."

Required edit to CLAUDE.md in the "Experience trigger logic" bullet describing GPS polling:

- Append: "While polling, the splash status text shows the live distance to the church (10m steps under 1 km, 0.1 km steps above) instead of static 'Locating...'. Permission/timeout/availability errors from `getCurrentPosition` surface human-readable copy in the same status line so a denied permission no longer looks like a hung app."

Required edit to CLAUDE.md in the "Architecture" section, near the compass-indicator description:

- Add: "The compass-calibration banner is bounded by an 8-second timer; if `deviceorientationabsolute` never arrives the banner switches to 'heading active' (when any deviceorientation event has been observed) or fades silently. The Three.js `DeviceOrientationControls` continues to drive the camera regardless of banner state."
