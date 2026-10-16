package dev.alfieprojects.mirroredlotus.core.ui

/**
 * The top-left heading banner. Shows "calibrating" when the experience starts,
 * "locked" as soon as a trustworthy absolute heading arrives, and never
 * lingers: after [timeoutMs] it settles on "active" (heading works but was
 * never confirmed good), "needs calibration" (the compass reported itself
 * unreliable - the wearer should wave the phone in a figure-8), or simply
 * fades if no orientation arrived at all.
 */
class CompassBanner(
    private val timeoutMs: Long = 8000,
    private val lingerMs: Long = 2500,
    private val calibrationHintMs: Long = 5000,
) {
    enum class State { HIDDEN, CALIBRATING, LOCKED, ACTIVE, NEEDS_CALIBRATION }

    private var state = State.HIDDEN
    private var startedAtMs = 0L
    private var hideAtMs = Long.MAX_VALUE
    private var anyOrientation = false
    private var lastAbsoluteGood: Boolean? = null

    fun start(nowMs: Long) {
        state = State.CALIBRATING
        startedAtMs = nowMs
        hideAtMs = Long.MAX_VALUE
    }

    /**
     * One orientation sample. [absolute] is true for magnetometer-anchored
     * samples; [good] is [HeadingQuality.isGood] for those.
     */
    fun onOrientation(nowMs: Long, absolute: Boolean, good: Boolean) {
        anyOrientation = true
        if (absolute) lastAbsoluteGood = good
        if (state == State.CALIBRATING && absolute && good) settle(State.LOCKED, nowMs, lingerMs)
    }

    fun update(nowMs: Long): State {
        if (state == State.CALIBRATING && nowMs - startedAtMs >= timeoutMs) {
            when {
                !anyOrientation -> state = State.HIDDEN
                lastAbsoluteGood == false -> settle(State.NEEDS_CALIBRATION, nowMs, calibrationHintMs)
                else -> settle(State.ACTIVE, nowMs, lingerMs)
            }
        }
        if (state != State.HIDDEN && state != State.CALIBRATING && nowMs >= hideAtMs) state = State.HIDDEN
        return state
    }

    private fun settle(to: State, nowMs: Long, visibleMs: Long) {
        state = to
        hideAtMs = nowMs + visibleMs
    }
}

/** Whether a rotation-vector sample's heading is trustworthy enough to call "locked". */
object HeadingQuality {
    /** Android's SENSOR_STATUS_ACCURACY_MEDIUM. */
    const val STATUS_MEDIUM = 2
    /** ~20 degrees: past this the monument visibly sits off the church. */
    const val MAX_ACCURACY_RAD = 0.35f

    /**
     * [status] is the last `onAccuracyChanged` value (-1 if never reported);
     * [headingAccuracyRad] is rotation-vector `values[4]` (-1 or NaN when the
     * device doesn't estimate it). With neither available the sample counts
     * as good, as the web build locked on its first absolute event.
     */
    fun isGood(status: Int, headingAccuracyRad: Float): Boolean {
        val hasEstimate = !headingAccuracyRad.isNaN() && headingAccuracyRad > 0f
        if (hasEstimate) return headingAccuracyRad <= MAX_ACCURACY_RAD
        if (status >= 0) return status >= STATUS_MEDIUM
        return true
    }
}
