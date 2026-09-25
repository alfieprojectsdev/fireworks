package dev.alfieprojects.mirroredlotus.core.render

import dev.alfieprojects.mirroredlotus.core.math.Mat4

/**
 * Model matrices for each part of the monument, given where the group stands
 * (`gx, gy, gz` in scene metres). The renderer multiplies these by its
 * view-projection; keeping them here means the placement of every pixel of
 * the monument is covered by JVM tests rather than eyeballed on a phone.
 */
object MonumentPlacement {
    private const val HALF_PI = (Math.PI / 2).toFloat()

    fun beacon(out: FloatArray, gx: Float, gy: Float, gz: Float): FloatArray =
        Mat4.translateRotateYX(out, gx, gy + SceneLayout.BEACON_Y, gz, 0f)

    /** The ring lies flat on the ground (its XY plane rotated onto XZ). */
    fun ground(out: FloatArray, gx: Float, gy: Float, gz: Float): FloatArray =
        Mat4.translateRotateYX(out, gx, gy + SceneLayout.GROUND_Y, gz, 0f, -HALF_PI)

    /** Pylon [i] (0..3) is a unit plane scaled to [widthM] x pylon height, facing outward. */
    fun pylon(out: FloatArray, i: Int, gx: Float, gy: Float, gz: Float, widthM: Float): FloatArray =
        Mat4.translateRotateYX(
            out,
            gx + SceneLayout.pylonOffsetX(i), gy + SceneLayout.PYLON_Y, gz + SceneLayout.pylonOffsetZ(i),
            SceneLayout.PYLON_ROTATIONS[i], 0f, widthM, SceneLayout.PYLON_HEIGHT, 1f,
        )

    fun crown(out: FloatArray, gx: Float, gy: Float, gz: Float, rotation: Float): FloatArray =
        Mat4.translateRotateYX(out, gx, gy + SceneLayout.CROWN_Y, gz, rotation)

    /** The personal message circles the group origin itself (world y = 15). */
    fun message(out: FloatArray, gx: Float, gy: Float, gz: Float, rotation: Float): FloatArray =
        Mat4.translateRotateYX(out, gx, gy, gz, rotation)
}
