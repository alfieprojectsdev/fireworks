package dev.alfieprojects.mirroredlotus.core.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical-earth geodesy, ported verbatim from `public/js/geo.js` so both
 * builds put the monument in the same place. A 6371 km sphere is off by at most
 * ~0.5% against the ellipsoid - under 10 m at the 1.8 km trigger ring, which is
 * inside GPS noise anyway.
 */
object Geo {
    const val EARTH_RADIUS_M = 6_371_000.0
    private const val DEG = Math.PI / 180.0

    /** Haversine great-circle distance in metres. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * DEG
        val dLng = (lng2 - lng1) * DEG
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dLng / 2).let { it * it }
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, radians clockwise from true north, in `(-PI, PI]`. */
    fun bearingRad(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLng = (lng2 - lng1) * DEG
        val y = sin(dLng) * cos(lat2 * DEG)
        val x = cos(lat1 * DEG) * sin(lat2 * DEG) - sin(lat1 * DEG) * cos(lat2 * DEG) * cos(dLng)
        return atan2(y, x)
    }

    /** Bearing in degrees normalised to `[0, 360)`, as the HUD and recorder print it. */
    fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double =
        normaliseDeg(Math.toDegrees(bearingRad(lat1, lng1, lat2, lng2)))

    fun normaliseDeg(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** Destination point given a start, an initial bearing (radians) and a distance. */
    fun destination(lat: Double, lng: Double, bearingRad: Double, distanceM: Double): LatLng {
        val d = distanceM / EARTH_RADIUS_M
        val lat1 = lat * DEG
        val lng1 = lng * DEG
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(bearingRad))
        val lng2 = lng1 + atan2(sin(bearingRad) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLng(lat2 / DEG, lng2 / DEG)
    }
}

data class LatLng(val lat: Double, val lng: Double)

/**
 * Where something at a geographic position sits in the scene, relative to the
 * wearer at the origin. Scene axes follow the web build: X = east, Y = up,
 * Z = south (so north is -Z), which is also Three.js's convention.
 */
data class SceneOffset(val x: Double, val z: Double, val distanceM: Double, val bearingRad: Double)

object SceneGeo {
    fun offset(userLat: Double, userLng: Double, targetLat: Double, targetLng: Double): SceneOffset {
        val dist = Geo.distanceM(userLat, userLng, targetLat, targetLng)
        val bearing = Geo.bearingRad(userLat, userLng, targetLat, targetLng)
        return SceneOffset(dist * sin(bearing), -dist * cos(bearing), dist, bearing)
    }
}
