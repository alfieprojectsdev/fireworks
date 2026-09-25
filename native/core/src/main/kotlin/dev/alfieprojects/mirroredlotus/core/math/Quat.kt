package dev.alfieprojects.mirroredlotus.core.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit quaternion `w + xi + yj + zk`.
 *
 * Orientation quaternions here rotate a vector from the *body* frame (device or
 * camera) into the *world* frame: `v_world = q * v_body * q^-1`. That is the
 * convention of Android's rotation-vector sensors and of Three.js's
 * `Object3D.quaternion`, which is why the web version could hand one straight
 * to the other - once the two worlds agree on their axes (see Frames).
 */
data class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {

    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conjugate() = Quat(w, -x, -y, -z)

    fun norm() = sqrt(w * w + x * x + y * y + z * z)

    fun normalized(): Quat {
        val n = norm()
        return if (n < 1e-12) IDENTITY else Quat(w / n, x / n, y / n, z / n)
    }

    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(u x v) + 2u x (u x v), with u the vector part.
        val tx = 2.0 * (y * v.z - z * v.y)
        val ty = 2.0 * (z * v.x - x * v.z)
        val tz = 2.0 * (x * v.y - y * v.x)
        return Vec3(
            v.x + w * tx + (y * tz - z * ty),
            v.y + w * ty + (z * tx - x * tz),
            v.z + w * tz + (x * ty - y * tx),
        )
    }

    /**
     * Rotation angle in `[0, PI]`, via `atan2` rather than `acos(w)`: sensor
     * steps are milliradians, where `acos` of a number that close to 1 collapses
     * to zero (the stable-still lesson).
     */
    fun angle(): Double {
        val v = sqrt(x * x + y * y + z * z)
        if (v < 1e-300) return 0.0
        return 2.0 * atan2(v, abs(w))
    }

    /** Angle of the rotation that takes this orientation to [o]. */
    fun angleTo(o: Quat): Double = (conjugate() * o).angle()

    fun toArray(out: DoubleArray, offset: Int = 0) {
        out[offset] = w; out[offset + 1] = x; out[offset + 2] = y; out[offset + 3] = z
    }

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        fun fromAxisAngle(ax: Double, ay: Double, az: Double, radians: Double): Quat {
            val n = sqrt(ax * ax + ay * ay + az * az)
            if (n < 1e-12) return IDENTITY
            val s = sin(radians / 2) / n
            return Quat(cos(radians / 2), ax * s, ay * s, az * s)
        }

        /**
         * Angle of the rotation between orientations [p] and [q] (`[w, x, y, z]`),
         * allocation-free: atan2 of conj(p)·q's vector part over its scalar part,
         * since sensor steps are milliradians, where acos rounds to zero.
         */
        fun angleBetween(p: DoubleArray, q: DoubleArray): Double {
            val w = p[0] * q[0] + p[1] * q[1] + p[2] * q[2] + p[3] * q[3]
            val x = p[0] * q[1] - p[1] * q[0] - p[2] * q[3] + p[3] * q[2]
            val y = p[0] * q[2] + p[1] * q[3] - p[2] * q[0] - p[3] * q[1]
            val z = p[0] * q[3] - p[1] * q[2] + p[2] * q[1] - p[3] * q[0]
            return 2.0 * atan2(sqrt(x * x + y * y + z * z), abs(w))
        }

        fun fromArray(a: DoubleArray, offset: Int = 0) =
            Quat(a[offset], a[offset + 1], a[offset + 2], a[offset + 3])

        fun slerp(a: Quat, b: Quat, t: Double): Quat {
            val out = DoubleArray(4)
            val aa = DoubleArray(4).also { a.toArray(it) }
            val bb = DoubleArray(4).also { b.toArray(it) }
            slerpInto(aa, 0, bb, 0, t, out)
            return fromArray(out)
        }

        /**
         * Allocation-free shortest-arc slerp on `[w,x,y,z]` arrays, for the
         * sensor and render threads. [t] is clamped to `[0, 1]`.
         */
        fun slerpInto(a: DoubleArray, ao: Int, b: DoubleArray, bo: Int, t: Double, out: DoubleArray) {
            val u = t.coerceIn(0.0, 1.0)
            val aw = a[ao]; val ax = a[ao + 1]; val ay = a[ao + 2]; val az = a[ao + 3]
            var bw = b[bo]; var bx = b[bo + 1]; var by = b[bo + 2]; var bz = b[bo + 3]
            var cosom = aw * bw + ax * bx + ay * by + az * bz
            if (cosom < 0.0) {
                cosom = -cosom; bw = -bw; bx = -bx; by = -by; bz = -bz
            }
            val sa: Double
            val sb: Double
            if (cosom > 0.9995) {
                sa = 1.0 - u; sb = u
            } else {
                val omega = acos(cosom.coerceIn(-1.0, 1.0))
                val sinOm = sin(omega)
                sa = sin((1.0 - u) * omega) / sinOm
                sb = sin(u * omega) / sinOm
            }
            val w = aw * sa + bw * sb
            val x = ax * sa + bx * sb
            val y = ay * sa + by * sb
            val z = az * sa + bz * sb
            val n = sqrt(w * w + x * x + y * y + z * z)
            out[0] = w / n; out[1] = x / n; out[2] = y / n; out[3] = z / n
        }

        /** Allocation-free `out = a * b` on `[w,x,y,z]` arrays. `out` may alias neither input. */
        fun multiplyInto(a: DoubleArray, b: DoubleArray, out: DoubleArray) {
            val aw = a[0]; val ax = a[1]; val ay = a[2]; val az = a[3]
            val bw = b[0]; val bx = b[1]; val by = b[2]; val bz = b[3]
            out[0] = aw * bw - ax * bx - ay * by - az * bz
            out[1] = aw * bx + ax * bw + ay * bz - az * by
            out[2] = aw * by - ax * bz + ay * bw + az * bx
            out[3] = aw * bz + ax * by - ay * bx + az * bw
        }
    }
}
