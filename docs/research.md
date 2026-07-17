Here are the technical suggestions reformatted as targeted, structured prompts for Claude Code. These are designed to guide the agent through an initial exploratory and scoping phase, allowing you to review the architecture and code drafts before committing changes to your main files.

---

### Research Path 1: Eliminating Compass Jitter via Native `ROTATION_VECTOR` Plugin

**Copy-paste prompt for Claude Code:**

```text
I want to explore a research path to completely eliminate compass jitter and browser sensor throttling by building a custom native Android sensor bridge.

Please inspect the current repository structure, specifically looking at:
1. `public/index.html` (how DeviceOrientationControls are used to orient the camera)
2. `android/app/src/main/java/com/alfie/anniversary/MainActivity.java`

Do not modify the files yet. Instead, act as an expert mobile systems engineer and provide a detailed architectural plan and a code draft for a custom Capacitor plugin that accomplishes the following:
- In MainActivity.java, sets up a native Android SensorEventListener targeting `Sensor.TYPE_ROTATION_VECTOR` to leverage hardware-level sensor fusion and internal Kalman filtering.
- Captures the raw 3D orientation Quaternion [x, y, z, w] at high frequency (60Hz–100Hz).
- Passes those coordinates across the Capacitor bridge via a custom event (e.g., 'nativeQuaternionUpdate') directly to the JavaScript window object.
- Shows exactly how we would modify the Three.js rendering loop in `public/index.html` to listen for this event and update `camera.quaternion.set(x, y, z, w)` directly while dropping DeviceOrientationControls.

Provide this research as a Markdown report with the precise native Java code blocks and the corresponding JavaScript modifications so I can review the implementation logic first.

```

---

### Research Path 2: High-Accuracy Tracking via Fused Location Provider

**Copy-paste prompt for Claude Code:**

```text
I want to research replacing the current web-based `navigator.geolocation` loop with a high-accuracy, low-latency native Android positioning setup to make our proximity reveal triggers instant.

Please analyze the current geolocation and distance math in `public/index.html`. Then, evaluate two distinct implementation options for this repository:
- Option A: Installing and utilizing the official `@capacitor/geolocation` core plugin.
- Option B: Building a custom native implementation within our existing Android folder using Google's `FusedLocationProviderClient`.

For both options, research how to explicitly configure:
1. `PRIORITY_HIGH_ACCURACY` to force hardware GPS usage over cell tower triangulation.
2. An update interval and `fastestInterval` set to 1000ms or lower.

Provide a comparative breakdown detailing performance overhead, battery impacts, and setup complexity. Include code drafts showing how the native updates would travel across the bridge to dynamically update the `userDistanceToChurch` variable inside our Three.js animation loop. Output this as a clean research file.

```

---

### Research Path 3: Combined Custom Hardware Sensor Bridge Feasibility Study

**Copy-paste prompt for Claude Code:**

```text
Let's conduct a comprehensive feasibility study on creating a single, consolidated native hardware plugin bridge inside our Capacitor Android project to handle both high-frequency orientation fusion and real-time high-accuracy GPS tracking.

Scan the entire repository to understand our current Three.js rendering cycle, audio listener sync updates, and build toolchain (`serve_apk.sh`, `build.gradle`, etc.).

Provide a technical blueprint that outlines:
- How a unified custom Capacitor plugin class can be declared in `MainActivity.java` to minimize IPC (Inter-Process Communication) bridge overhead between the Android native thread and the Webview.
- The thread safety implications of streaming 100Hz sensor data arrays across the webview bridge while running a WebGL rendering loop at 60 FPS.
- How to ensure backward compatibility or a graceful fallback to standard Web APIs if the user runs the app in an environment without target hardware sensor access.

Give me an step-by-step implementation guide detailing which files would need to be added or modified in the `/android` directory, complete with file paths and precise code listings. Do not execute the changes; just compile the comprehensive guide for my review.

```
