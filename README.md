# Cinematic AR Fireworks: Anniversary Edition

A high-fidelity, location-based Augmented Reality (AR) experience built to celebrate a very special milestone. This project transforms the sky over a specific physical location into a personalized digital monument, featuring physics-based fireworks, spatial 3D audio, and a "walk-in" immersive marquee.

## 🌟 The Experience

Built as a tribute for **May 15th**, the application uses the phone's camera, GPS, and magnetometer (compass) to anchor a 3D celebration to the real world.

## 🌙 Optimal Viewing Conditions

**Time of Day (Night/Twilight Required)**
This experience relies heavily on WebGL **Additive Blending** (`THREE.AdditiveBlending`) to give the fireworks and text their luminous, glowing appearance. Because additive blending adds virtual light to the physical camera feed, **this application is strictly designed for nighttime or late twilight viewing**. 

If viewed against a bright daytime sky, the additive light values will peak, causing the fireworks and marquee to wash out and become invisible against the bright background.

### Key Features

* **Geospatial Anchoring:** Unlike standard "sticker" AR, these fireworks are mathematically locked to the **Parish of the Holy Sacrifice** (`14.6588, 121.0711`). No matter where you turn or walk, the display stays fixed over the church.
* **Immersive Cylindrical Marquee:** A 20-meter radius rotating ring suspended 15 meters in the air. The scale is designed so the viewer can physically walk "inside" the anniversary message.
* **Proximity-Triggered Reveal:** The marquee fades in only when within 30m of the church; the anniversary stats appear within 20m. Walking toward the location becomes a discovery.
* **Physics-Based Shells:** Six firework types with realistic gravity, wind drift, and streak trails rendered as `THREE.LineSegments`:
  * **Peony** — uniform spherical burst
  * **Willow** — slower, drooping fall under heavier gravity
  * **Chrysanthemum** — fast, wide-radius burst with trailing crackle sounds
  * **Heart** — parametric curve (`16 sin³(t)`) forms a heart viewed from the ground; hot-pink, 10% spawn
  * **Lemniscate** — Bernoulli's lemniscate (`x=cos(t)/(1+sin²t)`) traces the ∞ symbol in the sky; amber gold, 8% spawn
  * **Lissajous 3:2** — `x=sin(3t+π/2), z=sin(2t)`, the oscilloscope figure from wave physics lab; electric cyan, 8% spawn
* **Spatial 3D Audio:** Web Audio API `PannerNode` with HRTF model positions each explosion in 3D space. The audio listener orientation updates every frame from the phone's gyroscope, so sounds pan correctly as you turn.
* **Earphone / Bluetooth Mode:** Automatically detected via `enumerateDevices()` on device connection. Activates a synthesized outdoor reverb convolver and drops the thump oscillator from 150Hz to 100Hz for true bass reproduction through IEMs. Manual toggle (♪ button, bottom-left).
* **Live Anniversary Stats:** Four `PlaneGeometry` text panels below the marquee calculate the exact elapsed time (Years, Months, Weeks, Days) since **May 15, 2008**, arranged facing the four compass directions.
* **Ground Glow:** A faint `RingGeometry` at ground level under the church, pulsing slowly with additive blending.
* **Timed Personal Message:** 60 seconds after the experience starts, a smaller inner cylinder fades in with a dynamically computed message, counter-rotating against the outer marquee.
* **Compass Calibration Indicator:** A small top-left label shows `◎ calibrating heading…` → `◉ heading locked` once absolute device orientation is confirmed, then fades.
* **Build Versioning:** Every `./serve_apk.sh` run auto-increments `versionCode` in `build.gradle` and writes a `public/version.js` with the build timestamp, displayed in the splash screen so you can confirm which build is installed.

## 🛠️ Technical Stack

* **Engine:** [Three.js](https://threejs.org/) v0.128 (WebGL, loaded from CDN)
* **Sensors:** Geolocation API (GPS) & Device Orientation API (Magnetometer/Gyroscope)
* **Audio:** Web Audio API — HRTF panning, synthesized reverb, pre-generated noise pool
* **Platform:** Capacitor v8 / Android APK (`appId: com.alfie.anniversary`)

## 📐 How It Works

1. **Coordinate Fusion:** The app calculates the **Haversine distance** and **compass bearing** from the user's current GPS coordinates to the target location.
2. **World Locking:** Those real-world metres and angles are translated into Three.js `X, Z` offsets from the camera origin.
3. **Sensor Fusion:** `DeviceOrientationControls` syncs the virtual camera to the phone's hardware compass, aligning virtual North with physical North and locking the digital objects to the landmark.

## 🚀 Build & Deploy

```bash
./serve_apk.sh
```

This syncs web assets via `npx cap sync android`, builds a debug APK with Gradle, and serves it over WiFi on port 8000 so the phone can download and sideload it. Each run bumps the APK `versionCode` automatically.

Individual steps:
```bash
npx cap sync android
cd android && ./gradlew assembleDebug
```

The built APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.

## 📍 Location Testing

Triple-tap the invisible 100×100px zone in the **top-right corner** of the screen to bypass GPS and enter **test mode**. The AR group spawns 40m straight ahead at 15m height so the experience can be verified anywhere.

## 💍 Personal Note

> *Calculated since May 15, 2008. Still counting.*
