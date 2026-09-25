package dev.alfieprojects.mirroredlotus.core.site

import kotlin.math.hypot

/**
 * Surveyed reference points around the church with their expected distance to
 * it, so the HUD can show how far the live distance is from the survey
 * (`sensors.js` CHECKPOINTS). Church site only.
 */
data class Checkpoint(val name: String, val lat: Double, val lng: Double, val expectedM: Int)

object Checkpoints {
    val CHURCH: List<Checkpoint> = listOf(
        Checkpoint("PHIVOLCS", 14.652088, 121.058387, 1571),
        Checkpoint("600m-N", 14.664278, 121.071173, 600),
        Checkpoint("600m-E", 14.658888, 121.076744, 600),
        Checkpoint("600m-S", 14.653498, 121.071173, 600),
        Checkpoint("600m-W", 14.658888, 121.065602, 600),
    )

    /**
     * Nearest by naive degree distance, exactly as the web HUD does it. At
     * 14.66 degrees N a degree of longitude is 3% shorter than one of latitude,
     * which is too small to change which of five spread-out points is closest.
     */
    fun nearest(lat: Double, lng: Double, points: List<Checkpoint> = CHURCH): Checkpoint =
        points.minBy { hypot(lat - it.lat, lng - it.lng) }
}
