package dev.alfieprojects.mirroredlotus.core.math

import kotlin.math.sqrt

/** Immutable 3-vector for code off the per-frame hot paths. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun length() = sqrt(x * x + y * y + z * z)
    fun distanceTo(o: Vec3) = (this - o).length()

    fun normalized(): Vec3 {
        val n = length()
        return if (n < 1e-12) ZERO else Vec3(x / n, y / n, z / n)
    }

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}
