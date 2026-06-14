// Host-side unit test for step_particles(). No device / NDK required.
//
//   g++ -std=c++17 -O2 -I.. test_particle_step.cpp ../particle_step.cpp -o /tmp/pt && /tmp/pt
//
// Verifies (a) SoA stride/indexing, (b) per-type constant selection, and
// (c) exact arithmetic parity with the JS reference in fireworks.js.

#include "particle_step.h"
#include <cmath>
#include <cstdio>
#include <vector>

static int g_fail = 0;

static void check(const char* what, float got, float want) {
    // Single-precision throughout (matches Float32Array + jfloat), so a tight
    // epsilon is appropriate; loosen only if it ever flakes on another arch.
    if (std::fabs(got - want) > 1e-4f) {
        std::printf("FAIL %-28s got=%.6f want=%.6f\n", what, got, want);
        ++g_fail;
    } else {
        std::printf("ok   %-28s %.6f\n", what, got);
    }
}

int main() {
    // ---- Case 1: single alive Peony (type 0) particle, one step --------------
    // Reference computed by replaying fireworks.js update() for type 0:
    //   sl=1.5, g=0.03, drag=0.96, wind=(0.015,0,-0.005)
    {
        float pos[6]  = {10, 20, 30,  10, 20, 30};   // head, tail coincident
        float vel[3]  = {1, 2, 3};
        float life[1] = {1.0f};                       // fadeRate*life = 0.5 -> alive
        int alive = step_particles(pos, vel, life, 1, /*type*/0, /*fadeRate*/0.5f,
                                   0.015f, 0.0f, -0.005f);
        check("c1 alive", (float)alive, 1.0f);
        // head += vel
        check("c1 head.x", pos[0], 11.0f);
        check("c1 head.y", pos[1], 22.0f);
        check("c1 head.z", pos[2], 33.0f);
        // tail = head - vel*1.5  (vel is pre-integration value here)
        check("c1 tail.x", pos[3], 11.0f - 1.0f * 1.5f);   // 9.5
        check("c1 tail.y", pos[4], 22.0f - 2.0f * 1.5f);   // 19.0
        check("c1 tail.z", pos[5], 33.0f - 3.0f * 1.5f);   // 28.5
        // vel: vy-=g, then *drag, then +wind
        check("c1 vel.x", vel[0], 1.0f * 0.96f + 0.015f);            // 0.975
        check("c1 vel.y", vel[1], (2.0f - 0.03f) * 0.96f + 0.0f);    // 1.8912
        check("c1 vel.z", vel[2], 3.0f * 0.96f - 0.005f);            // 2.875
    }

    // ---- Case 2: dead particle collapses head onto tail ----------------------
    {
        float pos[6]  = {5, 6, 7,  1, 2, 3};
        float vel[3]  = {9, 9, 9};
        float life[1] = {2.0f};                       // fadeRate*life = 2.0 > 1 -> dead
        int alive = step_particles(pos, vel, life, 1, 0, /*fadeRate*/1.0f,
                                   0, 0, 0);
        check("c2 alive", (float)alive, 0.0f);
        check("c2 head.x->tail", pos[0], 1.0f);
        check("c2 head.y->tail", pos[1], 2.0f);
        check("c2 head.z->tail", pos[2], 3.0f);
        check("c2 vel untouched", vel[0], 9.0f);      // dead particles skip integration
    }

    // ---- Case 3: per-type constants (Willow=1 gravity, shaped>=3 drag) --------
    {
        // Willow: g=0.05. Single step, vy delta = -0.05 before drag.
        float pos[6]  = {0,0,0, 0,0,0};
        float vel[3]  = {0, 10, 0};
        float life[1] = {1.0f};
        step_particles(pos, vel, life, 1, /*type*/1, 0.0f, 0, 0, 0);
        check("c3 willow vel.y", vel[1], (10.0f - 0.05f) * 0.96f);   // 9.5048
    }
    {
        // Heart (type 3): g=0.004, drag=0.985, streak=0.8.
        float pos[6]  = {0,0,0, 0,0,0};
        float vel[3]  = {0, 10, 0};
        float life[1] = {1.0f};
        step_particles(pos, vel, life, 1, /*type*/3, 0.0f, 0, 0, 0);
        check("c3 heart vel.y", vel[1], (10.0f - 0.004f) * 0.985f);  // 9.84606
        check("c3 heart tail.y", pos[4], pos[1] - 10.0f * 0.8f);     // streak 0.8
    }

    // ---- Case 4: mixed alive/dead count over many particles ------------------
    {
        const int N = 5;
        std::vector<float> pos(N * 6, 0.0f), vel(N * 3, 1.0f);
        std::vector<float> life = {0.5f, 3.0f, 0.5f, 3.0f, 0.5f};  // 3 alive, 2 dead
        int alive = step_particles(pos.data(), vel.data(), life.data(),
                                   N, 0, /*fadeRate*/1.0f, 0, 0, 0);
        check("c4 alive count", (float)alive, 3.0f);
    }

    // ---- Case 5: Chrysanthemum (type 2) — distinctive streak=3, gravity=0.03 --
    // Most likely victim of a ternary transposition; the default-branch types
    // (0/1) use streak 1.5 and shaped types (>=3) use 0.8, so 3.0 is unique.
    {
        float pos[6]  = {0,0,0, 0,0,0};
        float vel[3]  = {2, 5, 0};
        float life[1] = {1.0f};
        step_particles(pos, vel, life, 1, /*type*/2, /*fadeRate*/0.0f, 0, 0, 0);
        check("c5 chrys tail.x", pos[3], pos[0] - 2.0f * 3.0f);      // streak 3.0
        check("c5 chrys tail.y", pos[4], pos[1] - 5.0f * 3.0f);
        check("c5 chrys vel.y", vel[1], (5.0f - 0.03f) * 0.96f);     // default gravity/drag
    }

    // ---- Case 6: multi-particle, multi-step — exercises i>=1 stride + write-back
    // Two steps so vel mutated in step 1 must be consumed in step 2; checks the
    // SECOND particle (i==1), which a q=i*3 / p=i*6 stride bug would corrupt.
    {
        const int N = 2;
        // Particle 0: vel (1,0,0); particle 1: vel (0,0,1). Distinct axes so a
        // stride mix-up between particles shows up immediately.
        float pos[12] = {0,0,0, 0,0,0,   0,0,0, 0,0,0};
        float vel[6]  = {1,0,0,          0,0,1};
        float life[2] = {1.0f, 1.0f};
        // Peony (type 0): streak 1.5, gravity 0.03, drag 0.96, no wind.
        step_particles(pos, vel, life, N, 0, /*fadeRate*/0.0f, 0, 0, 0);
        // After step 1, particle 1 head.z = 1; vel.z = (1)*0.96 = 0.96, vel.y = -0.03*0.96.
        step_particles(pos, vel, life, N, 0, /*fadeRate*/0.0f, 0, 0, 0);
        // Step 2 head.z += 0.96 -> 1.96; head.y += (-0.0288) -> -0.0288.
        check("c6 p1 head.z", pos[8],  1.96f);
        check("c6 p1 head.y", pos[7],  -0.0288f);
        // Particle 0 must be untouched on z/y axes (no cross-particle bleed).
        check("c6 p0 head.x", pos[0],  1.0f + 0.96f);   // 1.96 along x only
        check("c6 p0 head.z", pos[2],  0.0f);
    }

    std::printf("\n%s (%d failures)\n", g_fail ? "TESTS FAILED" : "ALL PASS", g_fail);
    return g_fail ? 1 : 0;
}
