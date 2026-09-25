package dev.alfieprojects.mirroredlotus.core.track

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Whether the phone is being swung, for [StepDetector]'s rotation gate.
 *
 * Fed the fused orientation stream, it measures the angular rate between
 * consecutive samples. Whipping the phone round at arm's length to follow a
 * burst puts ω²r of centripetal acceleration into |a|: 2 rad/s at 0.6 m is
 * 2.4 m/s², more than a footstep's bounce. Walking with the phone held up
 * turns it well under a radian a second.
 *
 * The gate stays shut for [holdNanos] after the rate drops, because the step
 * detector's low-pass puts its threshold crossing a little after the swing
 * that caused it. Allocation-free; single-threaded (the sensor thread).
 */
class SwingGate(
    private val thresholdRadPerS: Double = 1.5,
    private val holdNanos: Long = 300_000_000L,
) {
    private val prev = DoubleArray(4)
    private var prevNanos = Long.MIN_VALUE
    private var lastFastNanos = Long.MIN_VALUE / 2

    /** Latest measured angular rate, rad/s. */
    var rateRadPerS = 0.0
        private set

    /** One orientation sample, `[w, x, y, z]`. */
    fun onOrientation(tNanos: Long, q: DoubleArray) {
        if (prevNanos != Long.MIN_VALUE) {
            val dt = (tNanos - prevNanos) * 1e-9
            // A gap (sensor paused, thread stalled) says nothing about the rate.
            if (dt > 0 && dt < MAX_GAP_S) {
                rateRadPerS = angleBetween(prev, q) / dt
                if (rateRadPerS > thresholdRadPerS) lastFastNanos = tNanos
            }
        }
        prev[0] = q[0]; prev[1] = q[1]; prev[2] = q[2]; prev[3] = q[3]
        prevNanos = tNanos
    }

    fun swinging(tNanos: Long): Boolean = tNanos - lastFastNanos <= holdNanos

    fun reset() {
        prevNanos = Long.MIN_VALUE
        lastFastNanos = Long.MIN_VALUE / 2
        rateRadPerS = 0.0
    }

    private companion object {
        const val MAX_GAP_S = 0.25

        /**
         * Angle of conj(p)·q, via atan2 of its vector part over its scalar part.
         * At 200 Hz the step is milliradians, where acos would round to zero.
         */
        fun angleBetween(p: DoubleArray, q: DoubleArray): Double {
            val w = p[0] * q[0] + p[1] * q[1] + p[2] * q[2] + p[3] * q[3]
            val x = p[0] * q[1] - p[1] * q[0] - p[2] * q[3] + p[3] * q[2]
            val y = p[0] * q[2] + p[1] * q[3] - p[2] * q[0] - p[3] * q[1]
            val z = p[0] * q[3] - p[1] * q[2] + p[2] * q[1] - p[3] * q[0]
            return 2.0 * atan2(sqrt(x * x + y * y + z * z), abs(w))
        }
    }
}
