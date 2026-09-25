package dev.alfieprojects.mirroredlotus.core.sim

import dev.alfieprojects.mirroredlotus.core.geo.Geo
import dev.alfieprojects.mirroredlotus.core.geo.LatLng
import dev.alfieprojects.mirroredlotus.core.site.Site

/**
 * A straight walk toward a site, for exercising every act at a desk: the
 * native build replaces real GPS with this when launched with `--es simulate`.
 * The walker starts [startDistanceM] out along [fromBearingDeg] (measured at
 * the site, pointing toward the walker), closes in at [speedMps], and stops
 * [stopDistanceM] short of the centre.
 */
class ApproachRoute(
    private val site: Site,
    private val startDistanceM: Double = 2000.0,
    private val fromBearingDeg: Double = 225.0,
    private val speedMps: Double = 1.4,
    private val stopDistanceM: Double = 0.0,
) {
    fun distanceAt(elapsedSeconds: Double): Double =
        maxOf(stopDistanceM, startDistanceM - speedMps * maxOf(0.0, elapsedSeconds))

    fun positionAt(elapsedSeconds: Double): LatLng =
        Geo.destination(site.lat, site.lng, Math.toRadians(fromBearingDeg), distanceAt(elapsedSeconds))

    fun arrived(elapsedSeconds: Double): Boolean = distanceAt(elapsedSeconds) <= stopDistanceM
}
