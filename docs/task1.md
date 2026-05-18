# MISSION: AR Sensor Fusion Bridge (Capacitor Native to Three.js)

## Context
We are working on the `alfieprojectsdev/fireworks` repository. This is a CapacitorJS + Three.js hybrid application. Currently, the application uses web APIs (`navigator.geolocation` and `deviceorientationabsolute`) which are heavily throttled by the WebView and suffer from electromagnetic interference, causing severe pose drift and jitter in our AR objects.

## Objective
Design a step-by-step execution plan to replace the web sensor APIs with a custom Capacitor Android Plugin that bridges raw, high-frequency hardware data directly into our Three.js rendering loop.

## Architecture Blueprint Requirements
The plan must encompass the following three pillars:

1.  **High-Frequency Orientation (Gyro/Compass):**
    * Bypass `deviceorientationabsolute`.
    * Implement Android's `Sensor.TYPE_ROTATION_VECTOR` to utilize hardware-level Kalman filtering.
    * Stream the resulting quaternion `[x, y, z, w]` to the WebView.
2.  **High-Accuracy Positioning (GPS):**
    * Bypass `navigator.geolocation`.
    * Implement Google's `FusedLocationProviderClient` configured with `PRIORITY_HIGH_ACCURACY` and an interval of 1000ms.
3.  **IPC (Inter-Process Communication) Bridge:**
    * Define an efficient Capacitor Event pipeline to send this data to `window` listeners in `public/index.html` without blocking the Three.js main thread.

## Required File Scoping (Planner Context)
The plan must evaluate and target the following files:
* `android/app/src/main/AndroidManifest.xml` (Permissions for precise location).
* `android/app/build.gradle` (Dependencies for Play Services Location).
* `android/app/src/main/java/com/alfie/anniversary/MainActivity.java` (Or a new dedicated Plugin class).
* `public/index.html` (The Three.js consumer loop).

## Phase Breakdown for the Coder Agent
Structure your final plan for the Coder agent into these distinct phases:

### Phase 1: Native Android Configuration
* Add `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` to `AndroidManifest.xml`.
* Add the necessary `com.google.android.gms:play-services-location` dependency to the app's `build.gradle`.

### Phase 2: The Capacitor Plugin Implementation (Java)
* Draft the Java implementation (either in `MainActivity.java` or a new registered plugin class) that implements `SensorEventListener`.
* Set up the `SensorManager` to listen to `TYPE_ROTATION_VECTOR`.
* Set up the `FusedLocationProviderClient` with a `LocationCallback`.
* Create the Capacitor `notifyListeners` or `bridge.triggerJSEvent` logic to emit two specific custom events: `onNativeOrientationUpdate` and `onNativeLocationUpdate`.

### Phase 3: Three.js Integration & Web Cleanup (`public/index.html`)
* Remove the `THREE.DeviceOrientationControls` implementation.
* Add event listeners for `onNativeOrientationUpdate` to update the `camera.quaternion.set(x, y, z, w)` directly.
* Add event listeners for `onNativeLocationUpdate` to update the `userDistanceToChurch` proximity variable.
* Ensure fallback logic exists: if the native plugin fails to initialize, the system gracefully falls back to the existing web APIs.

## Constraints & Rules
1.  **Do not break the math:** The existing Three.js scene coordinate system (offset math based on the Haversine formula) and firework parametric equations must remain untouched. 
2.  **Performance:** Ensure the native Java code checks for delta changes in the quaternion before spamming the IPC bridge to prevent frame drops in the 60fps WebGL loop.
3.  **Output:** Output a strict, file-by-file execution plan for the Coder agent.