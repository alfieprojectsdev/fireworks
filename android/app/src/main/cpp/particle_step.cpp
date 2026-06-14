#include "particle_step.h"

// Mirrors Firework.update()'s explosion branch (fireworks.js:147-168)
// operation-for-operation. Results track the JS reference within float rounding
// (JS uses f64 intermediates; this kernel is f32 throughout) — see header.
int step_particles(float* pos, float* vel, const float* life,
                   int count, int type, float fadeRate,
                   float windX, float windY, float windZ) {
    // Per-type constants — identical to the ternaries in fireworks.js.
    const float streak  = (type == 2) ? 3.0f  : (type >= 3) ? 0.8f   : 1.5f;
    const float gravity = (type == 1) ? 0.05f : (type >= 3) ? 0.004f : 0.03f;
    const float drag    = (type >= 3) ? 0.985f : 0.96f;

    int alive = 0;

    for (int i = 0; i < count; ++i) {
        const int p = i * 6;
        const int q = i * 3;

        // Dead particle: collapse head onto tail (zero-length segment, invisible).
        if (fadeRate * life[i] > 1.0f) {
            pos[p]     = pos[p + 3];
            pos[p + 1] = pos[p + 4];
            pos[p + 2] = pos[p + 5];
            continue;
        }
        ++alive;

        float vx = vel[q], vy = vel[q + 1], vz = vel[q + 2];

        // Advance head.
        pos[p]     += vx;
        pos[p + 1] += vy;
        pos[p + 2] += vz;

        // Tail trails the head by `streak` velocity-lengths.
        pos[p + 3] = pos[p]     - vx * streak;
        pos[p + 4] = pos[p + 1] - vy * streak;
        pos[p + 5] = pos[p + 2] - vz * streak;

        // Integrate velocity — order matches JS: gravity, then drag, then wind.
        vy -= gravity;
        vx *= drag; vy *= drag; vz *= drag;
        vx += windX; vy += windY; vz += windZ;

        vel[q] = vx; vel[q + 1] = vy; vel[q + 2] = vz;
    }

    return alive;
}
