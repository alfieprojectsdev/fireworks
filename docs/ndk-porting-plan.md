# NDK Porting Plan — Anniversary AR App

## Executive Summary

Three components benefit from C++ porting, ranked by ROI:

| Priority | Component | Current bottleneck | Expected gain |
|---|---|---|---|
| 1 (HIGH) | Fireworks particle loop | 160K–540K float ops/sec peak in JS, heap alloc burst on explode | 10–30× throughput; eliminates GC stalls |
| 2 (MED) | SensorBridgePlugin hot path | JSObject + 4× put() + notifyListeners() at ~50 Hz | ~30–50% IPC reduction; lower orientation latency |
| 3 (LOW) | Haversine/bearing | Only 1 Hz; no real gain | Skip unless Phase 1+2 reveal headroom |

WebGL rendering (`renderer.render()`) is GPU-bound — the bottleneck is the WebGL pipeline, not JS. NDK cannot help there. Do not port the Three.js render loop.

---

## Phase 1 — Fireworks Particle Physics

### Why it matters

`Firework.update()` in `public/js/fireworks.js` runs every frame (~60 Hz) for up to 15 simultaneous fireworks, each with 250–400 particles. Two compounding problems:

1. **Heap allocation burst**: `explode()` calls `new THREE.Vector3()` per particle (~300 allocations per explosion) — triggers GC mid-frame.
2. **Buffer sync cost**: `geometry.attributes.position.needsUpdate = true` forces a GPU buffer upload every frame regardless of particle count.

### Target architecture

```
JS (Three.js frame loop)
    │
    ▼ ParticleEngine.step(dt) via JNI
Java ParticleEngine.java (thin JNI bridge)
    │
    ▼
C++ particle_engine.cpp
    - Pre-allocated float[] pools (pos, vel, life, color)
    - SoA layout: pos_x[], pos_y[], pos_z[], vel_x[], vel_y[], vel_z[], life[]
    - Per-particle gravity + drag + lifetime decrement
    - Returns updated pos array directly into pre-allocated JS Float32Array
```

### File layout

```
android/app/src/main/cpp/
    CMakeLists.txt               # new (or augment if exists)
    particle_engine.cpp          # particle step + spawn logic
    particle_engine.h

android/app/src/main/java/com/alfie/anniversary/
    ParticleEngine.java          # JNI bridge (thin)

public/js/fireworks.js          # edit: delegate physics to ParticleEngine
```

### JNI interface

```cpp
// particle_engine.h
extern "C" {
    JNIEXPORT void JNICALL
    Java_com_alfie_anniversary_ParticleEngine_nativeStep(
        JNIEnv* env, jclass cls,
        jfloatArray pos,   // [x0,y0,z0, x1,y1,z1, ...] — modified in-place
        jfloatArray vel,   // [vx0,vy0,vz0, ...] — modified in-place
        jfloatArray life,  // [l0, l1, ...] — modified in-place
        jint count,
        jfloat dt,
        jfloat gravity,
        jfloat drag
    );

    JNIEXPORT void JNICALL
    Java_com_alfie_anniversary_ParticleEngine_nativeSpawn(
        JNIEnv* env, jclass cls,
        jfloatArray pos, jfloatArray vel, jfloatArray life,
        jint offset, jint count,
        jfloat originX, jfloat originY, jfloat originZ,
        jfloat speed, jfloat lifespan
    );
}
```

Key: `GetPrimitiveArrayCritical` / `ReleasePrimitiveArrayCritical` to avoid copy — pin the Java float[] directly. Only valid when no other JNI calls happen between pin and release (safe here since this is the only work per call).

### JS side changes (`fireworks.js`)

- Pre-allocate `Float32Array` pools at construction (max 400 particles × 3 floats)
- On `explode()`: call `ParticleEngine.nativeSpawn()` instead of `new THREE.Vector3()` loop
- On `update()`: call `ParticleEngine.nativeStep()`, then write pool back to `BufferGeometry.attributes.position.array`
- Set `needsUpdate = true` only when `aliveCount > 0` (skip dead fireworks entirely)

### Gearsync reference

- `writeAccelRingSample()` (native-lib.cpp:176) — pattern for writing into pre-allocated array without alloc
- `sensorThreadFn()` (native-lib.cpp:502) — thread lifecycle: attach once, loop, detach on exit
- `DspPrimitives.h:8` — Cooley-Tukey FFT shows the SoA tight-loop pattern to mimic

---

## Phase 2 — SensorBridge Orientation Hot Path

### Why it matters

`SensorBridgePlugin.java` allocates a new `JSObject` + 4× `put()` calls + `notifyListeners()` on every `TYPE_ROTATION_VECTOR` sensor event at `SENSOR_DELAY_GAME` (~50 Hz). The `notifyListeners()` path serializes to JSON and posts to the WebView message queue — that's ~50 round-trips/sec of JS→Java→JS IPC.

On the JS side, `onNativeOrientationUpdate` (sensors.js:130–134) sets the camera quaternion. Most of these calls move the heading by <0.5° — imperceptible to the user but still burns IPC budget.

### Target architecture

Move sensor polling to a C++ `ASensorEventQueue` thread (same pattern as gearsync's `sensorThreadFn`). Apply delta gating and smoothing in C++. Post to JS only when the heading delta exceeds a threshold (suggest 1.0°).

```
C++ ASensorEventQueue thread (~300 Hz raw)
    │
    ├─ smooth quaternion (Gearsync SPSC ring pattern)
    ├─ compute heading delta since last post
    └─ if delta > 1.0°: enqueue event
            │
            ▼
    Java callback (AttachCurrentThread, CallVoidMethod)
            │
            ▼
    notifyListeners() → WebView (50→5 Hz effective)
```

### File layout

```
android/app/src/main/cpp/
    sensor_bridge.cpp            # ASensorManager + SPSC ring + delta gate
    sensor_bridge.h
    spsc_ring.h                  # lifted from gearsync pattern

android/app/src/main/java/com/alfie/anniversary/
    SensorBridgePlugin.java      # edit: JNI_OnLoad init + remove Java SensorManager path
```

### JNI interface

```cpp
// sensor_bridge.h
extern "C" {
    JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*);  // cache VM, find callback method

    JNIEXPORT void JNICALL
    Java_com_alfie_anniversary_SensorBridgePlugin_nativeStartSensor(
        JNIEnv* env, jobject plugin,
        jfloat deltaThresholdDeg   // suppress posts below this heading change
    );

    JNIEXPORT void JNICALL
    Java_com_alfie_anniversary_SensorBridgePlugin_nativeStopSensor(
        JNIEnv* env, jobject plugin
    );
}
```

The C++ thread calls back into Java via cached `jmethodID` for `onNativeOrientationUpdate(float x, float y, float z, float w)`. This eliminates JSObject alloc on the hot path.

### Gearsync reference patterns to copy

```cpp
// gearsync native-lib.cpp:502 — thread lifecycle (copy verbatim, adapt looper)
static void* sensorThreadFn(void* arg) {
    JavaVM* vm = ...;
    JNIEnv* env;
    vm->AttachCurrentThread(&env, nullptr);
    // ... ALooper_prepare, ASensorEventQueue_enableSensor ...
    while (running) {
        ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
        // drain queue, compute delta, conditionally post
    }
    vm->DetachCurrentThread();
    return nullptr;
}
```

SPSC ring (`spsc_ring.h` from gearsync) — store raw quaternion samples from the sensor thread; the callback thread reads the latest. No mutex needed.

### JS side (no change to sensors.js)

`onNativeOrientationUpdate` signature unchanged — Phase 2 is transparent to JS. The only visible effect is fewer IPC calls per second.

---

## Phase 3 — Haversine/Bearing (defer)

1 Hz GPS — compute time is ~0.01ms per call. Moving to C++ saves <0.5ms/min. Not worth the JNI overhead until Phases 1+2 are complete and profiled. Keep in JS (`sensors.js:141–147`).

---

## Build Configuration

### `android/app/src/main/cpp/CMakeLists.txt`

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(anniversary_native CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    anniversary_native
    SHARED
    particle_engine.cpp
    sensor_bridge.cpp
)

target_include_directories(anniversary_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    anniversary_native
    ${log-lib}
    ${android-lib}
    # Phase 2 only:
    # android     # for ALooper / ASensor APIs
)
```

No Oboe needed — this app has no audio path in C++ (audio stays as Web Audio API). Unlike gearsync, skip the prefab AAR and `c++_shared` — `c++_static` is fine for a single .so.

### `android/app/build.gradle` additions

```groovy
android {
    // ... existing config ...

    defaultConfig {
        // ... existing ...
        externalNativeBuild {
            cmake {
                cppFlags "-std=c++17 -O3 -ffast-math"
                abiFilters "arm64-v8a"   // A56 is arm64; skip x86 overhead
            }
        }
    }

    externalNativeBuild {
        cmake {
            path "src/main/cpp/CMakeLists.txt"
            version "3.22.1"
        }
    }
}
```

Add to `local.properties` (or confirm already present via gearsync):
```
ndk.dir=/path/to/sdk/ndk/27.1.12297006
```

Use NDK **27.1.12297006** — same as gearsync, proven stable on A56.

---

## Permissions (Phase 2 only)

`AndroidManifest.xml` additions for high-rate sensors:

```xml
<!-- Required for >200 Hz sensor rate on Android 12+ -->
<uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS"/>
```

No foreground service needed — Phase 2 does not hold sensor during background. Sensor starts/stops with `startExperience()` / app pause.

---

## Migration Sequence

```
Phase 1a  Add CMakeLists.txt + empty particle_engine.cpp → confirm NDK builds
Phase 1b  Implement nativeStep + nativeSpawn; test with synthetic data in unit harness
Phase 1c  Edit fireworks.js to delegate; A/B compare frame timing with Chrome DevTools
Phase 1d  Remove old THREE.Vector3 alloc path; commit + PR review

Phase 2a  Add sensor_bridge.cpp skeleton; confirm ASensorManager links
Phase 2b  Port sensorThreadFn from gearsync; implement delta gating
Phase 2c  Wire into SensorBridgePlugin.java; remove Java SensorManager path
Phase 2d  Test: verify compass still drives DeviceOrientationControls; commit + PR
```

Each phase is independently shippable — do not batch Phases 1 and 2 into a single PR.

---

## Effort Estimates

| Phase | Effort | Risk |
|---|---|---|
| 1a — NDK scaffold | 30 min | Low — CMake boilerplate |
| 1b — particle C++ | 3–4 h | Medium — SoA layout + GetPrimitiveArrayCritical subtleties |
| 1c/1d — JS integration | 2 h | Low — interface is simple |
| 2a/2b — sensor C++ | 4–5 h | Medium — ASensorEventQueue + SPSC ring |
| 2c/2d — Java integration | 2 h | Medium — JNI_OnLoad method caching |

Total: ~12–13 hours. Phase 1 alone (~6 h) delivers the highest visible improvement.

---

## What NOT to Port

- `renderer.render(scene, camera)` — GPU-bound; NDK cannot help
- `DeviceOrientationControls.update()` — already replaced by native bridge in primary path
- `_buildHUD()` / DOM manipulation — UI, not compute
- Haversine — 1 Hz, negligible (Phase 3 deferred indefinitely)
- Audio (Web Audio API) — adequate latency for firework SFX; Oboe adds significant complexity for no user-perceptible gain without a professional mixing use case
