package dev.alfieprojects.mirroredlotus.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class MeshesTest {

    @Test
    fun `crown cylinder follows three r128 layout`() {
        val m = Meshes.cylinder(35f, 12f, 64)
        assertEquals(65 * 2, m.vertexCount)
        assertEquals(64 * 6, m.indices.size)
        // First vertex: theta = 0 sits on +Z, top edge, s = 0, t = 0 (top of the image).
        assertEquals(0f, m.positions[0], 1e-5f)
        assertEquals(6f, m.positions[1], 1e-5f)
        assertEquals(35f, m.positions[2], 1e-5f)
        assertEquals(0f, m.uvs[0], 0f)
        assertEquals(0f, m.uvs[1], 0f)
        // A quarter turn round is +X with s = 0.25: text reads left-to-right from outside.
        assertEquals(35f, m.positions[16 * 3], 1e-4f)
        assertEquals(0.25f, m.uvs[16 * 2], 1e-6f)
        // Bottom row carries t = 1.
        assertEquals(-6f, m.positions[65 * 3 + 1], 1e-5f)
        assertEquals(1f, m.uvs[65 * 2 + 1], 0f)
        for (v in 0 until m.vertexCount) {
            val r = hypot(m.positions[v * 3], m.positions[v * 3 + 2])
            assertEquals(35f, r, 1e-3f)
        }
        assertTrue(m.indices.all { it in 0 until m.vertexCount })
    }

    @Test
    fun `ring spans 12 to 24 m`() {
        val m = Meshes.ring(12f, 24f, 64)
        assertEquals(65 * 2, m.vertexCount)
        for (v in 0 until m.vertexCount) {
            val r = hypot(m.positions[v * 3], m.positions[v * 3 + 1])
            assertTrue(r in 11.999f..24.001f)
            assertEquals(0f, m.positions[v * 3 + 2], 0f)
        }
        assertTrue(m.indices.all { it in 0 until m.vertexCount })
    }

    @Test
    fun `plane is centred and upright with t = 0 at the top`() {
        val m = Meshes.plane(4f, 50f)
        assertEquals(-2f, m.positions[0], 0f)
        assertEquals(25f, m.positions[1], 0f)
        assertEquals(0f, m.uvs[1], 0f)
        assertEquals(-25f, m.positions[7], 0f)
        assertEquals(1f, m.uvs[5], 0f)
    }

    @Test
    fun `pylons ring the crown at the compass points`() {
        assertEquals(0f, SceneLayout.pylonOffsetX(0), 1e-4f); assertEquals(35f, SceneLayout.pylonOffsetZ(0), 1e-4f)
        assertEquals(35f, SceneLayout.pylonOffsetX(1), 1e-4f); assertEquals(0f, SceneLayout.pylonOffsetZ(1), 1e-4f)
        assertEquals(0f, SceneLayout.pylonOffsetX(2), 1e-4f); assertEquals(-35f, SceneLayout.pylonOffsetZ(2), 1e-4f)
        assertEquals(-35f, SceneLayout.pylonOffsetX(3), 1e-4f); assertEquals(0f, SceneLayout.pylonOffsetZ(3), 1e-4f)
        assertEquals(50f / 8, SceneLayout.pylonWidth(8), 0f)
    }
}
