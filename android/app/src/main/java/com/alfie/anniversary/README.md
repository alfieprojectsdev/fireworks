# SensorBridgePlugin

Capacitor v8 plugin providing direct Android hardware sensor access for the GPS-triggered AR
anniversary experience. Two event channels carry raw sensor data into the Three.js rendering loop.

## Event Channels

| Event | Payload | Source |
|-------|---------|--------|
| `onNativeOrientationUpdate` | `{x, y, z, w}` quaternion | `TYPE_ROTATION_VECTOR` |
| `onNativeLocationUpdate` | `{latitude, longitude, accuracy}` | `FusedLocationProviderClient` |

`TYPE_ROTATION_VECTOR` fuses accelerometer + gyroscope + magnetometer through the
hardware Kalman filter, producing a stable absolute-frame quaternion anchored to true
compass north. `TYPE_GAME_ROTATION_VECTOR` omits the magnetometer: its yaw component
drifts over minutes, which would move the AR marquee off the church's physical bearing.

## Plugin Registration

Auto-discovered by Capacitor v5+ classpath scanning via `@CapacitorPlugin(name = "SensorBridge")`.
`MainActivity.java` requires no `registerPlugin()` override. If the plugin fails to appear
in `Capacitor.Plugins` at runtime, verify the class is in the `com.alfie.anniversary`
package (same package as `MainActivity`). The `load()` method emits `Log.d("SensorBridge",
"SensorBridgePlugin loaded")` to Logcat on successful registration.

Moving `SensorBridgePlugin` to a sub-package or renaming the package silently disables
auto-discovery — the plugin disappears from `Capacitor.Plugins` with no error or exception.
The app continues to run on the web fallback path with no indication that native sensors are
inactive. The `load()` `Log.d` tag will be absent from Logcat, which is the only diagnostic.

## Delta Threshold

`DELTA_THRESHOLD = 0.001f` (L-infinity norm per component) gates `notifyListeners` calls
to suppress redundant IPC events at ~50 Hz (SENSOR_DELAY_GAME). Rationale:

- Typical stationary noise floor: ~1e-4 per component (Android sensor-stack documentation)
- Threshold is ~10x above the noise floor
- Smallest perceptible head motion: ~0.057 deg (~0.017 per component)
- Threshold is ~17x below perceptible motion

**Tuning**: `DELTA_THRESHOLD` is a single `private static final float`. Measure actual
stationary noise on the target device (Galaxy A56 5G) via Logcat instrumentation and
adjust this value. Too tight = IPC saturation; too loose = choppy AR tracking.

## Fallback Contract

`start()` resolves with `{status: "native_ok"}` when both streams register. It resolves
with `{status: "fallback"}` only when location permission is denied. A missing
`TYPE_ROTATION_VECTOR` sensor is silent on the Java side — the JS consumer applies a
2000ms watchdog from `bootstrapSensors()`: if no `onNativeOrientationUpdate` event arrives
within 2s, `DeviceOrientationControls` remains active as the orientation source.

## Threading

`onSensorChanged` is dispatched on the SensorManager background HandlerThread. Capacitor's
`notifyListeners` posts to the WebView thread internally via `bridge.triggerWindowJSEvent`,
which marshals the JSObject to the WebView thread. If `IllegalStateException` appears
in Logcat, wrap the `notifyListeners` call in `getActivity().runOnUiThread(() -> ...)`.

## Lifecycle

| Lifecycle hook | Action |
|----------------|--------|
| `load()` | Acquire `SensorManager` + `FusedLocationProviderClient` |
| `handleOnPause()` | Unregister sensor + stop location updates |
| `handleOnResume()` | Re-register sensor + restart location updates |

Location re-registration in `handleOnResume` is wrapped in `try/catch SecurityException`
to handle mid-session permission revocation without crashing.

## JS Consumer Integration

`public/index.html` `bootstrapSensors()` IIFE:

1. Registers listeners for both event channels
2. Calls `SensorBridge.start()`
3. On `status=fallback` or `.catch()`: calls `startWebGeoInterval()` (web fallback)
4. 2s `setTimeout` watchdog: if `lastNativeLocationAt === 0`, calls `startWebGeoInterval()`

`nativeOrientationActive` flag in `public/index.html` gates `controls.update()` in the
`animate()` loop. `DeviceOrientationControls` is always constructed; only its `.update()`
call is skipped when native orientation is confirmed active.

## Design Decisions

References of the form `(ref: DL-NNN)` in code comments map to these decisions:

| Ref | Decision summary |
|-----|------------------|
| DL-001 | Single plugin for orientation + location; avoids double permission flow and double fallback gating in single-file consumer |
| DL-002 | Auto-discovery via `@CapacitorPlugin` annotation; no `MainActivity.registerPlugin()` override needed |
| DL-003 | Native location is the primary GPS source from app boot; web geolocation (`startWebGeoInterval`) is fallback-only |
| DL-004 | `ACCESS_FINE_LOCATION` requested at app launch via Capacitor permission helpers |
| DL-005 | Quaternion delta threshold 0.001f (L-infinity) suppresses redundant IPC at 50 Hz |
| DL-006 | `SENSOR_DELAY_GAME` (~20 ms) avoids saturating the JNI-to-JS bridge |
| DL-007 | `FusedLocationProviderClient` at 1000 ms `PRIORITY_HIGH_ACCURACY` on `Looper.getMainLooper()` |
| DL-008 | `nativeOrientationActive` flag + 2s watchdog gates fallback without co-writing `camera.quaternion` |
| DL-009 | `DeviceOrientationControls` remains constructed; only `.update()` is gated so fallback re-engages in one frame |
| DL-010 | Test mode (triple-tap) guards native location handler with `if (!isTestMode)` |
| DL-011 | `TYPE_ROTATION_VECTOR` (not `TYPE_GAME_ROTATION_VECTOR`) provides absolute-frame yaw anchored to compass north |
| DL-012 | `play-services-location:21.3.0` pinned explicitly; surfaces transitive API drift at compile time |
| DL-013 | 2000 ms watchdog covers GPS warm-fix latency without blocking the splash crossfade |
| DL-014 | `notifyListeners` invoked from the SensorEventListener background thread; Capacitor bridges to WebView thread internally |
