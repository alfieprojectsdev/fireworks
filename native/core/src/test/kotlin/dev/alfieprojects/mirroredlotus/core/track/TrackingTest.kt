package dev.alfieprojects.mirroredlotus.core.track

import dev.alfieprojects.mirroredlotus.core.fx.Rng
import dev.alfieprojects.mirroredlotus.core.geo.Geo
import dev.alfieprojects.mirroredlotus.core.orientation.PoseMaths
import dev.alfieprojects.mirroredlotus.core.site.Sites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TrackingTest {

    private val g = 9.80665

    /**
     * Accelerometer at 50 Hz for [seconds]: gravity along a fixed tilted axis,
     * plus a bounce of [bounce] m/s^2 at [stepHz] along it, plus sensor noise.
     */
    private fun countSteps(seconds: Double, stepHz: Double, bounce: Double, noise: Double = 0.25, seed: Long = 1): Long {
        val d = StepDetector()
        val rng = Rng(seed)
        val ux = 0.3; val uy = 0.9; val uz = sqrt(1 - ux * ux - uy * uy) // phone held tilted
        var t = 0L
        while (t < (seconds * 1e9).toLong()) {
            val s = t / 1e9
            val along = g + bounce * sin(2 * PI * stepHz * s)
            fun n() = (rng.nextDouble() - 0.5) * 2 * noise
            d.onSample(t, ux * along + n(), uy * along + n(), uz * along + n())
            t += 20_000_000L
        }
        return d.steps
    }

    @Test
    fun `walking pace is counted step for step`() {
        assertEquals(36.0, countSteps(20.0, 1.8, 2.0).toDouble(), 1.0)
        assertEquals(48.0, countSteps(20.0, 2.4, 2.5).toDouble(), 1.0)
        assertEquals(30.0, countSteps(20.0, 1.5, 1.6).toDouble(), 1.0)
    }

    @Test
    fun `standing still or panning the phone is not walking`() {
        assertEquals(0L, countSteps(30.0, 1.8, 0.0, noise = 0.3)) // sensor noise only
        assertEquals(0L, countSteps(30.0, 0.4, 0.8))              // slow sway, below the threshold
    }

    @Test
    fun `walking heading follows the camera, even when tilted up at the crown`() {
        val out = DoubleArray(2)
        for (h in listOf(0.0, 45.0, 90.0, 200.0, 315.0)) {
            for (pitch in listOf(0.0, 40.0, 85.0)) {
                assertTrue(WalkHeading.of(PoseMaths.arr(PoseMaths.upright(h, pitch)), out))
                assertEquals("heading $h pitch $pitch east", sin(Math.toRadians(h)), out[0], 1e-9)
                assertEquals("heading $h pitch $pitch north", cos(Math.toRadians(h)), out[1], 1e-9)
            }
        }
    }

    @Test
    fun `a phone lying face-up faces where its top edge points`() {
        // The field case: face-up on a desk, top edge toward ~249 degrees, with a
        // 2 degree tilt whose horizontal part pointed somewhere else entirely.
        val out = DoubleArray(2)
        for (h in listOf(0.0, 90.0, 249.0)) {
            for (tilt in listOf(-90.0, -88.0, -70.0)) { // camera pitched down by 90, 88, 70 degrees
                assertTrue(WalkHeading.of(PoseMaths.arr(PoseMaths.upright(h, tilt)), out))
                assertEquals("heading $h tilt $tilt east", sin(Math.toRadians(h)), out[0], 1e-9)
                assertEquals("heading $h tilt $tilt north", cos(Math.toRadians(h)), out[1], 1e-9)
            }
        }
    }

    @Test
    fun `facing is continuous across the forward-to-top-edge switch`() {
        val a = DoubleArray(2); val b = DoubleArray(2)
        // Around the 60 degree threshold, pitched both up and down.
        for (pitch in listOf(59.0, 61.0, -59.0, -61.0)) {
            assertTrue(WalkHeading.of(PoseMaths.arr(PoseMaths.upright(137.0, pitch)), a))
            assertTrue(WalkHeading.of(PoseMaths.arr(PoseMaths.upright(137.0, 0.0)), b))
            assertEquals(b[0], a[0], 1e-9)
            assertEquals(b[1], a[1], 1e-9)
        }
    }

    // ---- tracker ------------------------------------------------------------

    private val church = Sites.CHURCH

    /** Point [northM] north and [eastM] east of the church. */
    private fun at(northM: Double, eastM: Double = 0.0) =
        Geo.destination(church.lat, church.lng, 0.0, northM).let { Geo.destination(it.lat, it.lng, PI / 2, eastM) }

    private fun errorM(t: PositionTracker, northM: Double, eastM: Double = 0.0): Double {
        val truth = at(northM, eastM)
        return Geo.distanceM(t.lat, t.lng, truth.lat, truth.lng)
    }

    @Test
    fun `with good GPS the estimate follows the fixes`() {
        val t = PositionTracker()
        var ms = 0L
        for (i in 0..60) {
            val p = at(200.0 - i * 1.4) // walking south toward the church
            t.onFix(p.lat, p.lng, 5.0, ms)
            if (i > 0) repeat(2) { t.onStep(0.0, -1.0, ms) } // ~1.4 m in two strides
            ms += 1000
        }
        assertTrue("error ${errorM(t, 200.0 - 60 * 1.4)}", errorM(t, 200.0 - 60 * 1.4) < 3.0)
    }

    @Test
    fun `when GPS drops out, footsteps carry the position`() {
        val t = PositionTracker()
        val start = at(40.0)
        t.onFix(start.lat, start.lng, 5.0, 0)
        // 30 steps south (0.7 m each) with no fixes at all: 21 m.
        var ms = 0L
        repeat(30) { ms += 500; t.onStep(0.0, -1.0, ms) }
        assertTrue("error ${errorM(t, 19.0)}", errorM(t, 19.0) < 0.5)
        assertEquals(30L, t.stepCount)
        assertTrue(t.sigmaM > 5.0) // and it knows it is less sure
    }

    @Test
    fun `standing inside the church, wandering indoor fixes barely move it`() {
        val t = PositionTracker()
        val rng = Rng(4)
        val here = at(10.0)
        t.onFix(here.lat, here.lng, 5.0, 0) // last good outdoor fix at the door
        var worst = 0.0
        var worstRaw = 0.0
        for (s in 1..120) { // two minutes of 1 Hz indoor fixes, +-25 m scatter, 25 m accuracy
            val p = at(10.0 + (rng.nextDouble() - 0.5) * 50, (rng.nextDouble() - 0.5) * 50)
            worstRaw = maxOf(worstRaw, Geo.distanceM(p.lat, p.lng, here.lat, here.lng))
            t.onFix(p.lat, p.lng, 25.0, s * 1000L)
            worst = maxOf(worst, errorM(t, 10.0))
        }
        assertTrue("raw scatter $worstRaw", worstRaw > 25.0)
        assertTrue("fused stayed within $worst m", worst < 8.0)
    }

    @Test
    fun `a single wild fix is rejected, a persistent one wins`() {
        val t = PositionTracker()
        val here = at(50.0)
        t.onFix(here.lat, here.lng, 5.0, 0)
        t.onFix(here.lat, here.lng, 5.0, 1000)
        val far = at(150.0)
        assertEquals(PositionTracker.FixResult.REJECTED_JUMP, t.onFix(far.lat, far.lng, 8.0, 2000))
        assertTrue(errorM(t, 50.0) < 1.0)
        repeat(3) { assertEquals(PositionTracker.FixResult.REJECTED_JUMP, t.onFix(far.lat, far.lng, 8.0, 3000L + it * 1000)) }
        assertEquals(PositionTracker.FixResult.RESET, t.onFix(far.lat, far.lng, 8.0, 7000))
        assertTrue(errorM(t, 150.0) < 1.0)
    }

    @Test
    fun `hopeless fixes are ignored`() {
        val t = PositionTracker()
        val here = at(50.0)
        t.onFix(here.lat, here.lng, 5.0, 0)
        val p = at(90.0)
        assertEquals(PositionTracker.FixResult.IGNORED_ACCURACY, t.onFix(p.lat, p.lng, 250.0, 1000))
        assertTrue(errorM(t, 50.0) < 0.01)
        assertEquals(1L, t.rejectedFixes)
    }

    @Test
    fun `missed steps can't strand it because GPS wins over time`() {
        val t = PositionTracker()
        val start = at(0.0)
        t.onFix(start.lat, start.lng, 5.0, 0)
        // The wearer moved 15 m, but no steps were detected (phone in a pocket, say).
        val moved = at(15.0)
        var ms = 0L
        repeat(60) { ms += 1000; t.onFix(moved.lat, moved.lng, 10.0, ms) }
        assertTrue("error ${errorM(t, 15.0)}", errorM(t, 15.0) < 2.0)
    }

    @Test
    fun `steps before the first fix are ignored`() {
        val t = PositionTracker()
        t.onStep(1.0, 0.0, 0)
        assertFalse(t.initialised)
        assertEquals(0L, t.stepCount)
    }
}
