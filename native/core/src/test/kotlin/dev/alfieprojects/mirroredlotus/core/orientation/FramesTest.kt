package dev.alfieprojects.mirroredlotus.core.orientation

import dev.alfieprojects.mirroredlotus.core.math.Mat4
import dev.alfieprojects.mirroredlotus.core.math.Quat
import dev.alfieprojects.mirroredlotus.core.math.Vec3
import dev.alfieprojects.mirroredlotus.core.orientation.PoseMaths.arr
import org.junit.Assert.assertEquals
import org.junit.Test

class FramesTest {

    private fun sceneForward(qEnu: Quat): DoubleArray {
        val scene = DoubleArray(4)
        Frames.enuToScene(arr(qEnu), scene)
        return DoubleArray(3).also { Frames.forward(scene, it) }
    }

    @Test
    fun `upright phone facing north looks down scene minus Z`() {
        val f = sceneForward(PoseMaths.upright(0.0))
        assertEquals(0.0, f[0], 1e-12); assertEquals(0.0, f[1], 1e-12); assertEquals(-1.0, f[2], 1e-12)
    }

    @Test
    fun `heading reads back for every compass point`() {
        for (h in listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0, 359.0)) {
            val f = sceneForward(PoseMaths.upright(h))
            assertEquals(0.0, f[1], 1e-12)
            assertEquals("heading $h", h, Frames.headingDeg(f[0], f[2]), 1e-9)
        }
    }

    @Test
    fun `pitching up raises the view toward scene plus Y`() {
        val f = sceneForward(PoseMaths.upright(90.0, pitchDeg = 30.0))
        assertEquals(0.5, f[1], 1e-12)
        assertEquals(90.0, Frames.headingDeg(f[0], f[2]), 1e-9)
    }

    @Test
    fun `screen up stays scene up when held upright`() {
        val scene = DoubleArray(4)
        Frames.enuToScene(arr(PoseMaths.upright(200.0)), scene)
        val up = Quat.fromArray(scene).rotate(Vec3(0.0, 1.0, 0.0))
        assertEquals(1.0, up.y, 1e-12)
    }

    @Test
    fun `the web build's raw ENU quaternion aimed the camera at the sky`() {
        // Documenting the bug the native port fixes: without ENU->scene the
        // upright phone's forward is +Y (straight up) in the Three.js scene.
        val raw = Quat.fromArray(arr(PoseMaths.upright(0.0)))
        val f = raw.rotate(Vec3(0.0, 0.0, -1.0))
        assertEquals(1.0, f.y, 1e-12)
    }

    @Test
    fun `rotation vector conversion handles both HAL forms`() {
        val q = PoseMaths.upright(123.0, 17.0)
        val four = floatArrayOf(q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), q.w.toFloat(), -1f)
        val three = floatArrayOf(q.x.toFloat(), q.y.toFloat(), q.z.toFloat())
        val a = DoubleArray(4); val b = DoubleArray(4)
        Frames.rotationVectorToQuat(four, 5, a)
        Frames.rotationVectorToQuat(three, 3, b)
        val qa = Quat.fromArray(a); val qb = Quat.fromArray(b)
        val expect = if (q.w < 0) Quat(-q.w, -q.x, -q.y, -q.z) else q // 3-form assumes w >= 0
        assertEquals(0.0, qa.angleTo(q), 1e-6)
        assertEquals(0.0, qb.angleTo(expect), 1e-6)
    }

    @Test
    fun `view matrix puts what the camera faces at screen centre`() {
        val scene = DoubleArray(4)
        Frames.enuToScene(arr(PoseMaths.upright(90.0)), scene) // facing east
        val view = Mat4.viewFromCameraQuat(FloatArray(16), scene)
        val proj = Mat4.perspective(FloatArray(16), 1.5f, 0.7f, 0.1f, 4000f)
        val vp = Mat4.multiply(FloatArray(16), proj, view)
        val clip = Mat4.transformPoint(vp, 100f, 0f, 0f, FloatArray(4)) // 100 m east
        assertEquals(0f, clip[0] / clip[3], 1e-5f)
        assertEquals(0f, clip[1] / clip[3], 1e-5f)
        assertEquals(true, clip[3] > 0)
        // Something 100 m west is behind the camera.
        val behind = Mat4.transformPoint(vp, -100f, 0f, 0f, FloatArray(4))
        assertEquals(true, behind[3] < 0)
    }
}
