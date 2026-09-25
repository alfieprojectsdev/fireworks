package dev.alfieprojects.mirroredlotus.core.orientation

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The three frames the pose passes through, all as `[w,x,y,z]` quaternions:
 *
 * - **device**: Android sensor axes. X right, Y toward the top of the screen,
 *   Z out of the screen toward the wearer. The rear camera looks down -Z, so in
 *   a portrait-locked activity the device frame *is* the camera frame, with the
 *   same axes Three.js and OpenGL use for a camera (look -Z, up +Y).
 * - **ENU**: the rotation-vector sensors' world. X east, Y north, Z up.
 * - **scene**: the web build's world. X east, Y up, Z south (north is -Z).
 *
 * The web build's native path wrote the ENU quaternion straight into the
 * Three.js camera, skipping the ENU->scene step; with the phone held upright
 * that aims the virtual camera at the zenith. Three's DeviceOrientationControls
 * carries this same -90 degrees-about-X factor internally, which is why the
 * pure-web fallback path never showed it.
 */
object Frames {
    private val S = sqrt(0.5)

    /** -90 degrees about X: maps ENU north (0,1,0) to scene -Z and ENU up to scene +Y. */
    val ENU_TO_SCENE = doubleArrayOf(S, -S, 0.0, 0.0)

    /**
     * Rotation-vector `values` to a device->ENU quaternion, following
     * `SensorManager.getQuaternionFromVector` (including its handling of the
     * three-component form some HALs deliver).
     */
    fun rotationVectorToQuat(values: FloatArray, count: Int, out: DoubleArray) {
        val x = values[0].toDouble(); val y = values[1].toDouble(); val z = values[2].toDouble()
        val w = if (count >= 4) {
            values[3].toDouble()
        } else {
            val t = 1 - x * x - y * y - z * z
            if (t > 0) sqrt(t) else 0.0
        }
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n < 1e-9) {
            out[0] = 1.0; out[1] = 0.0; out[2] = 0.0; out[3] = 0.0
            return
        }
        out[0] = w / n; out[1] = x / n; out[2] = y / n; out[3] = z / n
    }

    /** `out = ENU_TO_SCENE * qEnu`: device->ENU becomes camera->scene. */
    fun enuToScene(qEnu: DoubleArray, out: DoubleArray) {
        val aw = S; val ax = -S
        val bw = qEnu[0]; val bx = qEnu[1]; val by = qEnu[2]; val bz = qEnu[3]
        out[0] = aw * bw - ax * bx
        out[1] = aw * bx + ax * bw
        out[2] = aw * by - ax * bz
        out[3] = aw * bz + ax * by
    }

    /** Camera forward (-Z of the camera) in scene axes, into `out[0..2]`. */
    fun forward(q: DoubleArray, out: DoubleArray) {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        // Third column of the rotation matrix, negated.
        out[0] = -(2 * (x * z + w * y))
        out[1] = -(2 * (y * z - w * x))
        out[2] = -(1 - 2 * (x * x + y * y))
    }

    /** Compass heading of a scene-frame direction, degrees clockwise from north, `[0, 360)`. */
    fun headingDeg(fwdX: Double, fwdZ: Double): Double {
        val deg = Math.toDegrees(atan2(fwdX, -fwdZ))
        return ((deg % 360) + 360) % 360
    }
}
