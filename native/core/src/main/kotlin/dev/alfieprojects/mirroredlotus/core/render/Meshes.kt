package dev.alfieprojects.mirroredlotus.core.render

import kotlin.math.cos
import kotlin.math.sin

/**
 * Indexed triangle mesh: `positions` xyz, `uvs` st (t = 0 at the *top* of the
 * texture image, matching how `GLUtils.texImage2D` uploads a Bitmap), 16-bit
 * indices. Everything here is drawn double-sided, as every web material was.
 */
class MeshData(val positions: FloatArray, val uvs: FloatArray, val indices: ShortArray) {
    val vertexCount get() = positions.size / 3
}

/**
 * Geometry generators reproducing Three.js r128's `CylinderGeometry`,
 * `RingGeometry` and `PlaneGeometry` vertex layout and UVs, so textures wrap
 * and read exactly as they did in the web build.
 */
object Meshes {

    /**
     * Open-ended cylinder about +Y centred on the origin. Around the side,
     * `x = r sin(theta)`, `z = r cos(theta)` and s = theta / 2pi, which makes the
     * texture read left-to-right from outside. Three's `v = 1 - row/rows` with a
     * flipped upload becomes `t = row/rows` here.
     */
    fun cylinder(radius: Float, height: Float, radialSegments: Int, heightSegments: Int = 1): MeshData {
        val cols = radialSegments + 1
        val rows = heightSegments + 1
        val pos = FloatArray(cols * rows * 3)
        val uv = FloatArray(cols * rows * 2)
        val half = height / 2
        var p = 0
        var u = 0
        for (row in 0 until rows) {
            val g = row.toFloat() / heightSegments
            val y = -g * height + half
            for (col in 0 until cols) {
                val s = col.toFloat() / radialSegments
                val theta = s * 2.0 * Math.PI
                pos[p++] = (radius * sin(theta)).toFloat()
                pos[p++] = y
                pos[p++] = (radius * cos(theta)).toFloat()
                uv[u++] = s
                uv[u++] = g
            }
        }
        val idx = ShortArray(radialSegments * heightSegments * 6)
        var i = 0
        for (col in 0 until radialSegments) {
            for (row in 0 until heightSegments) {
                val a = row * cols + col
                val b = (row + 1) * cols + col
                val c = (row + 1) * cols + col + 1
                val d = row * cols + col + 1
                idx[i++] = a.toShort(); idx[i++] = b.toShort(); idx[i++] = d.toShort()
                idx[i++] = b.toShort(); idx[i++] = c.toShort(); idx[i++] = d.toShort()
            }
        }
        return MeshData(pos, uv, idx)
    }

    /** Flat annulus in the XY plane (rotate it -90 degrees about X to lay it on the ground). */
    fun ring(inner: Float, outer: Float, thetaSegments: Int, phiSegments: Int = 1): MeshData {
        val cols = thetaSegments + 1
        val rows = phiSegments + 1
        val pos = FloatArray(cols * rows * 3)
        val uv = FloatArray(cols * rows * 2)
        var p = 0
        var u = 0
        for (j in 0 until rows) {
            val r = inner + j * (outer - inner) / phiSegments
            for (i in 0 until cols) {
                val a = i.toDouble() / thetaSegments * 2.0 * Math.PI
                val x = (r * cos(a)).toFloat()
                val y = (r * sin(a)).toFloat()
                pos[p++] = x; pos[p++] = y; pos[p++] = 0f
                uv[u++] = (x / outer + 1) / 2
                uv[u++] = 1 - (y / outer + 1) / 2
            }
        }
        val idx = ShortArray(thetaSegments * phiSegments * 6)
        var k = 0
        for (j in 0 until phiSegments) {
            for (i in 0 until thetaSegments) {
                val seg = i + j * cols
                val a = seg; val b = seg + cols; val c = seg + cols + 1; val d = seg + 1
                idx[k++] = a.toShort(); idx[k++] = b.toShort(); idx[k++] = d.toShort()
                idx[k++] = b.toShort(); idx[k++] = c.toShort(); idx[k++] = d.toShort()
            }
        }
        return MeshData(pos, uv, idx)
    }

    /** Rectangle in the XY plane facing +Z; s runs left-to-right, t top-to-bottom. */
    fun plane(width: Float, height: Float): MeshData {
        val w = width / 2; val h = height / 2
        return MeshData(
            positions = floatArrayOf(-w, h, 0f, w, h, 0f, -w, -h, 0f, w, -h, 0f),
            uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
            indices = shortArrayOf(0, 2, 1, 2, 3, 1),
        )
    }
}
