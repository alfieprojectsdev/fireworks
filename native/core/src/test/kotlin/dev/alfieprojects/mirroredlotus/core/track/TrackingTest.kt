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
    private fun countSteps(
        seconds: Double, stepHz: Double, bounce: Double, noise: Double = 0.25, seed: Long = 1,
        rotating: (Double) -> Boolean = { false },
    ): Long {
        val d = StepDetector()
        val rng = Rng(seed)
        val ux = 0.3; val uy = 0.9; val uz = sqrt(1 - ux * ux - uy * uy) // phone held tilted
        var t = 0L
        while (t < (seconds * 1e9).toLong()) {
            val s = t / 1e9
            val along = g + bounce * sin(2 * PI * stepHz * s)
            fun n() = (rng.nextDouble() - 0.5) * 2 * noise
            d.onSample(t, ux * along + n(), uy * along + n(), uz * along + n(), rotating(s))
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
    fun `isolated jolts are not steps, a rhythm is`() {
        val d = StepDetector()
        var t = 0L
        fun sample(a: Double) { d.onSample(t, 0.0, 0.0, g + a); t += 20_000_000L }
        // Three single bumps, three seconds apart (bumping into a pew, say).
        repeat(3) {
            repeat(25) { sample(-1.0) }; repeat(5) { sample(3.0) }; repeat(120) { sample(0.0) }
        }
        assertEquals(0L, d.steps)
        // Then two steps in rhythm: both credited when the second confirms the first.
        repeat(2) { repeat(15) { sample(-1.0) }; repeat(15) { sample(2.5) } }
        assertEquals(2L, d.steps)
    }

    @Test
    fun `swinging the phone to follow a burst is not walking`() {
        assertEquals(0L, countSteps(20.0, 1.8, 2.0, rotating = { true }))
        // Walking, with a two-second swing in the middle: only the swing's steps are lost.
        val n = countSteps(20.0, 1.8, 2.0, rotating = { it in 9.0..11.0 })
        assertTrue("steps $n", n in 30L..34L)
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

    /**
     * Moving north at [speed] m/s with 1 Hz fixes (5 m accuracy, [noise] m of
     * error) and no steps at all. Returns the worst lag after warm-up, the worst
     * lag measured against 3-sigma, and whether it ever reset.
     */
    private fun ride(speed: Double, doppler: Boolean, noise: Double, seconds: Int = 120): Triple<Double, Double, Boolean> {
        val t = PositionTracker()
        val rng = Rng(21)
        var worst = 0.0
        var worstRatio = 0.0
        var reset = false
        for (s in 0..seconds) {
            val truthN = -1500.0 + speed * s
            val p = at(truthN + (rng.nextDouble() - 0.5) * 2 * noise, (rng.nextDouble() - 0.5) * 2 * noise)
            val sp = if (doppler) speed + (rng.nextDouble() - 0.5) * 0.4 else null
            val result = t.onFix(p.lat, p.lng, 5.0, s * 1000L, sp, if (doppler) 0.0 else null, if (doppler) 0.3 else null)
            if (result == PositionTracker.FixResult.RESET) reset = true
            if (s >= 10) {
                val lag = errorM(t, truthN)
                worst = maxOf(worst, lag)
                worstRatio = maxOf(worstRatio, lag / (3 * t.sigmaM + 1))
            }
        }
        return Triple(worst, worstRatio, reset)
    }

    @Test
    fun `walking without detected steps doesn't trail behind`() {
        val (lag, ratio, reset) = ride(1.4, doppler = true, noise = 1.5)
        assertTrue("lag $lag m", lag < 3.0)
        assertTrue("sigma must cover the lag ($ratio)", ratio < 1.0)
        assertFalse(reset)
    }

    @Test
    fun `vehicle speed tracks without the reject-reset sawtooth`() {
        for (speed in listOf(5.0, 12.0, 20.0)) { // jeep, car, the 20 m/s desk simulation
            val (lag, ratio, reset) = ride(speed, doppler = true, noise = 1.5)
            assertTrue("$speed m/s: lag $lag m", lag < 4.0)
            assertTrue("$speed m/s: sigma must cover the lag ($ratio)", ratio < 1.0)
            assertFalse("$speed m/s reset", reset)
        }
    }

    @Test
    fun `without Doppler, GNSS-grade fixes still imply the velocity`() {
        for (speed in listOf(1.4, 5.0)) {
            val (lag, _, reset) = ride(speed, doppler = false, noise = 1.0)
            assertTrue("$speed m/s: lag $lag m", lag < 6.0)
            assertFalse("$speed m/s reset", reset)
        }
    }

    @Test
    fun `steps and GPS velocity together don't double-count`() {
        val t = PositionTracker()
        var ms = 0L
        for (s in 0..60) {
            val p = at(-100.0 + 1.4 * s)
            t.onFix(p.lat, p.lng, 5.0, ms, 1.4, 0.0, 0.3)
            // two strides a second, north, between fixes
            t.onStep(0.0, 1.0, ms + 250); t.onStep(0.0, 1.0, ms + 750)
            ms += 1000
        }
        assertTrue("error ${errorM(t, -100.0 + 1.4 * 60)}", errorM(t, -100.0 + 1.4 * 60) < 2.0)
        assertEquals(122L, t.stepCount) // counted, but GPS velocity carried the motion
    }

    @Test
    fun `steps take over once GPS velocity goes stale indoors`() {
        val t = PositionTracker()
        val door = at(40.0)
        t.onFix(door.lat, door.lng, 5.0, 0, 1.4, 180.0, 0.3) // walking south into the church
        // No more fixes; the wearer keeps walking, a 0.7 m step every 0.5 s.
        // Steps in the first 2.5 s are only counted (the velocity carries that
        // stretch); after that the velocity is stale and steps drive.
        for (ms in 500L..14_000L step 500L) t.onStep(0.0, -1.0, ms)
        assertEquals(28L, t.stepCount)
        // 14 s at 1.4 m/s from 40 m north of the reference point.
        assertTrue("error ${errorM(t, 20.4)}", errorM(t, 20.4) < 0.5)
    }

    @Test
    fun `a swing trips the gate, walking and trembling don't`() {
        fun maxSwinging(peakRadPerS: Double, hz: Double): Pair<Boolean, Boolean> {
            val gate = SwingGate()
            val q = DoubleArray(4)
            var anySwing = false
            var t = 0L
            while (t < 4_000_000_000L) {
                // Oscillating yaw about the vertical: peak rate = amplitude * 2 pi f.
                val yaw = peakRadPerS / (2 * PI * hz) * sin(2 * PI * hz * t / 1e9)
                q[0] = cos(yaw / 2); q[1] = 0.0; q[2] = 0.0; q[3] = sin(yaw / 2)
                gate.onOrientation(t, q)
                anySwing = anySwing || gate.swinging(t)
                t += 5_000_000L
            }
            // Held still afterwards: the gate reopens once the hold has passed.
            repeat(100) { gate.onOrientation(t, q); t += 5_000_000L }
            return anySwing to gate.swinging(t)
        }
        assertEquals(false to false, maxSwinging(0.6, 1.0))   // the sway of walking with the phone up
        assertEquals(false to false, maxSwinging(0.05, 8.0))  // hand tremor: milliradians
        assertEquals(true to false, maxSwinging(3.0, 0.5))    // whipping round after a burst
    }

    @Test
    fun `steps before the first fix are ignored`() {
        val t = PositionTracker()
        t.onStep(1.0, 0.0, 0)
        assertFalse(t.initialised)
        assertEquals(0L, t.stepCount)
    }
}
