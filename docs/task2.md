This is an inspired idea. Using a casual evening jog around the Academic Oval as the "alibi" for the reveal turns a static installation into a massive, 10-minute experiential narrative.

From an optical and architectural standpoint, a 650-meter threshold from Palma Hall (AS) across the campus fundamentally changes the pacing of the AR experience. Here is exactly how it will look through the camera and how you can script the code to match a walking pace.

### 1. The Physics of the View at 650 Meters

At 650 meters, a 60-meter diameter virtual ring (your 30m radius cylinder) will take up approximately **5.3 degrees** of the phone camera's field of view.

It will not look like a massive UFO dominating the sky. Instead, it will look like a highly distinct, luminous, floating crown in the distance. The fireworks, which spawn around 15m and shoot upward, will look like a private festival happening on the other side of the campus.

### 2. The "Sunken Garden / Acad Oval Canopy" Effect

Looking toward the Parish of the Holy Sacrifice from Palma Hall means your line of sight crosses right through the dense Acacia tree canopy of the Oval and the Sunken Garden area.

Because standard AR layers additive blending *over* the camera feed without depth occlusion, the physical trees will be silhouetted in black against the night sky, and your fireworks and marquee will burn brightly right *through* the leaves. It will look like a sci-fi beacon pulling you off your jogging route and drawing you across the campus.

### 3. The "Three-Act" Code Structure

If you trigger *everything* at 650 meters, the text on the marquee will be too small to read, and the "elapsed time" stats will be tiny floating dots.

To make the walk from Palma Hall feel like a cinematic progression, you can split the proximity triggers into a **Three-Act Structure** based on distance. You can update your `animate()` loop (or your modularized `scene.js`) to stagger the opacity math like this:

```javascript
// ACT 1: The Beacon (650m)
// Fireworks begin firing, but text remains hidden.
if (userDistanceToChurch <= 650) {
    showFireworks = true; 
}

// ACT 2: The Marquee Reveal (Starts at 400m, fully visible by 150m)
// As you leave the Oval and approach the chapel grounds, the halo materializes.
const marqueeTarget = userDistanceToChurch <= 400 ? 
    Math.max(0, 1 - ((userDistanceToChurch - 150) / 250)) : 0; 

// ACT 3: The Intimate Details (Starts at 100m, fully visible by 40m)
// The specific years, months, weeks, and days fade in only as you arrive.
const statsTarget = userDistanceToChurch <= 100 ? 
    Math.max(0, 1 - ((userDistanceToChurch - 40) / 60)) : 0;

// Apply the smooth lerp
marqueeBaseOpacity += (marqueeTarget * 0.8 - marqueeBaseOpacity) * 0.02;
statsBaseOpacity   += (statsTarget * 0.8 - statsBaseOpacity) * 0.02;

```

### The Experiential Result

By structuring the math this way, the jogging session becomes a choreographed journey. When you pause near Palma Hall and tell her to hold up the phone, she will just see the electric cyan and gold fireworks going off in the distance.

As you both walk closer, cutting across the campus, the glowing 60-meter ring of the marquee slowly bleeds into existence through the trees. Finally, as you arrive at the actual concrete dome of the church, the highly personal time stats (calculated down to the day) reveal themselves floating just above the ground.