package dev.alfieprojects.mirroredlotus.core.fieldtest

import dev.alfieprojects.mirroredlotus.core.geo.Geo
import dev.alfieprojects.mirroredlotus.core.track.WalkHeading
import kotlin.math.abs
import kotlin.math.atan2

/**
 * What the app hands the field-test runner on every UI tick (10 Hz). All
 * nullable fields are "not known yet" (no pose, no fix, no monument placed).
 */
class FieldSample(
    /** Monotonic ms (`elapsedRealtime`). */
    val tMs: Long,
    /** Newest device-to-ENU orientation, `[w, x, y, z]`, or null before the first sample. */
    val quat: DoubleArray?,
    /** Footsteps the detector has credited so far (cumulative). */
    val steps: Long,
    val fusedLat: Double? = null,
    val fusedLng: Double? = null,
    val sigmaM: Double? = null,
    /** Camera heading minus monument bearing (the HUD's Δ), or null before placement. */
    val deltaDeg: Double? = null,
    val renderFps: Double = 0.0,
    val cameraFps: Double = 0.0,
    val photonMs: Double = 0.0,
    val thermalStatus: Int = -1,
    val headroom: Double = Double.NaN,
    /** Raw `ROTATION_VECTOR` heading and the fusion's yaw correction, for the compass test. */
    val compassHeadingDeg: Double? = null,
    val yawCorrectionDeg: Double? = null,
)

/** Pose tests the scripts wait on, from a device-to-ENU quaternion. */
object FieldPose {
    /** Face-up means the camera points within 15 degrees of straight down. */
    private const val FACE_UP_UP_COMPONENT = -0.9659 // -cos 15°
    /** Upright: camera within ~37° of level, screen top within ~53° of vertical. */
    private const val UPRIGHT_MAX_CAMERA_UP = 0.6
    private const val UPRIGHT_MIN_TOP_UP = 0.6

    /** Up component of the camera's viewing direction (device -Z) in ENU. */
    fun cameraUp(q: DoubleArray): Double {
        val x = q[1]; val y = q[2]
        return -(1 - 2 * (x * x + y * y))
    }

    /** Up component of the screen's top edge (device +Y) in ENU. */
    fun topUp(q: DoubleArray): Double {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        return 2 * (y * z + w * x)
    }

    fun faceUp(q: DoubleArray): Boolean = cameraUp(q) < FACE_UP_UP_COMPONENT

    fun upright(q: DoubleArray): Boolean = abs(cameraUp(q)) < UPRIGHT_MAX_CAMERA_UP && topUp(q) > UPRIGHT_MIN_TOP_UP

    /** The heading a step or a test-mode placement would use (`WalkHeading`), degrees; null if none. */
    fun headingDeg(q: DoubleArray, scratch: DoubleArray = DoubleArray(2)): Double? {
        if (!WalkHeading.of(q, scratch)) return null
        return Geo.normaliseDeg(Math.toDegrees(atan2(scratch[0], scratch[1])))
    }
}
