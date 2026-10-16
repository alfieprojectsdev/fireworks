package dev.alfieprojects.mirroredlotus.core.math

import kotlin.math.cos
import kotlin.math.sin

/**
 * Column-major 4x4 matrices in plain `FloatArray(16)`, laid out exactly as
 * `glUniformMatrix4fv(..., transpose = false, ...)` expects. Element (row r,
 * column c) lives at `c * 4 + r`.
 *
 * Everything writes into a caller-owned array: these run on the render thread
 * every frame, and the render thread allocates nothing per frame.
 */
object Mat4 {

    fun identity(out: FloatArray): FloatArray {
        out.fill(0f)
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
        return out
    }

    /** `out = a * b`. [out] must not alias [a] or [b]. */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray): FloatArray {
        for (c in 0 until 4) {
            val b0 = b[c * 4]; val b1 = b[c * 4 + 1]; val b2 = b[c * 4 + 2]; val b3 = b[c * 4 + 3]
            for (r in 0 until 4) {
                out[c * 4 + r] = a[r] * b0 + a[4 + r] * b1 + a[8 + r] * b2 + a[12 + r] * b3
            }
        }
        return out
    }

    /**
     * OpenGL perspective projection from focal lengths already expressed in
     * normalised device units (`2 * f_px / viewport_px`), principal point at the
     * centre. Building it from focal length rather than a field-of-view angle is
     * what lets the virtual camera match the physical lens exactly.
     */
    fun perspective(out: FloatArray, fxNdc: Float, fyNdc: Float, near: Float, far: Float): FloatArray {
        out.fill(0f)
        out[0] = fxNdc
        out[5] = fyNdc
        out[10] = -(far + near) / (far - near)
        out[11] = -1f
        out[14] = -2f * far * near / (far - near)
        return out
    }

    /** Rotation matrix of a `[w,x,y,z]` quaternion (body -> world). */
    fun fromQuat(out: FloatArray, q: DoubleArray): FloatArray {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        out[0] = (1 - 2 * (yy + zz)).toFloat(); out[1] = (2 * (xy + wz)).toFloat(); out[2] = (2 * (xz - wy)).toFloat(); out[3] = 0f
        out[4] = (2 * (xy - wz)).toFloat(); out[5] = (1 - 2 * (xx + zz)).toFloat(); out[6] = (2 * (yz + wx)).toFloat(); out[7] = 0f
        out[8] = (2 * (xz + wy)).toFloat(); out[9] = (2 * (yz - wx)).toFloat(); out[10] = (1 - 2 * (xx + yy)).toFloat(); out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
        return out
    }

    /**
     * View matrix of a camera sitting at the world origin with orientation
     * [q] (camera -> world). The camera never translates in this app: the world
     * is re-anchored around the wearer instead, so the view is the inverse
     * rotation, i.e. the transpose.
     */
    fun viewFromCameraQuat(out: FloatArray, q: DoubleArray): FloatArray {
        val w = q[0]; val x = -q[1]; val y = -q[2]; val z = -q[3] // conjugate = inverse
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        out[0] = (1 - 2 * (yy + zz)).toFloat(); out[1] = (2 * (xy + wz)).toFloat(); out[2] = (2 * (xz - wy)).toFloat(); out[3] = 0f
        out[4] = (2 * (xy - wz)).toFloat(); out[5] = (1 - 2 * (xx + zz)).toFloat(); out[6] = (2 * (yz + wx)).toFloat(); out[7] = 0f
        out[8] = (2 * (xz + wy)).toFloat(); out[9] = (2 * (yz - wx)).toFloat(); out[10] = (1 - 2 * (xx + yy)).toFloat(); out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
        return out
    }

    /**
     * `out = T(tx,ty,tz) * Ry(ry) * Rx(rx) * S(scaleX,scaleY,scaleZ)`: every
     * placement the scene needs (the pylons share one unit plane, scaled).
     */
    fun translateRotateYX(
        out: FloatArray,
        tx: Float, ty: Float, tz: Float,
        ry: Float, rx: Float = 0f,
        scaleX: Float = 1f, scaleY: Float = 1f, scaleZ: Float = 1f,
    ): FloatArray {
        val cy = cos(ry); val sy = sin(ry)
        val cx = cos(rx); val sx = sin(rx)
        // Ry * Rx, columns:
        // c0 = Ry * (1,0,0) = ( cy, 0, -sy)
        // c1 = Ry * (0,cx,sx) = ( sy*sx, cx, cy*sx)
        // c2 = Ry * (0,-sx,cx) = ( sy*cx, -sx, cy*cx)
        out[0] = cy * scaleX; out[1] = 0f; out[2] = -sy * scaleX; out[3] = 0f
        out[4] = sy * sx * scaleY; out[5] = cx * scaleY; out[6] = cy * sx * scaleY; out[7] = 0f
        out[8] = sy * cx * scaleZ; out[9] = -sx * scaleZ; out[10] = cy * cx * scaleZ; out[11] = 0f
        out[12] = tx; out[13] = ty; out[14] = tz; out[15] = 1f
        return out
    }

    /** Transforms a point (w = 1) and writes homogeneous `[x,y,z,w]` into [out]. */
    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray): FloatArray {
        for (r in 0 until 4) out[r] = m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r]
        return out
    }
}
