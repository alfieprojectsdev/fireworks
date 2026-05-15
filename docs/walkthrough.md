# Cinematic AR Fireworks - Implementation Walkthrough

The "Cinematic Immersion" upgrades have been successfully integrated into `public/index.html`. This walkthrough summarizes the new features and structural changes made to achieve the premium spatial experience on the Galaxy A56 5G.

## 1. Absolute GPS Anchoring
The virtual AR objects are no longer loosely tied to the direction the phone is facing! 
- **Distance & Bearing:** When the experience triggers (or you use the triple-tap), the engine now reads your exact latitude and longitude. It mathematically calculates the physical distance (in meters) and the compass bearing from your body to the center of the church.
- **World Lock:** It translates those physical offsets into the Three.js 3D coordinate system (`X` and `Z` offsets). Because the gyroscope acts as a compass, the AR elements are now perfectly anchored exactly above the physical church location, regardless of where you walk or how you turn.

## 2. The Cylindrical Marquee
The static floating text has been completely reimagined:
- **`THREE.CylinderGeometry`:** The text "Happy Anniversary, Bhaze!" is now repeated across a massive `4096px` canvas, which is wrapped perfectly around a huge invisible cylinder with a radius of 20 virtual meters.
- **Spin Animation:** In the rendering loop, the cylinder smoothly rotates (`rotation.y += 0.002`).
- **Immersive Scale:** Because the material is `DoubleSide`, if you walk close enough to the church coordinates in the real world, you can actually step *inside* the massive floating ring and look up as it spins around you!

## 3. Time-Since-Wedding Statistics Array
Directly below the marquee, the engine dynamically calculates the exact time elapsed since **May 15, 2008**:
- **Live Calculation:** It generates the exact `Years`, `Months`, `Weeks`, and `Days` elapsed based on today's date.
- **The "Walk-Around" Array:** The four statistics are mounted onto four separate transparent `PlaneGeometry` objects. They are arranged in a perfect square (facing 0°, 90°, 180°, and 270° around the Y-axis). This means you cannot see them all at once—you have to physically walk around the perimeter of the church to read each milestone in turn!
- **Interactive Flash:** Just like the marquee, the opacity of the statistics array jumps brightly when a firework explodes nearby, then smoothly fades back to an elegant 50% transparency.

## 4. Environmental Physics & Streaks
The `Firework` class was completely rewritten to support advanced physics and realistic streak visuals:
- **`THREE.LineSegments` Trails:** We abandoned rendering individual `THREE.Points` dots. Instead, every particle is dynamically rendered as a Line Segment that stretches exactly from its current position back to its previous frame's velocity position. This creates brilliant, realistic light streaks.
- **Three Shell Types:** Explosions now dynamically choose between Peony, Willow, and Chrysanthemum.
- **Wind Drift:** A global wind vector `(0.015, 0, -0.005)` is constantly applied to the physics simulation, gently pushing the falling embers sideways over time.

> [!TIP]
> **Deployment:** Run `./serve_apk.sh` to compile the fresh Android build and pull it straight onto your Galaxy A07 5G via your local WiFi network!
