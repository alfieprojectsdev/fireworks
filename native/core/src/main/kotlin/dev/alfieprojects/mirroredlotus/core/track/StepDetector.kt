package dev.alfieprojects.mirroredlotus.core.track

import kotlin.math.sqrt

/**
 * Footsteps from the raw accelerometer, for pedestrian dead reckoning.
 *
 * Walking with a phone held up bounces it once per step, ~1-3 m/s^2 at
 * 1.5-2.5 steps a second. That bounce shows up in the magnitude of the
 * acceleration, which (unlike any single axis) doesn't care how the phone is
 * tilted. This low-passes |a| - g and counts an upward threshold crossing as a
 * step, but only once the signal has dipped below a valley threshold since the
 * last step and a minimum interval has passed - hysteresis that stops noise,
 * hand tremor and panning the phone from counting as steps.
 *
 * Uses the accelerometer rather than Android's step detector, which needs the
 * ACTIVITY_RECOGNITION runtime permission and reports late. Single-threaded.
 */
class StepDetector(
    private val peakThreshold: Double = 1.0,     // m/s^2 above gravity
    private val valleyThreshold: Double = -0.4,  // m/s^2 below gravity
    private val minIntervalNanos: Long = 280_000_000L, // at most ~3.5 steps/s
    private val smoothing: Double = 0.3,         // EMA coefficient at ~50 Hz: ~3 Hz corner
) {
    private var filtered = 0.0
    private var primed = false
    private var armed = true
    private var lastStepNanos = Long.MIN_VALUE / 2

    var steps = 0L
        private set

    /** One accelerometer sample (m/s^2, gravity included). Returns true on a step. */
    fun onSample(tNanos: Long, ax: Double, ay: Double, az: Double): Boolean {
        val dynamic = sqrt(ax * ax + ay * ay + az * az) - GRAVITY
        if (!primed) {
            filtered = dynamic
            primed = true
            return false
        }
        val previous = filtered
        filtered += smoothing * (dynamic - filtered)
        if (filtered < valleyThreshold) armed = true
        if (armed && previous < peakThreshold && filtered >= peakThreshold && tNanos - lastStepNanos >= minIntervalNanos) {
            armed = false
            lastStepNanos = tNanos
            steps++
            return true
        }
        return false
    }

    fun reset() {
        primed = false
        armed = true
        lastStepNanos = Long.MIN_VALUE / 2
    }

    private companion object {
        const val GRAVITY = 9.80665
    }
}
