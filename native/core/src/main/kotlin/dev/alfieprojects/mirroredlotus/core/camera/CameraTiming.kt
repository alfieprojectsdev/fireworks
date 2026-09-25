package dev.alfieprojects.mirroredlotus.core.camera

/**
 * Puts a camera frame on the sensor clock, so the pose can be sampled at the
 * instant the frame saw the world.
 *
 * Camera2 stamps a frame with the *start* of exposure of its first row. The
 * picture the eye reads is centred half an exposure and half a rolling-shutter
 * readout later: on the A07 that is 27 ms of skew and up to 33 ms of exposure
 * at 30 fps (docs/DEVICE-A07.md in stable-still), i.e. ~30 ms - two degrees of
 * error at a brisk 60 deg/s pan if ignored.
 */
object CameraTiming {
    /** Returned when a timestamp can't be trusted; callers fall back to the newest pose. */
    const val NO_TIME = Long.MIN_VALUE

    /** How far in the past a frame may plausibly be when it is drawn. */
    const val MAX_AGE_NANOS = 500_000_000L
    /** Tolerance for "from the future" (clock skew between readers). */
    const val MAX_LEAD_NANOS = 10_000_000L

    enum class Base {
        /** `elapsedRealtimeNanos` - the sensors' clock. Camera2 REALTIME, or TIMESTAMP_BASE_REALTIME. */
        REALTIME,
        /** Camera2 UNKNOWN source: in practice CLOCK_MONOTONIC (`System.nanoTime`). */
        MONOTONIC,
    }

    /**
     * Mid-exposure time of the frame stamped [frameNanos], in the sensor clock,
     * or [NO_TIME] if the result is implausible (then the timestamp base was
     * not what the HAL claimed, and syncing to it would do more harm than good).
     *
     * [realtimeNow] and [monotonicNow] must be read together, just before the call.
     */
    fun frameCentreRealtime(
        frameNanos: Long,
        base: Base,
        exposureNanos: Long,
        rollingShutterSkewNanos: Long,
        realtimeNow: Long,
        monotonicNow: Long,
    ): Long {
        if (frameNanos <= 0) return NO_TIME
        val start = when (base) {
            Base.REALTIME -> frameNanos
            Base.MONOTONIC -> frameNanos + (realtimeNow - monotonicNow)
        }
        val centre = start + maxOf(0L, exposureNanos) / 2 + maxOf(0L, rollingShutterSkewNanos) / 2
        val age = realtimeNow - centre
        if (age > MAX_AGE_NANOS || age < -MAX_LEAD_NANOS) return NO_TIME
        return centre
    }
}
