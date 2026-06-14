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
}
