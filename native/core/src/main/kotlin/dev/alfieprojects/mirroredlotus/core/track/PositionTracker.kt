package dev.alfieprojects.mirroredlotus.core.track

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Where the wearer is, from GPS fixes and footsteps together.
 *
 * GPS alone is not enough once the wearer is at or inside the church: fixes
 * come less often, and the ones that do come wander by 20-50 m as the signal
 * bounces off walls. The monument is re-anchored from every position, so
 * raw indoor GPS would drag it around. And without any fix, it stays anchored
 * to the last one and effectively walks with the wearer.
 *
 * This is a small Kalman filter on a local east/north plane, isotropic, so one
 * variance does:
 * - **A step** moves the estimate one stride along the walking direction
 *   (pedestrian dead reckoning) and adds stride uncertainty.
 * - **Time** adds a little uncertainty too, so GPS can still win if steps are
 *   missed.
 * - **A fix** pulls the estimate in proportion to how much the two
 *   uncertainties trust it: a 30 m-accuracy fix barely moves a 3 m estimate.
 *   A fix far outside both uncertainties is rejected as a jump, unless several
 *   in a row agree, which means the estimate was the thing that was wrong.
 *
 * Integrating the accelerometer twice (a true INS) would drift tens of metres
 * in a minute on phone sensors; stepping along the gyro-locked heading drifts
 * a few percent of the distance walked. Single-threaded (the main thread).
 */
class PositionTracker(
    private val strideM: Double = 0.7,
    private val strideSigmaM: Double = 0.35,
    /** Uncertainty growth while no step is detected, m^2 per second. */
    private val driftM2PerS: Double = 0.25,
    /** Fixes reporting worse than this are ignored outright. */
    private val maxAccuracyM: Double = 100.0,
    /** A fix this many combined sigmas away (and over [minJumpM]) is a jump. */
    private val gateSigmas: Double = 3.0,
    private val minJumpM: Double = 10.0,
    /** Consecutive jump rejections after which GPS is believed over the estimate. */
    private val resetAfterJumps: Int = 5,
    /** Accuracy assumed when a fix reports none. */
    private val defaultAccuracyM: Double = 30.0,
) {
    enum class FixResult { INITIALISED, FUSED, IGNORED_ACCURACY, REJECTED_JUMP, RESET }

    private var originLat = 0.0
    private var originLng = 0.0
    private var metresPerDegLng = 0.0
    private var east = 0.0
    private var north = 0.0
    private var variance = 0.0
    private var lastUpdateMs = 0L
    private var consecutiveJumps = 0

    var initialised = false
        private set
    var stepCount = 0L
        private set
    var rejectedFixes = 0L
        private set

    /** One-sigma uncertainty of the estimate, metres. */
    val sigmaM: Double get() = sqrt(variance)

    val lat: Double get() = originLat + north / METRES_PER_DEG_LAT
    val lng: Double get() = originLng + east / metresPerDegLng

    fun onFix(lat: Double, lng: Double, accuracyM: Double?, nowMs: Long): FixResult {
        val acc = (accuracyM ?: defaultAccuracyM).coerceAtLeast(1.0)
        if (!initialised) {
            originLat = lat
            originLng = lng
            metresPerDegLng = METRES_PER_DEG_LAT * cos(Math.toRadians(lat))
            east = 0.0; north = 0.0
            variance = acc * acc
            lastUpdateMs = nowMs
            initialised = true
            return FixResult.INITIALISED
        }
        grow(nowMs)
        if (acc > maxAccuracyM) {
            rejectedFixes++
            return FixResult.IGNORED_ACCURACY
        }
        val fe = (lng - originLng) * metresPerDegLng
        val fn = (lat - originLat) * METRES_PER_DEG_LAT
        val innovation = hypot(fe - east, fn - north)
        val r2 = acc * acc
        if (innovation > minJumpM && innovation > gateSigmas * sqrt(variance + r2)) {
            if (++consecutiveJumps >= resetAfterJumps) {
                east = fe; north = fn; variance = r2
                consecutiveJumps = 0
                return FixResult.RESET
            }
            rejectedFixes++
            return FixResult.REJECTED_JUMP
        }
        consecutiveJumps = 0
        val k = variance / (variance + r2)
        east += k * (fe - east)
        north += k * (fn - north)
        variance *= (1 - k)
        return FixResult.FUSED
    }

    /** A detected footstep toward unit direction ([eastUnit], [northUnit]). Ignored before the first fix. */
    fun onStep(eastUnit: Double, northUnit: Double, nowMs: Long) {
        if (!initialised) return
        grow(nowMs)
        east += eastUnit * strideM
        north += northUnit * strideM
        variance += strideSigmaM * strideSigmaM
        stepCount++
    }

    private fun grow(nowMs: Long) {
        val dt = ((nowMs - lastUpdateMs) / 1000.0).coerceIn(0.0, 60.0)
        variance += driftM2PerS * dt
        lastUpdateMs = nowMs
    }

    private companion object {
        /** On the web build's 6371 km sphere. */
        const val METRES_PER_DEG_LAT = 6_371_000.0 * Math.PI / 180.0
    }
}
