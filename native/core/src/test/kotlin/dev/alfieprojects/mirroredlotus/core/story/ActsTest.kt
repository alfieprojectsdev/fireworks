package dev.alfieprojects.mirroredlotus.core.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every band edge of every distance function, per the repo rule that grew out
 * of the missing 40-100 m `stats_fading_in` band.
 */
class ActsTest {

    @Test
    fun `beacon envelope at every edge`() {
        assertEquals(0.0, Acts.beaconTarget(1800.1), 0.0)
        assertEquals(0.0, Acts.beaconTarget(1800.0), 1e-12)
        assertEquals(0.5, Acts.beaconTarget(1650.0), 1e-12)
        assertEquals(1.0, Acts.beaconTarget(1500.0), 1e-12)
        assertEquals(1.0, Acts.beaconTarget(1000.0), 0.0)
        assertEquals(1.0, Acts.beaconTarget(800.0), 0.0)
        assertEquals(0.5, Acts.beaconTarget(725.0), 1e-12)
        assertEquals(1 / 150.0, Acts.beaconTarget(651.0), 1e-12)
        assertEquals(0.0, Acts.beaconTarget(650.0), 0.0)
        assertEquals(0.0, Acts.beaconTarget(0.0), 0.0)
    }

    @Test
    fun `fireworks start at 800 m inclusive`() {
        assertFalse(Acts.fireworksOn(800.01))
        assertTrue(Acts.fireworksOn(800.0))
        assertTrue(Acts.fireworksOn(0.0))
    }

    @Test
    fun `marquee fades in from 400 to 150 m and keeps the web overshoot inside`() {
        assertEquals(0.0, Acts.marqueeTarget(400.1), 0.0)
        assertEquals(0.0, Acts.marqueeTarget(400.0), 1e-12)
        assertEquals(0.5, Acts.marqueeTarget(275.0), 1e-12)
        assertEquals(1.0, Acts.marqueeTarget(150.0), 1e-12)
        assertEquals(1.6, Acts.marqueeTarget(0.0), 1e-12)
    }

    @Test
    fun `stats emerge from 100 to 40 m and keep the web overshoot inside`() {
        assertEquals(0.0, Acts.statsTarget(100.1), 0.0)
        assertEquals(0.0, Acts.statsTarget(100.0), 1e-12)
        assertEquals(0.5, Acts.statsTarget(70.0), 1e-12)
        assertEquals(1.0, Acts.statsTarget(40.0), 1e-12)
        assertEquals(5 / 3.0, Acts.statsTarget(0.0), 1e-12)
    }

    @Test
    fun `stage labels cover every band with no gap`() {
        val expected = listOf(
            5000.0 to "approaching", 1800.1 to "approaching",
            1800.0 to "beacon_fading_in", 1500.1 to "beacon_fading_in",
            1500.0 to "beacon_full", 800.1 to "beacon_full",
            800.0 to "beacon_fading_out", 650.1 to "beacon_fading_out",
            650.0 to "fireworks_only", 400.1 to "fireworks_only",
            400.0 to "marquee_fading_in", 150.1 to "marquee_fading_in",
            150.0 to "marquee_full", 100.1 to "marquee_full",
            100.0 to "stats_fading_in", 40.1 to "stats_fading_in",
            40.0 to "full_experience", 0.0 to "full_experience",
        )
        for ((d, label) in expected) assertEquals("at $d m", label, Acts.stageLabel(d))
    }

    @Test
    fun `act events carry the recorder's expected distances`() {
        assertEquals(listOf(1800, 800, 400, 100), ActEvent.entries.map { it.expectedM })
        assertEquals(listOf("beacon_on", "fireworks_on", "marquee_on", "stats_on"), ActEvent.entries.map { it.id })
    }
}
