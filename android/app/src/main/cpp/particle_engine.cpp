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

    // Pin without copy. Safe: no other JNI calls happen between pin and release,
    // so the GC-disabled critical window stays minimal.
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
