package dev.alfieprojects.mirroredlotus.core.orientation

import dev.alfieprojects.mirroredlotus.core.fx.Rng
import dev.alfieprojects.mirroredlotus.core.math.Quat
import dev.alfieprojects.mirroredlotus.core.orientation.PoseMaths.arr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class HeadingFusionTest {

    private fun headingOf(qEnu: DoubleArray): Double {
        val scene = DoubleArray(4); val f = DoubleArray(3)
        Frames.enuToScene(qEnu, scene)
        Frames.forward(scene, f)
        return Frames.headingDeg(f[0], f[2])
    }

    private fun angDiff(a: Double, b: Double): Double {
        var d = (a - b) % 360
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    /**
     * Wearer pans +-30 degrees around heading [centreDeg]. The game sensor sees
     * the truth yawed by an arbitrary offset that drifts at 1 deg/min; the
     * absolute sensor sees the truth plus +-6 degree compass jitter.
     */
    private fun run(centreDeg: Double, offsetDeg: Double, seconds: Double, check: (Double, Double, Double) -> Unit) {
        val fusion = HeadingFusion(tauSeconds = 2.0)
        val rng = Rng(9)
        val out = DoubleArray(4)
        val step = 5_000_000L // game at 200 Hz, absolute at 50 Hz
        var t = 0L
        var i = 0
        while (t < (seconds * 1e9).toLong()) {
            val secs = t / 1e9
            val trueHeading = centreDeg + 30 * sin(2 * PI * 0.3 * secs)
            val truth = PoseMaths.upright(trueHeading, 5.0)
            val drift = offsetDeg + secs / 60.0
            val game = Quat.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(drift)) * truth
            fusion.onGame(t, arr(game), out)
            val fused = headingOf(out)
            if (i % 4 == 0) {
                val jitter = (rng.nextDouble() - 0.5) * 12
                val abs = Quat.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(jitter)) * truth
                assertFalse(fusion.onAbsolute(t, arr(abs), out))
            }
            check(secs, trueHeading, fused)
            t += step
            i++
        }
    }

    @Test
    fun `fused heading converges to north-anchored truth and rejects compass jitter`() {
        var worstLate = 0.0
        var sumSq = 0.0
        var n = 0
        run(centreDeg = 75.0, offsetDeg = 137.0, seconds = 30.0) { secs, truth, fused ->
            if (secs > 10) {
                val e = abs(angDiff(fused, truth))
                worstLate = maxOf(worstLate, e)
                sumSq += e * e; n++
            }
        }
        // Raw compass jitter is +-6 degrees (rms ~3.5); the fused output is far steadier.
        assertTrue("worst $worstLate deg", worstLate < 2.5)
        assertTrue("rms ${kotlin.math.sqrt(sumSq / n)} deg", kotlin.math.sqrt(sumSq / n) < 1.0)
    }

    @Test
    fun `first absolute sample snaps the heading instead of easing in`() {
        var firstFusedError = Double.NaN
        run(centreDeg = 10.0, offsetDeg = -100.0, seconds = 0.03) { secs, truth, fused ->
            if (secs >= 0.02 && firstFusedError.isNaN()) firstFusedError = abs(angDiff(fused, truth))
        }
        assertTrue("error $firstFusedError", firstFusedError < 7.0) // within the one sample's jitter
    }

    @Test
    fun `handles the wrap at south without spinning`() {
        var worst = 0.0
        run(centreDeg = 180.0, offsetDeg = 179.0, seconds = 20.0) { secs, truth, fused ->
            if (secs > 10) worst = maxOf(worst, abs(angDiff(fused, truth)))
        }
        assertTrue("worst $worst", worst < 2.5)
    }

    @Test
    fun `without a game sensor the absolute stream passes straight through`() {
        val fusion = HeadingFusion()
        val q = arr(PoseMaths.upright(42.0))
        val out = DoubleArray(4)
        assertTrue(fusion.onAbsolute(1L, q, out))
        assertEquals(HeadingFusion.Mode.ABSOLUTE_ONLY, fusion.mode)
        assertEquals(42.0, headingOf(out), 1e-9)
    }

    @Test
    fun `game alone runs uncorrected until a compass sample arrives`() {
        val fusion = HeadingFusion()
        val out = DoubleArray(4)
        fusion.onGame(1L, arr(PoseMaths.upright(10.0)), out)
        assertEquals(HeadingFusion.Mode.GAME_ONLY, fusion.mode)
        assertEquals(10.0, headingOf(out), 1e-9)
        fusion.onAbsolute(1L, arr(PoseMaths.upright(70.0)), out)
        assertEquals(HeadingFusion.Mode.FUSED, fusion.mode)
        fusion.onGame(2L, arr(PoseMaths.upright(10.0)), out)
        assertEquals(70.0, headingOf(out), 1e-6)
    }
}
