#ifndef ANNIVERSARY_PARTICLE_ENGINE_H
#define ANNIVERSARY_PARTICLE_ENGINE_H

#include <jni.h>

// JNI surface for the native particle engine. Pure physics lives in
// particle_step.h (no jni.h) so it stays host-testable.

extern "C" {

JNIEXPORT jint JNICALL
Java_com_alfie_anniversary_ParticleEngine_nativeVersion(JNIEnv* env, jclass cls);

JNIEXPORT jint JNICALL
Java_com_alfie_anniversary_ParticleEngine_nativeStep(
    JNIEnv* env, jclass cls,
    jfloatArray pos, jfloatArray vel, jfloatArray life,
    jint count, jint type, jfloat fadeRate,
    jfloat windX, jfloat windY, jfloat windZ);

}  // extern "C"

#endif  // ANNIVERSARY_PARTICLE_ENGINE_H
