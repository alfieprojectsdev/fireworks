package dev.alfieprojects.mirroredlotus.core.orientation

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Smooth *and* north-anchored orientation from two Android sensors:
 *
 * - `TYPE_GAME_ROTATION_VECTOR` (gyro + accelerometer) is smooth and fast but
 *   its yaw reference is arbitrary and drifts slowly.
 * - `TYPE_ROTATION_VECTOR` adds the magnetometer, so yaw is anchored to north,
 *   but the compass jitters and jumps near steel and concrete - the jitter the
 *   web build's research notes set out to kill.
 *
 * Their difference is (to within sensor noise) a pure rotation about the
 * vertical. This class low-passes that yaw offset with time constant [tauSeconds]
 * and applies it to every game sample, so the output moves with the gyro's
 * smoothness and settles to the compass's heading. Magnetic noise and brief
 * disturbances are averaged away; a real heading change still converges in a
 * few seconds.
 *
 * Output is device->ENU. The sensor thread owns it; only [mode] is read from
 * elsewhere (the HUD), hence volatile.
 */
class HeadingFusion(private val tauSeconds: Double = 2.0) {

    enum class Mode { NONE, GAME_ONLY, ABSOLUTE_ONLY, FUSED }

    /** Raw game samples, so an absolute sample can be compared at its own instant. */
    private val gameHistory = OrientationHistory(64)
    private val gameAtAbs = DoubleArray(4)
    private val scratch = DoubleArray(4)

    private var haveGame = false
    private var haveCorrection = false
    private var yawCorrection = 0.0
    private var lastAbsNanos = Long.MIN_VALUE

    @Volatile
    var mode = Mode.NONE
        private set

    /** Current low-passed yaw offset, radians (game frame -> ENU). */
    val correctionRad: Double get() = yawCorrection

    fun reset() {
        gameHistory.clear()
        haveGame = false
        haveCorrection = false
        yawCorrection = 0.0
        lastAbsNanos = Long.MIN_VALUE
        mode = Mode.NONE
    }

    /**
     * A game-rotation sample. Writes the fused device->ENU orientation into
     * [out] and returns true; the caller pushes it into the render history.
     */
    fun onGame(tNanos: Long, qGame: DoubleArray, out: DoubleArray): Boolean {
        haveGame = true
        gameHistory.push(tNanos, qGame)
        applyYaw(yawCorrection, qGame, out)
        mode = if (haveCorrection) Mode.FUSED else Mode.GAME_ONLY
        return true
    }

    /**
     * An absolute (magnetometer-anchored) sample. With a game stream running it
     * only updates the yaw correction and returns false. Without one it is the
     * orientation itself: copied to [out], returns true.
     */
    fun onAbsolute(tNanos: Long, qAbs: DoubleArray, out: DoubleArray): Boolean {
        if (!haveGame) {
            mode = Mode.ABSOLUTE_ONLY
            out[0] = qAbs[0]; out[1] = qAbs[1]; out[2] = qAbs[2]; out[3] = qAbs[3]
            return true
        }
        if (gameHistory.sampleAt(tNanos, gameAtAbs) == OrientationHistory.EMPTY) return false
        val measured = yawOffset(qAbs, gameAtAbs)
        if (!haveCorrection) {
            yawCorrection = measured
            haveCorrection = true
        } else {
            val dt = if (lastAbsNanos == Long.MIN_VALUE) 0.0 else (tNanos - lastAbsNanos) / 1e9
            val alpha = 1 - exp(-dt.coerceIn(0.0, 1.0) / tauSeconds)
            yawCorrection = wrap(yawCorrection + alpha * wrap(measured - yawCorrection))
        }
        lastAbsNanos = tNanos
        mode = Mode.FUSED
        return false
    }

    /** Yaw (about ENU up) of `qAbs * conj(qGame)`, read off where it sends east. */
    private fun yawOffset(qAbs: DoubleArray, qGame: DoubleArray): Double {
        // scratch = qAbs * conj(qGame)
        val aw = qAbs[0]; val ax = qAbs[1]; val ay = qAbs[2]; val az = qAbs[3]
        val bw = qGame[0]; val bx = -qGame[1]; val by = -qGame[2]; val bz = -qGame[3]
        scratch[0] = aw * bw - ax * bx - ay * by - az * bz
        scratch[1] = aw * bx + ax * bw + ay * bz - az * by
        scratch[2] = aw * by - ax * bz + ay * bw + az * bx
        scratch[3] = aw * bz + ax * by - ay * bx + az * bw
        val w = scratch[0]; val x = scratch[1]; val y = scratch[2]; val z = scratch[3]
        // First column of the rotation matrix = image of the east axis.
        val ex = 1 - 2 * (y * y + z * z)
        val ey = 2 * (x * y + w * z)
        return atan2(ey, ex)
    }

    /** `out = Rz(yaw) * q`. */
    private fun applyYaw(yaw: Double, q: DoubleArray, out: DoubleArray) {
        val cw = cos(yaw / 2); val sz = sin(yaw / 2)
        val bw = q[0]; val bx = q[1]; val by = q[2]; val bz = q[3]
        out[0] = cw * bw - sz * bz
        out[1] = cw * bx - sz * by
        out[2] = cw * by + sz * bx
        out[3] = cw * bz + sz * bw
    }

    private fun wrap(a: Double): Double {
        var r = a % (2 * PI)
        if (r > PI) r -= 2 * PI
        if (r <= -PI) r += 2 * PI
        return r
    }
}
