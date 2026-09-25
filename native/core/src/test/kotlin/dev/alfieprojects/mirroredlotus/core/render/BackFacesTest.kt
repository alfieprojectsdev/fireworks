package dev.alfieprojects.mirroredlotus.core.render

import dev.alfieprojects.mirroredlotus.core.camera.CameraIntrinsics
import dev.alfieprojects.mirroredlotus.core.camera.CameraProjection
import dev.alfieprojects.mirroredlotus.core.math.Mat4
import dev.alfieprojects.mirroredlotus.core.orientation.Frames
import dev.alfieprojects.mirroredlotus.core.orientation.PoseMaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `mesh.frag` hides or mirrors back faces using `gl_FrontFacing`, which is only
 * right if the meshes wind their outward faces counter-clockwise on screen.
 * These tests project real triangles the way the renderer does and check it.
 */
class BackFacesTest {

    private val fit = CameraProjection.fit(CameraIntrinsics(3.98, 5.22, 3.93, 90), 1280, 720, 720, 1600)

    private fun viewProj(headingDeg: Double, pitchDeg: Double): FloatArray {
        val scene = DoubleArray(4)
        Frames.enuToScene(PoseMaths.arr(PoseMaths.upright(headingDeg, pitchDeg)), scene)
        val view = Mat4.viewFromCameraQuat(FloatArray(16), scene)
        val proj = Mat4.perspective(FloatArray(16), fit.fxNdc, fit.fyNdc, 0.1f, 4000f)
        return Mat4.multiply(FloatArray(16), proj, view)
    }

    /** Screen-space (NDC) x, y of mesh vertex [v]. */
    private fun ndc(mvp: FloatArray, mesh: MeshData, v: Int): FloatArray {
        val c = Mat4.transformPoint(mvp, mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2], FloatArray(4))
        assertTrue("vertex $v in front of the camera", c[3] > 0f)
        return floatArrayOf(c[0] / c[3], c[1] / c[3])
    }

    /** True when triangle [tri] winds counter-clockwise on screen: GL's front face. */
    private fun frontFacing(mvp: FloatArray, mesh: MeshData, tri: Int): Boolean {
        val a = ndc(mvp, mesh, mesh.indices[tri * 3].toInt())
        val b = ndc(mvp, mesh, mesh.indices[tri * 3 + 1].toInt())
        val c = ndc(mvp, mesh, mesh.indices[tri * 3 + 2].toInt())
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]) > 0f
    }

    private val crown = Meshes.cylinder(SceneLayout.CROWN_RADIUS, SceneLayout.CROWN_HEIGHT, SceneLayout.CROWN_SEGMENTS)

    @Test
    fun `seen from outside, the near side of the crown is front-facing and the far side is not`() {
        // Wearer 300 m south of the crown, facing it. Triangles 0-1 are column 0
        // (theta = 0, the south face); column 32 is theta = pi, the north face.
        val model = MonumentPlacement.crown(FloatArray(16), 0f, 15f, -300f, 0f)
        val mvp = Mat4.multiply(FloatArray(16), viewProj(0.0, 10.0), model)
        assertTrue(frontFacing(mvp, crown, 0))
        assertTrue(frontFacing(mvp, crown, 1))
        assertTrue(!frontFacing(mvp, crown, 32 * 2))
        assertTrue(!frontFacing(mvp, crown, 32 * 2 + 1))
    }

    @Test
    fun `seen from inside, the crown is all back faces and its text reads mirrored`() {
        // Wearer at the church, looking up-north at the far (north) wall.
        val model = MonumentPlacement.crown(FloatArray(16), 0f, 15f, 0f, 0f)
        val mvp = Mat4.multiply(FloatArray(16), viewProj(0.0, 55.0), model)
        assertTrue(!frontFacing(mvp, crown, 32 * 2))
        // Columns 31 and 33 straddle north: s grows from 31 to 33, but on screen
        // 33 lands to the *left* of 31. That's mirrored text, so MIRROR flips s.
        val left = ndc(mvp, crown, 31)
        val right = ndc(mvp, crown, 33)
        assertTrue(crown.uvs[33 * 2] > crown.uvs[31 * 2])
        assertTrue(right[0] < left[0])
        assertEquals(BackFaces.MIRROR, BackFaces.forCylinderText(0.0, 0.0, SceneLayout.CROWN_RADIUS))
    }

    @Test
    fun `a pylon's front faces the way it reads`() {
        val plane = Meshes.plane(1f, 1f)
        // The south pylon, seen from the south (outside the ring) and from the north (inside).
        val model = MonumentPlacement.pylon(FloatArray(16), 0, 0f, 15f, -60f, 6.25f)
        val fromSouth = Mat4.multiply(FloatArray(16), viewProj(0.0, 30.0), model)
        assertTrue(frontFacing(fromSouth, plane, 0) && frontFacing(fromSouth, plane, 1))
        // From the monument's north side, looking back south at the same pylon.
        val behindModel = MonumentPlacement.pylon(FloatArray(16), 0, 0f, 15f, 60f, 6.25f)
        val fromNorth = Mat4.multiply(FloatArray(16), viewProj(180.0, 30.0), behindModel)
        assertTrue(!frontFacing(fromNorth, plane, 0) && !frontFacing(fromNorth, plane, 1))
    }

    @Test
    fun `text cylinders mirror only once the viewer is inside them`() {
        assertEquals(BackFaces.HIDE, BackFaces.forCylinderText(0.0, -300.0, SceneLayout.CROWN_RADIUS))
        assertEquals(BackFaces.HIDE, BackFaces.forCylinderText(24.8, 24.8, SceneLayout.CROWN_RADIUS)) // 35.07 m
        assertEquals(BackFaces.MIRROR, BackFaces.forCylinderText(24.7, 24.7, SceneLayout.CROWN_RADIUS)) // 34.93 m
        assertEquals(BackFaces.HIDE, BackFaces.forCylinderText(0.0, -20.0, SceneLayout.MESSAGE_RADIUS))
        assertEquals(BackFaces.MIRROR, BackFaces.forCylinderText(0.0, -5.0, SceneLayout.MESSAGE_RADIUS))
        assertEquals(BackFaces.HIDE, BackFaces.FOR_PANEL_TEXT)
    }
}
