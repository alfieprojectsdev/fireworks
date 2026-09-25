package dev.alfieprojects.mirroredlotus.core.ui

/**
 * N taps, each within [windowMs] of the previous one. Without the window,
 * three stray taps hours apart would count as a "triple-tap" and drop the
 * wearer into test mode mid-approach (the web build learned this).
 */
class MultiTapDetector(private val taps: Int = 3, private val windowMs: Long = 600) {
    enum class Result { TICK, TRIGGERED }

    private var count = 0
    private var lastTapMs = Long.MIN_VALUE

    fun onTap(nowMs: Long): Result {
        if (lastTapMs == Long.MIN_VALUE || nowMs - lastTapMs > windowMs) count = 0
        lastTapMs = nowMs
        count++
        if (count >= taps) {
            count = 0
            lastTapMs = Long.MIN_VALUE
            return Result.TRIGGERED
        }
        return Result.TICK
    }
}
