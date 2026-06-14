#include "particle_engine.h"
#include "particle_step.h"

// Bump when the native ABI/contract changes.
static constexpr int kEngineVersion = 2;

extern "C" {

JNIEXPORT jint JNICALL
Java_com_alfie_anniversary_ParticleEngine_nativeVersion(JNIEnv* /*env*/, jclass /*cls*/) {
    return kEngineVersion;
}

JNIEXPORT jint JNICALL
Java_com_alfie_anniversary_ParticleEngine_nativeStep(
    JNIEnv* env, jclass /*cls*/,
    jfloatArray pos, jfloatArray vel, jfloatArray life,
    jint count, jint type, jfloat fadeRate,
    jfloat windX, jfloat windY, jfloat windZ) {

    // Pin three distinct, non-aliasing arrays without copying. ART permits
    // holding multiple primitive-critical pins simultaneously as long as the
    // arrays don't overlap and no blocking/object-allocating JNI calls occur in
    // the window — which holds here (only step_particles runs, no JNI calls).
    // The window stays minimal; CheckJNI passes for non-aliasing multi-pins.
    auto* pPos  = static_cast<float*>(env->GetPrimitiveArrayCritical(pos,  nullptr));
    auto* pVel  = static_cast<float*>(env->GetPrimitiveArrayCritical(vel,  nullptr));
    auto* pLife = static_cast<float*>(env->GetPrimitiveArrayCritical(life, nullptr));

    jint alive = -1;
    if (pPos && pVel && pLife) {
        alive = step_particles(pPos, pVel, pLife, count, type, fadeRate,
                               windX, windY, windZ);
    }

    // Release in reverse pin order. life is read-only -> JNI_ABORT skips copy-back.
    if (pLife) env->ReleasePrimitiveArrayCritical(life, pLife, JNI_ABORT);
    if (pVel)  env->ReleasePrimitiveArrayCritical(vel,  pVel,  0);
    if (pPos)  env->ReleasePrimitiveArrayCritical(pos,  pPos,  0);

    return alive;
}

}  // extern "C"
