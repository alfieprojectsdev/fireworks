package dev.alfieprojects.mirroredlotus.core.fx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class FireworkSystemTest {

    private val out = FloatArray(FireworkSystem.MAX_VERTICES * FireworkSystem.FLOATS_PER_VERTEX)

    @Test
    fun `hsl matches three r128`() {
        val c = FloatArray(3)
        Hsl.toRgb(0.0, 1.0, 0.5, c); assertArrayEquals(floatArrayOf(1f, 0f, 0f), c, 1e-6f)
        Hsl.toRgb(1.0 / 3, 1.0, 0.5, c); assertArrayEquals(floatArrayOf(0f, 1f, 0f), c, 1e-6f)
        Hsl.toRgb(0.5, 0.0, 0.3, c); assertArrayEquals(floatArrayOf(0.3f, 0.3f, 0.3f), c, 1e-6f)
        // The heart shell's hot pink: hsl(0.93, 1, 0.75).
        Hsl.toRgb(0.93, 1.0, 0.75, c); assertArrayEquals(floatArrayOf(1f, 0.5f, 0.71f), c, 1e-6f)
    }

    @Test
    fun `rockets launch 30 to 60 m out on the north side of the group, below ground`() {
        repeat(200) { seed ->
            val fw = FireworkSystem(Rng(seed.toLong()))
            assertTrue(fw.spawn(100.0, -500.0))
            // First vertex written is the rocket head.
            fw.writeVertices(out)
            val dx = out[0] - 100.0
            val dz = out[2] + 500.0
            val r = hypot(dx, dz)
            assertTrue("radius $r", r in 29.99..60.01)
            assertTrue("north of group", dz < 0)
            assertEquals(-40.0, out[1].toDouble(), 1e-4)
        }
    }

    @Test
    fun `a rocket bursts on the tick its climb slows to 1 m per tick`() {
        var bursts = 0
        val fw = FireworkSystem(Rng(7)) { _, y, _, _ ->
            bursts++
            assertTrue("burst height $y", y > -40)
        }
        fw.spawn(0.0, 0.0)
        var ticks = 0
        while (fw.isRocketAt(0)) {
            val vyBefore = fw.rocketVy(0)
            fw.update(false, 0.0, 0.0, 0.0)
            ticks++
            if (fw.isRocketAt(0)) assertTrue(fw.rocketVy(0) > 1) else assertTrue(vyBefore - 0.2 <= 1)
        }
        assertEquals(1, bursts)
        // vy starts 4..7 and drops 0.2 a tick: 15..30 ticks of climb.
        assertTrue("climb ticks $ticks", ticks in 15..31)
    }

    @Test
    fun `shell mix and particle counts follow the web weights`() {
        val counts = IntArray(6)
        val rng = Rng(42)
        val fw = FireworkSystem(rng)
        repeat(4000) {
            fw.spawn(0.0, 0.0)
            while (fw.isRocketAt(0)) fw.update(false, 0.0, 0.0, 0.0)
            val shell = fw.shellAt(0)
            counts[shell]++
            assertEquals(FireworkSystem.particleCount(shell), fw.particleCountAt(0))
            while (fw.isActive(0)) fw.update(false, 0.0, 0.0, 0.0)
        }
        val n = counts.sum().toDouble()
        assertEquals(0.10, counts[FireworkSystem.HEART] / n, 0.02)
        assertEquals(0.08, counts[FireworkSystem.LEMNISCATE] / n, 0.02)
        assertEquals(0.08, counts[FireworkSystem.LISSAJOUS] / n, 0.02)
        for (s in 0..2) assertEquals(0.74 / 3, counts[s] / n, 0.03)
    }

    @Test
    fun `bursts fade out and free their slot within max life`() {
        val fw = FireworkSystem(Rng(3))
        fw.spawn(0.0, 0.0)
        while (fw.isRocketAt(0)) fw.update(false, 0.0, 0.0, 0.0)
        var ticks = 0
        while (fw.isActive(0)) {
            fw.update(false, 0.0, 0.0, 0.0)
            ticks++
            assertTrue(ticks <= 180)
        }
        assertEquals(0, fw.liveCount)
        assertEquals(0, fw.writeVertices(out))
    }

    @Test
    fun `vertices stay within the preallocated buffer at the spawner's ceiling`() {
        val fw = FireworkSystem(Rng(11))
        var maxVertices = 0
        repeat(2000) {
            while (fw.liveCount < 15) fw.spawn(0.0, 0.0)
            fw.update(false, 0.0, 0.0, 0.0)
            maxVertices = maxOf(maxVertices, fw.writeVertices(out))
        }
        assertTrue(maxVertices <= FireworkSystem.MAX_VERTICES)
        assertTrue("fireworks actually drew", maxVertices > 1000)
        for (i in 0 until maxVertices * FireworkSystem.FLOATS_PER_VERTEX) assertTrue(out[i].isFinite())
    }

    @Test
    fun `premultiplied colours never exceed one`() {
        val fw = FireworkSystem(Rng(5))
        repeat(600) {
            if (fw.liveCount < 15) fw.spawn(0.0, 0.0)
            fw.update(false, 0.0, 0.0, 0.0)
            val n = fw.writeVertices(out)
            for (v in 0 until n) for (c in 3..5) {
                val value = out[v * 6 + c]
                assertTrue(value in 0f..1f)
            }
        }
    }

    @Test
    fun `a burst near the monument lights it up, one far away does not`() {
        val near = FireworkSystem(Rng(1))
        near.spawn(0.0, 0.0)
        while (near.isRocketAt(0)) near.update(true, 0.0, 15.0, 0.0)
        assertTrue(near.textIllumination > 0.3)
        near.tickIllumination()
        assertTrue(near.textIllumination < 1.0)

        val far = FireworkSystem(Rng(1))
        far.spawn(0.0, 0.0)
        while (far.isRocketAt(0)) far.update(true, 0.0, 15.0, 5000.0)
        assertEquals(0.0, far.textIllumination, 0.0)
    }

    @Test
    fun `same seed, same show`() {
        val a = FireworkSystem(Rng(99)); val b = FireworkSystem(Rng(99))
        val outB = FloatArray(out.size)
        repeat(300) {
            if (it % 7 == 0) { a.spawn(3.0, 4.0); b.spawn(3.0, 4.0) }
            a.update(true, 0.0, 15.0, 0.0); b.update(true, 0.0, 15.0, 0.0)
            val na = a.writeVertices(out); val nb = b.writeVertices(outB)
            assertEquals(na, nb)
            for (i in 0 until na * 6) assertEquals(out[i], outB[i], 0f)
        }
    }

    @Test
    fun `spawn refuses when every slot is live`() {
        val fw = FireworkSystem(Rng(2))
        repeat(FireworkSystem.MAX_FIREWORKS) { assertTrue(fw.spawn(0.0, 0.0)) }
        assertFalse(fw.spawn(0.0, 0.0))
    }
}
