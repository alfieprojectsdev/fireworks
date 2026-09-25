package dev.alfieprojects.mirroredlotus.core.perf

import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy.Companion.STATUS_CRITICAL
import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy.Companion.STATUS_LIGHT
import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy.Companion.STATUS_MODERATE
import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy.Companion.STATUS_NONE
import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy.Companion.STATUS_SEVERE
import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy.Companion.STATUS_SHUTDOWN
import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy.Companion.STATUS_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalPolicyTest {

    @Test
    fun `each thermal status maps to its rung`() {
        val p = ThermalPolicy()
        val expected = mapOf(
            STATUS_UNKNOWN to 0, STATUS_NONE to 0, STATUS_LIGHT to 0, STATUS_MODERATE to 1,
            STATUS_SEVERE to 2, STATUS_CRITICAL to 3, STATUS_SHUTDOWN to 3,
        )
        for ((status, level) in expected) {
            p.update(status, Double.NaN)
            assertEquals("status $status", level, p.level)
        }
    }

    @Test
    fun `a cool phone runs the full show, a hot one gives ground`() {
        val p = ThermalPolicy()
        assertEquals(RenderQuality(0, RenderPace.FULL, 1.0, 15), p.quality)
        assertTrue(p.update(STATUS_SEVERE, Double.NaN))
        assertEquals(RenderPace.CAMERA, p.quality.pace)
        assertTrue(p.quality.renderScale < 1.0)
        assertTrue(p.quality.maxLiveFireworks < 15)
        assertFalse("no change, no churn", p.update(STATUS_SEVERE, Double.NaN))
        assertEquals("L2 camera 75% fw 6", p.quality.describe)
    }

    @Test
    fun `the forecast steps down early, and doesn't flap at its threshold`() {
        val p = ThermalPolicy()
        p.update(STATUS_LIGHT, 0.80)
        assertEquals(0, p.level)
        p.update(STATUS_LIGHT, 0.86) // SEVERE is getting close: act before the governor does
        assertEquals(1, p.level)
        for (h in listOf(0.84, 0.86, 0.83, 0.85, 0.80)) {
            p.update(STATUS_LIGHT, h)
            assertEquals("headroom $h", 1, p.level)
        }
        p.update(STATUS_LIGHT, 0.74)
        assertEquals(0, p.level)
        p.update(STATUS_LIGHT, 1.02)
        assertEquals(2, p.level)
        p.update(STATUS_LIGHT, 0.95)
        assertEquals(2, p.level)
        p.update(STATUS_LIGHT, 0.88)
        assertEquals(1, p.level)
    }

    @Test
    fun `status and forecast combine by taking the worse`() {
        val p = ThermalPolicy()
        p.update(STATUS_MODERATE, 1.1)
        assertEquals(2, p.level)
        p.update(STATUS_CRITICAL, 0.5)
        assertEquals(3, p.level)
        // An unknown headroom (API 29, or polled too fast) keeps the last forecast.
        p.update(STATUS_NONE, 0.9)
        assertEquals(1, p.level)
        p.update(STATUS_NONE, Double.NaN)
        assertEquals(1, p.level)
    }

    @Test
    fun `nothing renders behind the splash`() {
        val p = ThermalPolicy()
        assertEquals(RenderPace.IDLE, p.pace(started = false))
        assertEquals(RenderPace.FULL, p.pace(started = true))
        p.update(STATUS_MODERATE, Double.NaN)
        assertEquals(RenderPace.IDLE, p.pace(started = false))
        assertEquals(RenderPace.CAMERA, p.pace(started = true))
    }

    @Test
    fun `the ladder only ever gives ground`() {
        val rungs = ThermalPolicy.LADDER
        for (i in 1 until rungs.size) {
            assertEquals(i, rungs[i].level)
            assertTrue(rungs[i].renderScale <= rungs[i - 1].renderScale)
            assertTrue(rungs[i].maxLiveFireworks < rungs[i - 1].maxLiveFireworks)
        }
    }
}
