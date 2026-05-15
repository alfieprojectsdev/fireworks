# Spatial Anchoring and Cylindrical Marquee Plan

This updated plan incorporates exact GPS anchoring and the incredible idea of a cylindrical marquee for the Anniversary text.

## 1. Absolute GPS Anchoring
**Current Behavior:** The text spawns a fixed 40 meters in front of wherever the phone was pointing when the app opened.
**Proposed Fix:** 
1. When `startExperience()` is triggered, the engine will grab your *exact* current GPS coordinate and compare it to the church's exact target coordinates (`14.658888, 121.071173`).
2. It will calculate the precise distance (in meters) and the compass bearing (angle from True North) between you and the church center.
3. The Three.js engine will place the central AR Group exactly at that calculated `(X, Z)` position.
4. Because the `DeviceOrientationControls` uses your phone's magnetometer (compass), the virtual structure will be locked to the physical church regardless of where you walk. 

## 2. The Cylindrical Marquee
It is absolutely not too complicated—in fact, it's a brilliant idea for AR!
Instead of a flat plane, we will wrap the "Happy Anniversary, Bhaze!" text around a giant floating `THREE.CylinderGeometry`.
- **The Cylinder:** We will create an open-ended invisible cylinder with a massive radius (e.g., 25 meters).
- **The Marquee:** The text will be drawn repeatedly on a wide Canvas texture, mapped to the cylinder with `THREE.DoubleSide` rendering. This means if you walk close to the church, you can actually stand *inside* the cylinder and watch the text spin around you!
- **Animation:** The cylinder will continuously rotate around its Y-axis (`rotation.y += 0.005`), creating a seamless, floating marquee ring in the sky over the church.

## 3. Time-Since-Wedding Statistics Array
We will calculate the exact time elapsed since **May 15, 2008**, up to the current moment.
- We will generate 4 new 3D text planes:
  - `X Years`
  - `X Months`
  - `X Weeks`
  - `X Days`
- **Spatial Arrangement:** These will be suspended beneath the marquee cylinder. They will be arranged in a square (facing North, East, South, and West), meaning as you physically walk around the perimeter of the church, you will encounter the different statistics in turn.
- **Opacity:** As requested, the stats will be rendered with `opacity: 0.5` (50% transparency).

## User Review Required
Does the Cylindrical Marquee and the updated structural layout sound ready for implementation? If so, approve this plan and I will write the code!
