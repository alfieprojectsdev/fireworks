package dev.alfieprojects.mirroredlotus.core.math

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class Mat4Test {

    private fun apply(m: FloatArray, x: Float, y: Float, z: Float) = Mat4.transformPoint(m, x, y, z, FloatArray(4))

    @Test
    fun `placement composes translate, yaw, pitch and scale in that order`() {
        // A pylon: unit plane scaled to 6.25 x 50, yawed to face east, pushed out 35 m.
        val m = Mat4.translateRotateYX(FloatArray(16), 35f, 10f, 0f, (PI / 2).toFloat(), 0f, 6.25f, 50f, 1f)
        val top = apply(m, 0f, 0.5f, 0f)
        assertArrayEquals(floatArrayOf(35f, 35f, 0f, 1f), top, 1e-4f)
        // The plane's +Z normal now points east.
        val normalTip = apply(m, 0f, 0f, 1f)
        assertEquals(36f, normalTip[0], 1e-4f)
        // Its right edge (+X) points north (-Z) when facing east.
        val right = apply(m, 0.5f, 0f, 0f)
        assertEquals(-3.125f, right[2], 1e-4f)
    }

    @Test
    fun `ground ring pitch lays the XY plane flat`() {
        val m = Mat4.translateRotateYX(FloatArray(16), 0f, -15f, 0f, 0f, (-PI / 2).toFloat())
        val p = apply(m, 24f, 0f, 0f)
        assertArrayEquals(floatArrayOf(24f, -15f, 0f, 1f), p, 1e-4f)
        val q = apply(m, 0f, 24f, 0f) // XY plane's +Y goes to -Z (north)
        assertArrayEquals(floatArrayOf(0f, -15f, -24f, 1f), q, 1e-4f)
    }

    @Test
    fun `multiply matches manual composition`() {
        val a = Mat4.translateRotateYX(FloatArray(16), 1f, 2f, 3f, 0.3f)
        val b = Mat4.translateRotateYX(FloatArray(16), -4f, 0f, 1f, -1.1f, 0.4f, 2f, 3f, 4f)
        val ab = Mat4.multiply(FloatArray(16), a, b)
        val direct = apply(ab, 0.5f, -1f, 2f)
        val stepped = apply(b, 0.5f, -1f, 2f).let { apply(a, it[0], it[1], it[2]) }
        assertArrayEquals(stepped, direct, 1e-4f)
        assertArrayEquals(floatArrayOf(0.5f, -1f, 2f, 1f), apply(Mat4.identity(FloatArray(16)), 0.5f, -1f, 2f), 0f)
    }
}
