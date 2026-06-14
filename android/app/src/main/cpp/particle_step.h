#ifndef ANNIVERSARY_PARTICLE_STEP_H
#define ANNIVERSARY_PARTICLE_STEP_H

// Pure particle physics — NO jni.h, so a host compiler can link this against
// system glibc headers for unit testing (NDK's jni.h conflicts with host libc).
//
// Layout (single firework, SoA pools owned by JS / Java):
//   pos[count*6]  head(x,y,z) + tail(x,y,z) interleaved, stride 6.
//                 Matches the LineSegments BufferGeometry vertex layout 1:1.
//   vel[count*3]  particle velocity, stride 3. Mutated in place.
//   life[count]   per-particle lifespan multiplier (read-only).
//
// Scalar params replicate Firework.update()'s explosion branch exactly:
//   type      0=Peony 1=Willow 2=Chrysanthemum 3=Heart 4=Lemniscate 5=Lissajous
//   fadeRate  firework.life / firework.maxLife (frame-advanced on the JS side)
//   windX/Y/Z constant per-frame drift added after drag
//
// Returns count of still-alive particles (0 => firework can be retired).
int step_particles(float* pos, float* vel, const float* life,
                   int count, int type, float fadeRate,
                   float windX, float windY, float windZ);

#endif  // ANNIVERSARY_PARTICLE_STEP_H
