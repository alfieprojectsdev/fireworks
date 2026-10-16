package dev.alfieprojects.mirroredlotus.core.site

import dev.alfieprojects.mirroredlotus.core.geo.Geo
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One place the experience can happen. [anchor] is the local date-time the stat
 * pylons and the personal message count from.
 */
data class Site(
    val id: String,
    val lat: Double,
    val lng: Double,
    val anchor: LocalDateTime,
) {
    /**
     * The crown's text on [date]: "Anniversary" through the anchor's month,
     * "Monthsary" the rest of the year, at both sites. Each web-build site had
     * one fixed line; Alfie chose the date rule on 16 Oct 2026 so the greeting
     * is always true for the day it's seen.
     */
    fun marqueeOn(date: LocalDate): String =
        if (date.month == anchor.month) Sites.ANNIVERSARY_MARQUEE else Sites.MONTHSARY_MARQUEE
}

object Sites {
    /** Trigger radius per site. The two sites are ~1.94 km apart, so the rings overlap slightly. */
    const val TRIGGER_RADIUS_M = 1800.0

    const val ANNIVERSARY_MARQUEE = "Happy Anniversary, Bhaze!"
    const val MONTHSARY_MARQUEE = "Happy Monthsary, Bhaze!"

    val CHURCH = Site(
        id = "church",
        lat = 14.658888478751235,
        lng = 121.071173166497,
        // Wedding, 10am ceremony - the Hours stat anchors here.
        anchor = LocalDateTime.of(2008, 5, 15, 10, 0, 0),
    )

    val ORIGIN = Site(
        id = "origin",
        lat = 14.651726103123695,
        lng = 121.05472805488795,
        // When Alfie and Bhazel became a couple: the "monthsary" site.
        anchor = LocalDateTime.of(2005, 10, 15, 0, 0, 0),
    )

    /** Order matters: test mode previews the first entry, as in the web build. */
    val ALL: List<Site> = listOf(CHURCH, ORIGIN)

    fun byId(id: String): Site? = ALL.firstOrNull { it.id == id }
}

data class NearestSite(val site: Site, val distanceM: Double, val bearingDeg: Double)

/** Port of `nearestSite()` in scene.js. */
fun nearestSite(lat: Double, lng: Double, sites: List<Site> = Sites.ALL): NearestSite {
    var best = sites.first()
    var bestD = Double.POSITIVE_INFINITY
    for (s in sites) {
        val d = Geo.distanceM(lat, lng, s.lat, s.lng)
        if (d < bestD) {
            bestD = d; best = s
        }
    }
    return NearestSite(best, bestD, Geo.bearingDeg(lat, lng, best.lat, best.lng))
}

/**
 * What a pre-trigger fix means: either the experience starts at a site, or the
 * splash should show how far there is still to go. Nearest-site plus the
 * session lock (enforced by the caller) guarantee exactly one site triggers
 * even where the two rings overlap.
 */
sealed interface TriggerDecision {
    val nearest: NearestSite

    data class Trigger(override val nearest: NearestSite) : TriggerDecision
    data class Approach(override val nearest: NearestSite) : TriggerDecision
}

object TriggerPolicy {
    /** Inclusive: a wearer exactly on the ring triggers. */
    fun inRange(distanceM: Double): Boolean = distanceM <= Sites.TRIGGER_RADIUS_M

    fun evaluate(lat: Double, lng: Double, sites: List<Site> = Sites.ALL): TriggerDecision {
        val near = nearestSite(lat, lng, sites)
        return if (inRange(near.distanceM)) {
            TriggerDecision.Trigger(near)
        } else {
            TriggerDecision.Approach(near)
        }
    }
}
