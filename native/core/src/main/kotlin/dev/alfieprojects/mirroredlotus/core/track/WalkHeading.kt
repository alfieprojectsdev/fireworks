package dev.alfieprojects.mirroredlotus.core.track

import kotlin.math.sqrt

/**
 * Which way the wearer faces, as a horizontal direction, from the phone's
 * device->ENU orientation. It serves as the direction of each dead-reckoned
 * step, and as where test mode places the monument.
 *
 * Held up in portrait, the camera's forward axis flattened onto the ground is
 * the answer. It fails when the camera is near vertical and its horizontal
 * part is a few degrees of tilt pointing anywhere:
 * - **Camera down** (phone lying face-up on a desk, the usual state when test
 *   mode is launched over adb): the screen's top edge points where the camera
 *   will face once the phone is lifted into portrait.
 * - **Camera up** (tilted back to look at the crown overhead): the top edge
 *   tips back toward the wearer, so its reverse is the way they face.
 * Found in the field: a face-up launch placed the test-mode monument along the
 * 2 degree tilt of a phone on a desk, i.e. a random bearing.
 */
object WalkHeading {
    /** Below this horizontal length (camera within ~60 degrees of vertical) forward is not trusted. */
    const val MIN_FORWARD_HORIZONTAL = 0.5
    private const val MIN_TOP_HORIZONTAL = 0.3

    /**
     * Writes the unit facing direction as `[east, north]` into [out]. Returns
     * false (and leaves [out] alone) when neither axis gives a direction,
     * e.g. the phone standing on its end with the camera flat to the horizon
     * sideways - not a pose anyone uses.
     */
    fun of(qEnu: DoubleArray, out: DoubleArray): Boolean {
        val w = qEnu[0]; val x = qEnu[1]; val y = qEnu[2]; val z = qEnu[3]
        // Camera forward = -(third column of the rotation matrix).
        val fe = -(2 * (x * z + w * y))
        val fn = -(2 * (y * z - w * x))
        val fu = -(1 - 2 * (x * x + y * y))
        val fLen = sqrt(fe * fe + fn * fn)
        if (fLen >= MIN_FORWARD_HORIZONTAL) {
            out[0] = fe / fLen
            out[1] = fn / fLen
            return true
        }
        // Screen top = second column: forward once raised when the camera looks
        // down, backward when it looks up.
        val sign = if (fu < 0) 1.0 else -1.0
        val te = sign * 2 * (x * y - w * z)
        val tn = sign * (1 - 2 * (x * x + z * z))
        val tLen = sqrt(te * te + tn * tn)
        if (tLen < MIN_TOP_HORIZONTAL) return false
        out[0] = te / tLen
        out[1] = tn / tLen
        return true
    }
}
