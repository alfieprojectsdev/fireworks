package dev.alfieprojects.mirroredlotus.core.sim

import dev.alfieprojects.mirroredlotus.core.geo.Geo
import dev.alfieprojects.mirroredlotus.core.site.Sites
import dev.alfieprojects.mirroredlotus.core.telemetry.Ema
import dev.alfieprojects.mirroredlotus.core.telemetry.RateMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApproachRouteTest {

    @Test
    fun `walker closes in along the chosen bearing and stops`() {
        val s = Sites.CHURCH
        val route = ApproachRoute(s, startDistanceM = 2000.0, fromBearingDeg = 90.0, speedMps = 10.0, stopDistanceM = 5.0)
        val p0 = route.positionAt(0.0)
        assertEquals(2000.0, Geo.distanceM(s.lat, s.lng, p0.lat, p0.lng), 0.01)
        assertEquals(270.0, Geo.bearingDeg(p0.lat, p0.lng, s.lat, s.lng), 0.1) // site lies west
        val p1 = route.positionAt(100.0)
        assertEquals(1000.0, Geo.distanceM(s.lat, s.lng, p1.lat, p1.lng), 0.01)
        assertFalse(route.arrived(100.0))
        assertTrue(route.arrived(1000.0))
        assertEquals(5.0, route.distanceAt(1e6), 0.0)
        // What the simulated feed reports as the fix's Doppler velocity.
        assertEquals(270.0, route.headingDeg, 1e-9)
        assertEquals(10.0, route.speedAt(100.0), 0.0)
        assertEquals(0.0, route.speedAt(1000.0), 0.0)
    }

    @Test
    fun `rate meter measures a steady 200 Hz stream`() {
        val m = RateMeter()
        var t = 0L
        repeat(1000) { m.record(t); t += 5_000_000L }
        assertEquals(200.0, m.rateHz(t), 0.5)
        assertEquals(0.0, m.rateHz(t + 5_000_000_000L), 0.0) // stale
    }

    @Test
    fun `ema starts at the first sample`() {
        val e = Ema(0.5)
        assertTrue(e.value.isNaN())
        e.add(10.0); assertEquals(10.0, e.value, 0.0)
        e.add(20.0); assertEquals(15.0, e.value, 0.0)
    }
}
