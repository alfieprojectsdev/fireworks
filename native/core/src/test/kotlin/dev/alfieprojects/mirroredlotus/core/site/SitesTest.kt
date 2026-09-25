package dev.alfieprojects.mirroredlotus.core.site

import dev.alfieprojects.mirroredlotus.core.geo.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SitesTest {

    private fun pointFrom(site: Site, bearingDeg: Double, metres: Double) =
        Geo.destination(site.lat, site.lng, Math.toRadians(bearingDeg), metres)

    @Test
    fun `constants match the web build`() {
        assertEquals(14.658888478751235, Sites.CHURCH.lat, 0.0)
        assertEquals(121.071173166497, Sites.CHURCH.lng, 0.0)
        assertEquals(14.651726103123695, Sites.ORIGIN.lat, 0.0)
        assertEquals(121.05472805488795, Sites.ORIGIN.lng, 0.0)
        assertEquals("2008-05-15T10:00", Sites.CHURCH.anchor.toString())
        assertEquals("2005-10-15T00:00", Sites.ORIGIN.anchor.toString())
        assertEquals(1800.0, Sites.TRIGGER_RADIUS_M, 0.0)
        assertSame(Sites.CHURCH, Sites.ALL.first())
    }

    @Test
    fun `the trigger ring is inclusive at exactly 1800 m`() {
        assertTrue(TriggerPolicy.inRange(1800.0))
        assertTrue(!TriggerPolicy.inRange(Math.nextUp(1800.0)))
    }

    @Test
    fun `positions just inside and just outside the ring decide correctly`() {
        val inside = pointFrom(Sites.CHURCH, 0.0, 1799.9)
        val outside = pointFrom(Sites.CHURCH, 0.0, 1800.5)
        assertTrue(TriggerPolicy.evaluate(inside.lat, inside.lng) is TriggerDecision.Trigger)
        val far = TriggerPolicy.evaluate(outside.lat, outside.lng)
        assertTrue(far is TriggerDecision.Approach)
        assertEquals(1800.5, far.nearest.distanceM, 0.1)
    }

    @Test
    fun `in the overlap the nearer site wins`() {
        // Midway between the two sites, nudged toward the origin.
        val bearing = Geo.bearingRad(Sites.CHURCH.lat, Sites.CHURCH.lng, Sites.ORIGIN.lat, Sites.ORIGIN.lng)
        val p = Geo.destination(Sites.CHURCH.lat, Sites.CHURCH.lng, bearing, 1000.0)
        val decision = TriggerPolicy.evaluate(p.lat, p.lng)
        assertTrue(decision is TriggerDecision.Trigger)
        assertSame(Sites.ORIGIN, decision.nearest.site)
    }

    @Test
    fun `nearest site reports bearing toward the site`() {
        val p = pointFrom(Sites.CHURCH, 180.0, 500.0) // walker is south of the church
        val n = nearestSite(p.lat, p.lng)
        assertSame(Sites.CHURCH, n.site)
        assertEquals(0.0, n.bearingDeg, 0.1) // so the church is due north
    }

    @Test
    fun `checkpoint lookup finds the surveyed point`() {
        val cp = Checkpoints.nearest(14.6641, 121.0712)
        assertEquals("600m-N", cp.name)
        assertEquals("PHIVOLCS", Checkpoints.nearest(14.652, 121.058).name)
    }
}
