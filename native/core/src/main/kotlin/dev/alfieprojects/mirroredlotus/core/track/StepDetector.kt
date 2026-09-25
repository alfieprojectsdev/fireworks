package dev.alfieprojects.mirroredlotus.core.track

import kotlin.math.sqrt

/**
 * Footsteps from the raw accelerometer, for pedestrian dead reckoning.
 *
 * Walking with a phone held up bounces it once per step, ~1-3 m/s^2 at
 * 1.5-2.5 steps a second. That bounce shows up in the magnitude of the
 * acceleration, which (unlike any single axis) doesn't care how the phone is
 * tilted. This low-passes |a| - g and treats an upward threshold crossing as a
 * candidate, but only once the signal has dipped below a valley threshold since
 * the last one and a minimum interval has passed. That hysteresis stops noise
 * and tremor from counting.
 *
 * Two more gates keep a wearer who is standing and watching from "walking":
 * - **Cadence.** A lone candidate is held back. It is credited, with the next
 *   one, only if that next one arrives within [maxGapNanos]: walking is a
 *   rhythm, a jolt isn't.
 * - **Rotation.** While the phone is being swung (following a burst across the
 *   sky), candidates are discarded. Whipping a phone round at arm's length puts
 *   metres per second squared of centripetal acceleration into |a|.
 *
 * Uses the accelerometer rather than Android's step detector, which needs the
 * ACTIVITY_RECOGNITION runtime permission and reports late. Single-threaded.
 */
class StepDetector(
    private val peakThreshold: Double = 1.0,     // m/s^2 above gravity
    private val valleyThreshold: Double = -0.4,  // m/s^2 below gravity
    private val minIntervalNanos: Long = 280_000_000L, // at most ~3.5 steps/s
    private val maxGapNanos: Long = 1_200_000_000L,    // slower than this is not a walking rhythm
    private val smoothing: Double = 0.3,         // EMA coefficient at ~50 Hz: ~3 Hz corner
) {
    private var filtered = 0.0
    private var primed = false
    private var armed = true
    private var lastCandidateNanos = Long.MIN_VALUE / 2
    private var pending = false

    /** Steps credited so far. */
    var steps = 0L
        private set

    /**
     * One accelerometer sample (m/s^2, gravity included). [rotating] is true while
     * the phone is being swung fast. Returns the number of steps to credit now:
     * 0, 1, or 2 (a held-back first step confirmed together with the second).
     */
    fun onSample(tNanos: Long, ax: Double, ay: Double, az: Double, rotating: Boolean = false): Int {
        val dynamic = sqrt(ax * ax + ay * ay + az * az) - GRAVITY
        if (!primed) {
            filtered = dynamic
            primed = true
            return 0
        }
        val previous = filtered
        filtered += smoothing * (dynamic - filtered)
        if (filtered < valleyThreshold) armed = true
        val candidate = armed && previous < peakThreshold && filtered >= peakThreshold &&
            tNanos - lastCandidateNanos >= minIntervalNanos
        if (!candidate) return 0
        armed = false
        if (rotating) {
            pending = false // swinging the phone breaks any rhythm
            return 0
        }
        val inRhythm = tNanos - lastCandidateNanos <= maxGapNanos
        lastCandidateNanos = tNanos
        if (!inRhythm) {
            pending = true
            return 0
        }
        val credit = if (pending) 2 else 1
        pending = false
        steps += credit
        return credit
    }

    fun reset() {
        primed = false
        armed = true
        pending = false
        lastCandidateNanos = Long.MIN_VALUE / 2
    }

    private companion object {
        const val GRAVITY = 9.80665
    }
}
