package dev.alfieprojects.mirroredlotus.core.render

import dev.alfieprojects.mirroredlotus.core.camera.CameraIntrinsics
import dev.alfieprojects.mirroredlotus.core.camera.CameraProjection
import dev.alfieprojects.mirroredlotus.core.fx.FireworkSystem
import dev.alfieprojects.mirroredlotus.core.fx.Rng
import dev.alfieprojects.mirroredlotus.core.geo.Geo
import dev.alfieprojects.mirroredlotus.core.math.Mat4
import dev.alfieprojects.mirroredlotus.core.orientation.Frames
import dev.alfieprojects.mirroredlotus.core.orientation.PoseMaths
import dev.alfieprojects.mirroredlotus.core.site.Sites
import dev.alfieprojects.mirroredlotus.core.story.ExperienceDirector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * End to end, the way the renderer composes a frame: GPS -> director placement
 * -> phone pose -> view -> the A07's real lens -> screen. If the ENU/scene
 * frames, the placement matrices or the projection disagree, the monument
 * lands somewhere other than where the church is, and these fail.
 */
class SceneProjectionTest {

    private val a07 = CameraIntrinsics(3.98, 5.22, 3.93, 90)
    private val fit = CameraProjection.fit(a07, 1280, 720, 720, 1600)

    private fun viewProj(headingDeg: Double, pitchDeg: Double): FloatArray {
        val scene = DoubleArray(4)
        Frames.enuToScene(PoseMaths.arr(PoseMaths.upright(headingDeg, pitchDeg)), scene)
        val view = Mat4.viewFromCameraQuat(FloatArray(16), scene)
        val proj = Mat4.perspective(FloatArray(16), fit.fxNdc, fit.fyNdc, 0.1f, 4000f)
        return Mat4.multiply(FloatArray(16), proj, view)
    }

    /** NDC x, y of model-space point p under model matrix m, or null if behind the camera. */
    private fun ndc(vp: FloatArray, m: FloatArray, x: Float, y: Float, z: Float): FloatArray? {
        val mvp = Mat4.multiply(FloatArray(16), vp, m)
        val c = Mat4.transformPoint(mvp, x, y, z, FloatArray(4))
        if (c[3] <= 1e-4f) return null
        return floatArrayOf(c[0] / c[3], c[1] / c[3])
    }

    private fun onScreen(p: FloatArray?) = p != null && abs(p[0]) <= 1f && abs(p[1]) <= 1f

    private fun directorAt(bearingFromSiteDeg: Double, metres: Double): ExperienceDirector {
        val church = Sites.CHURCH
        val user = Geo.destination(church.lat, church.lng, Math.toRadians(bearingFromSiteDeg), metres)
        return ExperienceDirector(Rng(1)).also { it.start(church, user.lat, user.lng, testMode = false) }
    }

    private fun crownCentre(d: ExperienceDirector): FloatArray =
        MonumentPlacement.crown(FloatArray(16), d.groupX.toFloat(), d.groupY.toFloat(), d.groupZ.toFloat(), 0f)

    @Test
    fun `facing the church from the south puts the crown dead ahead`() {
        val d = directorAt(180.0, 300.0) // wearer 300 m south of the church
        val p = ndc(viewProj(0.0, 10.0), crownCentre(d), 0f, 0f, 0f)!!
        assertEquals(0f, p[0], 1e-3f)
        // Crown centre is 56 m up at 300 m: 10.6 degrees, just above a 10 degree pitch.
        assertTrue("y=${p[1]}", p[1] in 0.005f..0.03f)
    }

    @Test
    fun `the same scene from the east needs the phone turned west`() {
        val d = directorAt(90.0, 300.0) // wearer east of the church
        assertTrue(onScreen(ndc(viewProj(270.0, 10.0), crownCentre(d), 0f, 0f, 0f)))
        assertTrue(!onScreen(ndc(viewProj(0.0, 10.0), crownCentre(d), 0f, 0f, 0f)))
        assertTrue(!onScreen(ndc(viewProj(90.0, 10.0), crownCentre(d), 0f, 0f, 0f)))
    }

    @Test
    fun `the narrow portrait field frames the church within about 16 degrees of heading`() {
        val d = directorAt(180.0, 300.0)
        assertTrue(onScreen(ndc(viewProj(15.0, 10.0), crownCentre(d), 0f, 0f, 0f)))
        assertTrue(!onScreen(ndc(viewProj(20.0, 10.0), crownCentre(d), 0f, 0f, 0f)))
        // Turning right moves it left on screen.
        assertTrue(ndc(viewProj(10.0, 10.0), crownCentre(d), 0f, 0f, 0f)!![0] < 0f)
    }

    @Test
    fun `crown text reads left to right from outside`() {
        val d = directorAt(180.0, 300.0)
        val m = crownCentre(d)
        val mesh = Meshes.cylinder(SceneLayout.CROWN_RADIUS, SceneLayout.CROWN_HEIGHT, SceneLayout.CROWN_SEGMENTS)
        val vp = viewProj(0.0, 10.0)
        // Near side faces south (theta = 0 sits on +Z). Columns 0 and 1 of the top row:
        val a = ndc(vp, m, mesh.positions[0], mesh.positions[1], mesh.positions[2])!!
        val b = ndc(vp, m, mesh.positions[3], mesh.positions[4], mesh.positions[5])!!
        assertTrue("s grows to the right", b[0] > a[0] && mesh.uvs[2] > mesh.uvs[0])
        // And t = 0 (the image's top) is the higher edge.
        val bottom = ndc(vp, m, mesh.positions[65 * 3], mesh.positions[65 * 3 + 1], mesh.positions[65 * 3 + 2])!!
        assertTrue(a[1] > bottom[1])
    }

    @Test
    fun `the pylon facing the wearer is upright and readable`() {
        val d = directorAt(180.0, 60.0) // 25 m from the south pylon's face
        val m = MonumentPlacement.pylon(FloatArray(16), 0, d.groupX.toFloat(), d.groupY.toFloat(), d.groupZ.toFloat(), 3)
        val vp = viewProj(0.0, 10.0)
        val centre = ndc(vp, m, 0f, 0f, 0f)!!
        assertTrue(onScreen(centre))
        val right = ndc(vp, m, 0.5f, 0f, 0f)!!
        val top = ndc(vp, m, 0f, 0.5f, 0f)!!
        assertTrue("image right is screen right", right[0] > centre[0])
        assertTrue("image top is screen top", top[1] > centre[1])
    }

    /**
     * The reason for the digits-only redesign: from a car with the phone held
     * about level, the whole number must be in frame and big enough to read
     * from the moment the pylons light until 60 m out. A07: 1600 px tall.
     */
    @Test
    fun `a three-digit stat is in frame and readable from 300 m to 60 m`() {
        val vp = viewProj(0.0, 10.0)
        for (metres in listOf(300.0, 250.0, 150.0, 100.0, 60.0)) {
            val d = directorAt(180.0, metres)
            val m = MonumentPlacement.pylon(FloatArray(16), 0, d.groupX.toFloat(), d.groupY.toFloat(), d.groupZ.toFloat(), 3)
            val top = ndc(vp, m, 0f, 0.5f, 0f)
            val bottom = ndc(vp, m, 0f, -0.5f, 0f)
            assertTrue("top on screen at $metres m", onScreen(top))
            assertTrue("bottom on screen at $metres m", onScreen(bottom))
            // One 4 m cell holds a digit about 0.65 of its height tall.
            val digitPx = (top!![1] - bottom!![1]) / 2f * 1600f / 3f * 0.65f
            if (metres <= 180.0) assertTrue("digit ${digitPx}px at $metres m", digitPx >= 18f)
        }
    }

    @Test
    fun `the ground ring sits on the ground under the church`() {
        val d = directorAt(180.0, 100.0)
        val m = MonumentPlacement.ground(FloatArray(16), d.groupX.toFloat(), d.groupY.toFloat(), d.groupZ.toFloat())
        val p = Mat4.transformPoint(m, 24f, 0f, 0f, FloatArray(4))
        assertEquals(0f, p[1], 1e-4f) // world ground level
        assertEquals(-100f, p[2], 0.5f)
    }

    /**
     * The portrait field is narrow (~33 degrees wide on the A07; the web build's
     * fixed 60-degree vertical field gave ~30), while rockets launch across +-43
     * degrees around the monument. So at any one heading only a share of the
     * sparks is on screen and the wearer pans to follow the show - by design.
     * (Big shells also throw sparks 100+ m, so from 40 m some pass overhead.)
     */
    @Test
    fun `in test mode the show is ahead of the wearer`() {
        val d = ExperienceDirector(Rng(2))
        d.start(Sites.CHURCH, 0.0, 0.0, testMode = true, forwardX = 0.0, forwardZ = -1.0)
        repeat(60 * 20) { d.tick() }
        val out = FloatArray(FireworkSystem.MAX_VERTICES * FireworkSystem.FLOATS_PER_VERTEX)
        val n = d.fireworks.writeVertices(out)
        assertTrue(n > 0)
        val vp = viewProj(0.0, 25.0)
        val identity = Mat4.identity(FloatArray(16))
        var visible = 0
        for (v in 0 until n) {
            if (onScreen(ndc(vp, identity, out[v * 6], out[v * 6 + 1], out[v * 6 + 2]))) visible++
        }
        assertTrue("visible $visible of $n", visible > n / 6)
    }
}
