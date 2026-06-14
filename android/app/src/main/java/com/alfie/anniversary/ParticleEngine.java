package com.alfie.anniversary;

import android.util.Log;

/**
 * JNI bridge to the native particle physics engine.
 *
 * Phase 1a: scaffold only — {@link #nativeVersion()} confirms the shared
 * library loaded and the JNI symbol table resolved. Real spawn/step entry
 * points land in Phase 1b.
 */
public final class ParticleEngine {

    private static final String TAG = "ParticleEngine";
    private static boolean sLoaded = false;

    static {
        try {
            System.loadLibrary("anniversary_native");
            sLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load anniversary_native", e);
        }
    }

    private ParticleEngine() {}

    /** @return true if the native shared library loaded successfully. */
    public static boolean isLoaded() {
        return sLoaded;
    }

    /** Native ABI version sentinel; throws if the library did not load. */
    public static native int nativeVersion();

    /**
     * Advances one firework's particles by a single frame, in place.
     *
     * SoA pools (see particle_step.h): {@code pos} is head+tail interleaved
     * (stride 6), {@code vel} is velocity (stride 3), {@code life} is the
     * per-particle lifespan multiplier (read-only). {@code fadeRate} is
     * {@code firework.life / firework.maxLife}. Returns the count of
     * still-alive particles (0 => the firework can be retired).
     *
     * Not yet called from JS — Phase 1c wires this into fireworks.js. The
     * declaration exists now so the JNI symbol in particle_engine.cpp resolves.
     */
    public static native int nativeStep(
        float[] pos, float[] vel, float[] life,
        int count, int type, float fadeRate,
        float windX, float windY, float windZ);
}
