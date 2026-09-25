package dev.alfieprojects.mirroredlotus.core.audio

import dev.alfieprojects.mirroredlotus.core.fx.FireworkSystem
import dev.alfieprojects.mirroredlotus.core.fx.Rng
import dev.alfieprojects.mirroredlotus.core.orientation.Frames
import dev.alfieprojects.mirroredlotus.core.orientation.PoseMaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTest {

    private val sr = 48_000

    private fun renderVoice(shell: Int, earphones: Boolean = false, seed: Long = 1): FloatArray {
        val v = ExplosionVoice(sr)
        v.trigger(shell, earphones, 0f, 0f, -30f, Rng(seed))
        val out = ArrayList<Float>()
        val buf = FloatArray(256)
        var guard = 0
        while (v.active) {
            v.render(buf, buf.size)
            for (s in buf) out += s
            assertTrue(guard++ < 1000)
        }
        return out.toFloatArray()
    }

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until minOf(to, a.size)) s += a[i] * a[i].toDouble()
        return sqrt(s / maxOf(1, minOf(to, a.size) - from))
    }

    @Test
    fun `every shell renders a finite, bounded, decaying report`() {
        for (shell in 0..5) {
            val a = renderVoice(shell)
            assertTrue(a.all { it.isFinite() })
            assertTrue("shell $shell peak", a.maxOf { abs(it) } < 2.5f)
            val head = rms(a, 0, sr / 10)
            val tail = rms(a, a.size - sr / 20, a.size)
            assertTrue("shell $shell decays ($head -> $tail)", tail < head * 0.05)
            // Longest layer is the sizzle (<= 1.1 s) or a late crackle (<= 0.62 s).
            assertTrue(a.size <= (1.11 * sr).toInt() + 256)
        }
    }

    @Test
    fun `only the chrysanthemum crackles`() {
        // Crackle pops land 60-560 ms in, long after the body has gone.
        fun lateEnergy(shell: Int) = (1L..20L).map { seed -> rms(renderVoice(shell, seed = seed), sr * 3 / 10, sr * 55 / 100) }.average()
        assertTrue(lateEnergy(FireworkSystem.CHRYSANTHEMUM) > 3 * lateEnergy(FireworkSystem.PEONY))
    }

    @Test
    fun `thump pitch follows the web table`() {
        assertEquals(150.0, ExplosionVoice.thumpHz(FireworkSystem.PEONY, false), 0.0)
        assertEquals(100.0, ExplosionVoice.thumpHz(FireworkSystem.PEONY, true), 0.0)
        assertEquals(80.0, ExplosionVoice.thumpHz(FireworkSystem.WILLOW, true), 0.0)
        assertEquals(180.0, ExplosionVoice.thumpHz(FireworkSystem.HEART, false), 0.0)
        assertEquals(600.0, ExplosionVoice.bodyHz(FireworkSystem.HEART), 0.0)
    }

    @Test
    fun `exp ramp lands on its target and holds`() {
        val r = ExpRamp()
        r.start(0.75f, 0.001f, 0.35, sr)
        repeat((0.35 * sr).toInt()) { r.next() }
        assertEquals(0.001f, r.value, 1e-7f)
        r.next()
        assertEquals(0.001f, r.value, 0f)
    }

    @Test
    fun `soft clip saturates like the web curve, quirks included`() {
        // The web curve samples x = 2i/256 - 1, which never reaches +1 and puts
        // the zero crossing between two points: WaveShaper interpolation maps
        // 0 to -0.169. Kept for parity; the thump is a near-square either way.
        assertEquals(-0.1687f, SoftClip.shape(0f), 1e-3f)
        assertTrue(SoftClip.shape(0.1f) > 0.85f)
        assertTrue(SoftClip.shape(1f) > 0.99f)
        assertTrue(SoftClip.shape(-0.5f) < -0.98f)
        assertEquals(SoftClip.shape(1f), SoftClip.shape(5f), 0f)
    }

    // ---- mixer --------------------------------------------------------------

    private fun facing(headingDeg: Double) = ListenerPose { out ->
        Frames.enuToScene(PoseMaths.arr(PoseMaths.upright(headingDeg)), out)
        true
    }

    private fun burstLevels(mixer: SpatialMixer, x: Float, y: Float, z: Float): Pair<Double, Double> {
        assertTrue(mixer.queue.offer(x, y, z, FireworkSystem.PEONY))
        val out = FloatArray(4800 * 2)
        mixer.render(out, 4800)
        var l = 0.0; var r = 0.0
        for (i in 0 until 4800) { l += out[i * 2] * out[i * 2].toDouble(); r += out[i * 2 + 1] * out[i * 2 + 1].toDouble() }
        return sqrt(l / 4800) to sqrt(r / 4800)
    }

    @Test
    fun `a burst ahead is centred`() {
        val (l, r) = burstLevels(SpatialMixer(sr, facing(0.0), seed = 1), 0f, 0f, -30f)
        assertEquals(1.0, l / r, 0.02)
    }

    @Test
    fun `a burst to the east is heard on the right while facing north`() {
        val (l, r) = burstLevels(SpatialMixer(sr, facing(0.0), seed = 1), 30f, 0f, 0f)
        assertTrue("l=$l r=$r", r > 10 * l)
    }

    @Test
    fun `turning to face it re-centres it`() {
        val (l, r) = burstLevels(SpatialMixer(sr, facing(90.0), seed = 1), 30f, 0f, 0f)
        assertEquals(1.0, l / r, 0.02)
    }

    @Test
    fun `a burst to the west is heard on the left`() {
        val (l, r) = burstLevels(SpatialMixer(sr, facing(0.0), seed = 1), -30f, 0f, 0f)
        assertTrue(l > 10 * r)
    }

    @Test
    fun `distance follows the inverse model`() {
        assertEquals(1.0, SpatialMixer.distanceGain(10.0), 0.0)
        assertEquals(1.0, SpatialMixer.distanceGain(30.0), 0.0)
        assertEquals(0.5, SpatialMixer.distanceGain(60.0), 1e-12)
        assertEquals(0.06, SpatialMixer.distanceGain(500.0), 1e-12)
        // Both far enough to stay under the compressor's threshold.
        val near = burstLevels(SpatialMixer(sr, facing(0.0), seed = 1), 0f, 0f, -300f)
        val far = burstLevels(SpatialMixer(sr, facing(0.0), seed = 1), 0f, 0f, -600f)
        assertEquals(0.5, far.first / near.first, 0.005)
    }

    @Test
    fun `mixer output stays in range under a barrage and handles long blocks`() {
        val mixer = SpatialMixer(sr, facing(0.0), maxBlock = 256, seed = 3)
        repeat(40) { mixer.queue.offer(0f, 0f, -5f, it % 6) }
        val out = FloatArray(10_000 * 2)
        mixer.render(out, 10_000)
        assertTrue(out.all { it.isFinite() && it in -1f..1f })
        assertEquals(16, mixer.activeVoices) // pool saturated, extras dropped
    }

    @Test
    fun `silence in, silence out`() {
        val mixer = SpatialMixer(sr, facing(0.0), seed = 3)
        val out = FloatArray(2048) { 1f }
        mixer.render(out, 1024)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `earphone mode adds a reverb tail after the dry sound is gone`() {
        fun tailEnergy(earphones: Boolean): Double {
            val mixer = SpatialMixer(sr, facing(0.0), seed = 5)
            mixer.earphones = earphones
            val warm = FloatArray(sr) // let the wet gain settle
            mixer.render(warm, sr / 2)
            mixer.queue.offer(0f, 0f, -30f, FireworkSystem.WILLOW)
            val out = FloatArray(3 * sr * 2)
            mixer.render(out, 3 * sr)
            var e = 0.0
            for (i in (int(1.3 * sr)) * 2 until out.size) e += out[i] * out[i].toDouble()
            return e
        }
        assertEquals(0.0, tailEnergy(false), 1e-12)
        assertTrue(tailEnergy(true) > 1e-6)
    }

    private fun int(d: Double) = d.toInt()

    // ---- building blocks ----------------------------------------------------

    @Test
    fun `compressor static curve matches threshold, knee and ratio`() {
        val c = Compressor(sr)
        assertEquals(-20f, c.staticCurveDb(-20f), 1e-6f)
        assertEquals(-6f + 6f / 4f, c.staticCurveDb(0f), 1e-6f)
        assertEquals(-6f + 12f / 4f, c.staticCurveDb(6f), 1e-6f)
        // Knee is continuous at both edges.
        assertEquals(-7.5f, c.staticCurveDb(-7.5f), 1e-5f)
        assertEquals(-6f + 1.5f / 4f, c.staticCurveDb(-4.5f), 1e-5f)
    }

    @Test
    fun `compressor settles a full-scale tone about 4_5 dB down`() {
        val c = Compressor(sr)
        val buf = FloatArray(2)
        var peak = 0f
        for (i in 0 until sr) {
            val s = sin(2 * PI * 440 * i / sr).toFloat()
            buf[0] = s; buf[1] = s
            c.process(buf, 0)
            if (i > sr / 2) peak = maxOf(peak, abs(buf[0]))
        }
        val db = 20 * kotlin.math.log10(peak.toDouble())
        assertTrue("peak $db dB", db in -6.0..-3.0)
    }

    @Test
    fun `reverb impulse response is finite, decays, and has convolver-like energy`() {
        val rv = Reverb(sr)
        val out = FloatArray(2)
        var energy = 0.0
        var late = 0.0
        for (i in 0 until 4 * sr) {
            out[0] = 0f; out[1] = 0f
            rv.process(if (i == 0) 1f else 0f, 1f, out, 0)
            val e = out[0] * out[0] + out[1] * out[1].toDouble()
            assertTrue(e.isFinite())
            energy += e
            if (i > 3 * sr) late += e
        }
        assertTrue("energy $energy", energy in 0.05..0.5)
        assertTrue(late < energy * 1e-3)
    }

    @Test
    fun `trigger queue hands every item across threads in order`() {
        val q = TriggerQueue(8)
        val total = 100_000
        val producer = Thread {
            var i = 0
            while (i < total) if (q.offer(i.toFloat(), 0f, 0f, i % 6)) i++
        }
        producer.start()
        val item = FloatArray(4)
        var expected = 0
        val deadline = System.nanoTime() + 20_000_000_000L
        while (expected < total && System.nanoTime() < deadline) {
            if (q.poll(item)) {
                assertEquals(expected.toFloat(), item[0], 0f)
                assertEquals((expected % 6).toFloat(), item[3], 0f)
                expected++
            }
        }
        producer.join()
        assertEquals(total, expected)
        assertFalse(q.poll(item))
    }
}
