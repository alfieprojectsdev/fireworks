package dev.alfieprojects.mirroredlotus.core.ui

/**
 * Holds a request that needs a trustworthy heading - test mode places the
 * monument along the camera's heading - until one exists, or [timeoutMs]
 * passes.
 *
 * Launch-intent paths (`--ez testMode true`) run in onCreate, before any
 * sensor sample has arrived, and the first samples carry only the gyro's
 * arbitrary yaw until the compass correction lands. Acting on either would
 * place the monument somewhere the scene then swings away from.
 */
class PoseGate(private val timeoutMs: Long = 2000) {
    private var requestedAtMs = NONE

    val pending: Boolean get() = requestedAtMs != NONE

    /** Records a request; a second one while pending keeps the original deadline. */
    fun request(nowMs: Long) {
        if (requestedAtMs == NONE) requestedAtMs = nowMs
    }

    /** True exactly once: when the heading is ready, or the wait has timed out. */
    fun poll(nowMs: Long, headingReady: Boolean): Boolean {
        if (requestedAtMs == NONE) return false
        if (!headingReady && nowMs - requestedAtMs < timeoutMs) return false
        requestedAtMs = NONE
        return true
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
