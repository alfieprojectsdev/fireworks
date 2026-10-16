package dev.alfieprojects.mirroredlotus.core.orientation

import dev.alfieprojects.mirroredlotus.core.math.Quat
import kotlin.math.sqrt

/** Test helpers: build device->ENU orientations the way a real phone reports them. */
object PoseMaths {

    /**
     * Quaternion from a rotation matrix given by its columns: where the device's
     * X, Y and Z axes point in ENU.
     */
    fun fromColumns(x: DoubleArray, y: DoubleArray, z: DoubleArray): Quat {
        val m00 = x[0]; val m10 = x[1]; val m20 = x[2]
        val m01 = y[0]; val m11 = y[1]; val m21 = y[2]
        val m02 = z[0]; val m12 = z[1]; val m22 = z[2]
        val tr = m00 + m11 + m22
        return if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            Quat(0.25 * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1.0 + m00 - m11 - m22) * 2
            Quat((m21 - m12) / s, 0.25 * s, (m01 + m10) / s, (m02 + m20) / s)
        } else if (m11 > m22) {
            val s = sqrt(1.0 + m11 - m00 - m22) * 2
            Quat((m02 - m20) / s, (m01 + m10) / s, 0.25 * s, (m12 + m21) / s)
        } else {
            val s = sqrt(1.0 + m22 - m00 - m11) * 2
            Quat((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25 * s)
        }.normalized()
    }

    /**
     * A phone held upright in portrait with the rear camera facing compass
     * heading [headingDeg] (clockwise from north), pitched up by [pitchDeg].
     */
    fun upright(headingDeg: Double, pitchDeg: Double = 0.0): Quat {
        // Upright facing north: device X -> east, Y -> up, Z -> south.
        val base = fromColumns(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(0.0, -1.0, 0.0),
        )
        // Heading turns clockwise seen from above = negative rotation about ENU up.
        val yaw = Quat.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(-headingDeg))
        // Pitching the camera up tilts about the device's own X axis.
        val pitch = Quat.fromAxisAngle(1.0, 0.0, 0.0, Math.toRadians(pitchDeg))
        return yaw * base * pitch
    }

    fun arr(q: Quat) = DoubleArray(4).also { q.toArray(it) }
}
