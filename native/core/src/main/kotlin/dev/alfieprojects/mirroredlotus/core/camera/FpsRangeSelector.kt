package dev.alfieprojects.mirroredlotus.core.camera

/**
 * Chooses `CONTROL_AE_TARGET_FPS_RANGE`.
 *
 * This experience is used at night, where auto-exposure would happily stretch
 * each frame to 66 ms inside a [15, 30] range. Exposure time is latency the
 * overlay has to wait out, and blur the eye reads as lag, so a *fixed* rate is
 * preferred: [30, 30] caps exposure at 33 ms and makes frame pacing steady.
 * Faster fixed rates are not requested - at night they only buy darkness.
 */
object FpsRangeSelector {
    data class Range(val lower: Int, val upper: Int)

    fun choose(available: List<Range>, target: Int = 30): Range? {
        available.firstOrNull { it.lower == target && it.upper == target }?.let { return it }
        // Otherwise the range reaching the target with the highest floor.
        return available.filter { it.upper >= target && it.lower <= target }.maxByOrNull { it.lower }
            ?: available.maxByOrNull { it.upper * 1000 + it.lower }
    }
}
