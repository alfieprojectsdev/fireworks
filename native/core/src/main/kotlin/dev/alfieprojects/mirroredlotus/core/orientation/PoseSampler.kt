package dev.alfieprojects.mirroredlotus.core.orientation

import dev.alfieprojects.mirroredlotus.core.camera.CameraTiming

/**
 * Chooses the pose a render frame is drawn with. Render thread only; reuses its
 * arrays, so reading [scene] after [sample] costs nothing.
 *
 * With [sample]'s `syncToCamera` set and a trustworthy frame time, the pose is
 * the orientation at the camera frame's mid-exposure - overlay and picture then
 * move as one. Otherwise it is the newest orientation (lowest latency for the
 * overlay alone, but it leads the picture during a pan).
 */
class PoseSampler(private val history: OrientationHistory) {
    /** device -> ENU at the chosen instant. */
    val enu = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    /** camera -> scene at the chosen instant: what the view matrix is built from. */
    val scene = DoubleArray(4).also { Frames.enuToScene(enu, it) }

    private val newest = DoubleArray(4)

    /** Whether the last [sample] used the camera frame's time. */
    var synced = false
        private set

    /** How much older the chosen pose is than the newest sample, ns (0 when not synced). */
    var lagNanos = 0L
        private set

    /** False until any orientation has arrived; the view keeps its last pose meanwhile. */
    var hasPose = false
        private set

    fun sample(frameCentreNanos: Long, syncToCamera: Boolean) {
        val newestT = history.latest(newest)
        if (newestT == Long.MIN_VALUE) {
            synced = false
            lagNanos = 0
            return
        }
        hasPose = true
        if (syncToCamera && frameCentreNanos != CameraTiming.NO_TIME) {
            history.sampleAt(frameCentreNanos, enu)
            synced = true
            lagNanos = maxOf(0L, newestT - frameCentreNanos)
        } else {
            enu[0] = newest[0]; enu[1] = newest[1]; enu[2] = newest[2]; enu[3] = newest[3]
            synced = false
            lagNanos = 0
        }
        Frames.enuToScene(enu, scene)
    }
}
