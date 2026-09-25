package dev.alfieprojects.mirroredlotus.core.story

import kotlin.math.max

/**
 * The distance-driven staging from `scene.js` `_animate()`, as pure functions of
 * the wearer's distance to the active site. Every band edge is covered by
 * `ActsTest`, per the repo's "enumerate every boundary" rule.
 *
 * Note the marquee and stats targets deliberately keep the web build's
 * overshoot: they are only floored at 0, so inside 150 m (marquee) and 40 m
 * (stats) the target climbs past 1. The renderer clamps effective alpha the
 * way WebGL did, so the overshoot brightens the soft glow halos rather than
 * the glyph cores - a look the web build had, whether or not by design.
 */
object Acts {
    // ACT 0: beacon fades in 1800 -> 1500 m, holds to 800 m, fades out 800 -> 650 m.
    const val BEACON_OUTER_M = 1800.0
    const val BEACON_FULL_M = 1500.0
    const val BEACON_FADE_OUT_M = 800.0
    const val BEACON_GONE_M = 650.0

    // ACT 1: fireworks at or inside 800 m (suppressed further out to save GPU).
    const val FIREWORKS_M = 800.0

    // ACT 2: crown marquee materialises 400 -> 150 m.
    const val MARQUEE_START_M = 400.0
    const val MARQUEE_FULL_M = 150.0

    // ACT 3: stat pylons emerge 100 -> 40 m.
    const val STATS_START_M = 100.0
    const val STATS_FULL_M = 40.0

    /** Distance under which the 60 s countdown to the personal message arms. */
    const val MESSAGE_ARM_M = 100.0

    fun beaconTarget(d: Double): Double {
        if (d > BEACON_OUTER_M || d <= BEACON_GONE_M) return 0.0
        return when {
            d > BEACON_FULL_M -> max(0.0, 1 - (d - BEACON_FULL_M) / (BEACON_OUTER_M - BEACON_FULL_M))
            d < BEACON_FADE_OUT_M -> max(0.0, (d - BEACON_GONE_M) / (BEACON_FADE_OUT_M - BEACON_GONE_M))
            else -> 1.0
        }
    }

    fun fireworksOn(d: Double): Boolean = d <= FIREWORKS_M

    fun marqueeTarget(d: Double): Double =
        if (d <= MARQUEE_START_M) max(0.0, 1 - (d - MARQUEE_FULL_M) / (MARQUEE_START_M - MARQUEE_FULL_M)) else 0.0

    fun statsTarget(d: Double): Double =
        if (d <= STATS_START_M) max(0.0, 1 - (d - STATS_FULL_M) / (STATS_START_M - STATS_FULL_M)) else 0.0

    /** The HUD's stage name (`_stageLabel` in sensors.js). */
    fun stageLabel(d: Double): String = when {
        d > 1800 -> "approaching"
        d > 1500 -> "beacon_fading_in"
        d > 800 -> "beacon_full"
        d > 650 -> "beacon_fading_out"
        d > 400 -> "fireworks_only"
        d > 150 -> "marquee_fading_in"
        d > 100 -> "marquee_full"
        d > 40 -> "stats_fading_in"
        else -> "full_experience"
    }
}

/** One-shot milestones the recorder logs, with the distance each is designed to fire at. */
enum class ActEvent(val id: String, val expectedM: Int) {
    BEACON_ON("beacon_on", 1800),
    FIREWORKS_ON("fireworks_on", 800),
    MARQUEE_ON("marquee_on", 400),
    STATS_ON("stats_on", 100),
}
