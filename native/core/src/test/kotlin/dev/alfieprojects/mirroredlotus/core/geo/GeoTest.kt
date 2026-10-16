package dev.alfieprojects.mirroredlotus.core.geo

import dev.alfieprojects.mirroredlotus.core.site.Sites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun `the two sites are about 1_94 km apart, as the trigger-overlap reasoning assumes`() {
        val d = Geo.distanceM(Sites.CHURCH.lat, Sites.CHURCH.lng, Sites.ORIGIN.lat, Sites.ORIGIN.lng)
        assertEquals(1940.0, d, 15.0)
    }

    @Test
    fun `one degree of latitude is about 111_2 km on the 6371 km sphere`() {
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 1.0)
    }

    @Test
    fun `bearings point the right way`() {
        val lat = Sites.CHURCH.lat
        val lng = Sites.CHURCH.lng
        assertEquals(0.0, Geo.bearingDeg(lat, lng, lat + 0.01, lng), 0.01)
        assertEquals(90.0, Geo.bearingDeg(lat, lng, lat, lng + 0.01), 0.1)
        assertEquals(180.0, Geo.bearingDeg(lat, lng, lat - 0.01, lng), 0.01)
        assertEquals(270.0, Geo.bearingDeg(lat, lng, lat, lng - 0.01), 0.1)
    }

    @Test
    fun `destination inverts distance and bearing`() {
        val start = Sites.CHURCH
        for (bearingDeg in listOf(0.0, 37.0, 135.0, 225.0, 300.0)) {
            val p = Geo.destination(start.lat, start.lng, Math.toRadians(bearingDeg), 1234.0)
            assertEquals(1234.0, Geo.distanceM(start.lat, start.lng, p.lat, p.lng), 0.01)
            assertEquals(bearingDeg, Geo.bearingDeg(start.lat, start.lng, p.lat, p.lng), 0.01)
        }
    }

    @Test
    fun `scene offset puts north at minus Z and east at plus X`() {
        val u = Sites.CHURCH
        val north = SceneGeo.offset(u.lat, u.lng, u.lat + 0.001, u.lng)
        assertEquals(0.0, north.x, 0.01)
        assertTrue(north.z < -100)
        assertEquals(north.distanceM, -north.z, 0.01)

        val east = SceneGeo.offset(u.lat, u.lng, u.lat, u.lng + 0.001)
        assertTrue(east.x > 100)
        assertEquals(0.0, east.z, 0.5)
    }

    @Test
    fun `normaliseDeg wraps negatives and overflow`() {
        assertEquals(350.0, Geo.normaliseDeg(-10.0), 1e-9)
        assertEquals(10.0, Geo.normaliseDeg(370.0), 1e-9)
        assertEquals(0.0, Geo.normaliseDeg(360.0), 1e-9)
    }
}
