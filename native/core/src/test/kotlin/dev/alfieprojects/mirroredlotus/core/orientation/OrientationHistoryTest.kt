package dev.alfieprojects.mirroredlotus.core.orientation

import dev.alfieprojects.mirroredlotus.core.camera.CameraTiming
import dev.alfieprojects.mirroredlotus.core.math.Quat
import dev.alfieprojects.mirroredlotus.core.orientation.PoseMaths.arr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class OrientationHistoryTest {

    /**
     * Oscillating pan: +-40 degrees of heading at 0.8 Hz plus a nod. Constant
     * rotation would hide timestamp bugs (a common offset cancels), so every
     * timing test here uses oscillatory motion - the stable-still lesson.
     */
    private fun truth(tNanos: Long): Quat {
        val t = tNanos / 1e9
        return PoseMaths.upright(40 * sin(2 * PI * 0.8 * t), 10 * sin(2 * PI * 1.3 * t))
    }

    private fun filled(rateHz: Int, seconds: Double): OrientationHistory {
        val h = OrientationHistory(1024)
        val step = (1e9 / rateHz).toLong()
        var t = 1_000_000_000L
        while (t < 1_000_000_000L + (seconds * 1e9).toLong()) {
            h.push(t, arr(truth(t)))
            t += step
        }
        return h
    }

    @Test
    fun `slerp between 200 Hz samples tracks the true motion`() {
        val h = filled(200, 2.0)
        val out = DoubleArray(4)
        var worst = 0.0
        var t = 1_010_000_000L
        while (t < 2_990_000_000L) {
            assertEquals(OrientationHistory.INTERPOLATED, h.sampleAt(t, out))
            worst = maxOf(worst, Quat.fromArray(out).angleTo(truth(t)))
            t += 1_234_567L // deliberately off the sample grid
        }
        assertTrue("worst error ${Math.toDegrees(worst)} deg", Math.toDegrees(worst) < 0.02)
    }

    @Test
    fun `sampling at the frame's own time matters under oscillation`() {
        // Pose drawn at the camera frame's mid-exposure versus the newest pose
        // (the frame is ~50 ms old when drawn): the error sync removes.
        val h = filled(200, 3.0)
        val out = DoubleArray(4)
        var worstSynced = 0.0
        var worstLatest = 0.0
        for (k in 0 until 200) {
            val drawAt = 1_500_000_000L + k * 7_000_000L
            val frameCentre = drawAt - 50_000_000L
            h.sampleAt(frameCentre, out)
            worstSynced = maxOf(worstSynced, Quat.fromArray(out).angleTo(truth(frameCentre)))
            h.sampleAt(drawAt, out)
            worstLatest = maxOf(worstLatest, Quat.fromArray(out).angleTo(truth(frameCentre)))
        }
        assertTrue(Math.toDegrees(worstSynced) < 0.02)
        // A 0.8 Hz +-40 degree pan moves up to ~10 degrees in 50 ms.
        assertTrue("unsynced ${Math.toDegrees(worstLatest)}", Math.toDegrees(worstLatest) > 5)
    }

    @Test
    fun `out-of-range requests clamp and say so`() {
        val h = filled(100, 1.0)
        val out = DoubleArray(4)
        assertEquals(OrientationHistory.CLAMPED_OLDEST, h.sampleAt(0L, out))
        assertEquals(0.0, Quat.fromArray(out).angleTo(truth(1_000_000_000L)), 1e-9)
        assertEquals(OrientationHistory.CLAMPED_NEWEST, h.sampleAt(9_000_000_000L, out))
        assertEquals(OrientationHistory.EMPTY, OrientationHistory().sampleAt(0L, out))
    }

    @Test
    fun `ring wraps and keeps only the newest samples`() {
        val h = OrientationHistory(8)
        for (i in 1..20) h.push(i * 10L, arr(truth(i * 10L)))
        assertEquals(8, h.count)
        val out = DoubleArray(4)
        assertEquals(200L, h.latest(out))
        assertEquals(OrientationHistory.CLAMPED_OLDEST, h.sampleAt(120L, out))
        assertEquals(OrientationHistory.INTERPOLATED, h.sampleAt(135L, out))
    }

    @Test
    fun `stale or repeated timestamps are dropped`() {
        val h = OrientationHistory(8)
        h.push(100L, arr(Quat.IDENTITY))
        h.push(100L, arr(PoseMaths.upright(90.0)))
        h.push(50L, arr(PoseMaths.upright(90.0)))
        assertEquals(1, h.count)
    }

    @Test
    fun `pose sampler follows the camera when asked and reports the lag`() {
        val h = filled(200, 2.0)
        val s = PoseSampler(h)
        val frameCentre = 2_500_000_000L
        s.sample(frameCentre, syncToCamera = true)
        assertTrue(s.synced)
        val newest = 1_000_000_000L + (2.0 * 200 - 1).toLong() * 5_000_000L
        assertEquals(newest - frameCentre, s.lagNanos)
        assertEquals(0.0, Quat.fromArray(s.enu).angleTo(truth(frameCentre)), 1e-3)

        s.sample(CameraTiming.NO_TIME, syncToCamera = true)
        assertEquals(false, s.synced)
        assertEquals(0L, s.lagNanos)
        s.sample(frameCentre, syncToCamera = false)
        assertEquals(false, s.synced)
    }

    @Test
    fun `pose sampler waits for the first orientation`() {
        val s = PoseSampler(OrientationHistory())
        s.sample(123L, syncToCamera = true)
        assertEquals(false, s.hasPose)
    }
}
