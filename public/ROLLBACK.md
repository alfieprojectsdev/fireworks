# Event-Day Rollback Procedure

This file documents how to preserve the previous-known-good APK before sideloading
the M-001 build, and how to revert in under 60 seconds if the new build misbehaves.

## Step 1: Extract the Currently-Installed APK (Before Sideloading M-001)

Run these commands on the laptop with the phone connected via USB and ADB authorized:

```bash
APK_PATH=$(adb shell pm path com.alfie.anniversary | sed -e s/^package://)
adb pull "$APK_PATH" anniversary-rollback.apk
```

This uses `pm path` indirection to resolve the runtime install path. The literal glob form
`adb pull /data/app/com.alfie.anniversary*/base.apk` does NOT work on Android 12+ because:
- ADB pull does not expand shell globs on the device side
- `/data/app` is access-restricted on non-rooted devices
- The actual path contains a randomized suffix (e.g. `~~<hash>==/com.alfie.anniversary-<hash>==/base.apk`)

**Do not use the glob form.** It silently produces no APK and leaves no recovery path.

## Step 2: Stage the Rollback APK in Two Places

1. Keep `anniversary-rollback.apk` on the laptop in the project root.
2. Copy it to the phone Downloads folder:
   ```bash
   adb push anniversary-rollback.apk /sdcard/Download/anniversary-rollback.apk
   ```

## Step 3: Sideload the M-001 Build

```bash
./serve_apk.sh
```

Then download and install the APK from the phone browser as normal.

## Step 4: Pre-Event Smoke Test Checklist

Before handing the phone to the wearer, verify:

- [ ] Open the app; within 3 seconds the status message shows a floor-bucketed distance
      hint (e.g. `Approx 240m to go` if ~244m away, or `Approx 1.4km to go` if ~1440m away)
      or an error message (not a blank `Locating…` if GPS is denied). PASS: any non-blank
      distance or error string. FAIL: status stays `Locating…` with no update.
- [ ] Triple-tap the top-right invisible area; tap 1 and 2 each produce a 60ms vibration
      felt on the Galaxy A56; the third tap produces the [40,60,120] short-short-long
      pattern and fires the AR scene. PASS: all three taps produce distinct felt feedback.
      FAIL: any tap produces no vibration.
- [ ] With the AR scene running, wait 8 seconds; the compass calibration banner either
      changes to `heading active` (if orientation events fired) and fades, or fades
      outright (if no events fired). PASS: banner gone or showing `heading active` within
      9 seconds. FAIL: banner still shows `calibrating heading` after 9 seconds.
- [ ] The audio-mode-btn (♪, bottom-left) is visible in ambient light at opacity 0.55.
      PASS: icon legible in daylight. FAIL: icon invisible or too dim.

## Step 5: Rollback (If Needed)

If the M-001 build misbehaves during smoke test or during the walk-up:

1. On the phone, open the **Files** app
2. Navigate to **Downloads**
3. Tap `anniversary-rollback.apk`
4. Confirm the install prompt

No laptop, rebuild, or internet connection required. Total revert time: under 60 seconds.
