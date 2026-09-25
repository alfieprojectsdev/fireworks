package dev.alfieprojects.mirroredlotus.core.track

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the wearer is, from GPS fixes, GPS velocity and footsteps together.
 *
 * GPS alone is not enough once the wearer is at or inside the church: fixes
 * come less often, and the ones that do come wander by 20-50 m as the signal
 * bounces off walls. The monument is re-anchored from every position, so raw
 * indoor GPS would drag it around. And without any fix, it stays anchored to
 * the last one and effectively walks with the wearer.
 *
 * A small Kalman filter on a local east/north plane, isotropic, so one variance:
 * - **Predict** with a velocity when one is fresh: the fix's own Doppler speed
 *   and bearing (what the fused provider reports outdoors, exact on foot or in a
 *   vehicle), or else the speed implied by two consecutive GNSS-grade fixes. A
 *   constant speed then leaves no lag, and moving fixes never look like jumps.
 *   Without a velocity model, the filter trailed a moving wearer by ~9x their
 *   speed in metres and cycled reject/reset at vehicle speed.
 * - **Steps** (pedestrian dead reckoning) move the estimate only while no
 *   velocity is fresh: indoors, where GPS has no Doppler. Outdoors they would
 *   double-count the motion GPS velocity already carries.
 * - **Update** with each fix, weighted by its reported accuracy. A fix far
 *   outside the combined uncertainty is rejected as a jump, unless several in a
 *   row agree, which means the estimate was the thing that was wrong.
 * - Time adds variance too, so missed steps can't strand the estimate.
 *
 * Integrating the accelerometer twice (a true INS) would drift tens of metres a
 * minute on phone sensors; the step model drifts a few percent of the distance
 * walked. Single-threaded (the main thread).
 */
class PositionTracker(
    private val strideM: Double = 0.7,
    private val strideSigmaM: Double = 0.35,
    /** Uncertainty growth with no motion information at all, m^2 per second. */
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
    /** A velocity older than this is stale, and steps take over. */
    private val velocityFreshMs: Long = 2500,
    /** Only fixes at least this good may imply a velocity (GNSS, not Wi-Fi wander). */
    private val derivedVelocityMaxAccuracyM: Double = 10.0,
    /** Below this speed the wearer counts as standing still. */
    private val stationaryMps: Double = 0.3,
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

    // Velocity model.
    private var velE = 0.0
    private var velN = 0.0
    private var velSigma = 0.0
    private var velAtMs = Long.MIN_VALUE / 2

    // The previous GNSS-grade fix, for implied velocity.
    private var prevFixE = 0.0
    private var prevFixN = 0.0
    private var prevFixMs = Long.MIN_VALUE / 2
    private var prevFixGood = false

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

    /**
     * Current GPS speed, m/s, or null when no velocity is fresh as of [nowMs]:
     * then footsteps are what move the estimate.
     */
    fun speedMps(nowMs: Long): Double? = if (velocityFresh(nowMs)) hypot(velE, velN) else null

    /**
     * A GPS fix. [speedMps], [bearingDeg] and [speedAccuracyMps] are the fix's
     * Doppler velocity when the provider reports one (null otherwise).
     */
    fun onFix(
        lat: Double,
        lng: Double,
        accuracyM: Double?,
        nowMs: Long,
        speedMps: Double? = null,
        bearingDeg: Double? = null,
        speedAccuracyMps: Double? = null,
    ): FixResult {
        val acc = (accuracyM ?: defaultAccuracyM).coerceAtLeast(1.0)
        if (!initialised) {
            originLat = lat
            originLng = lng
            metresPerDegLng = METRES_PER_DEG_LAT * cos(Math.toRadians(lat))
            east = 0.0; north = 0.0
            variance = acc * acc
            lastUpdateMs = nowMs
            initialised = true
            rememberFix(0.0, 0.0, acc, nowMs)
            takeDopplerVelocity(speedMps, bearingDeg, speedAccuracyMps, nowMs)
            return FixResult.INITIALISED
        }
        predict(nowMs)
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
                velAtMs = Long.MIN_VALUE / 2 // whatever carried us here was wrong
                rememberFix(fe, fn, acc, nowMs)
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

        if (!takeDopplerVelocity(speedMps, bearingDeg, speedAccuracyMps, nowMs)) deriveVelocity(fe, fn, acc, nowMs)
        rememberFix(fe, fn, acc, nowMs)
        return FixResult.FUSED
    }

    /**
     * A detected footstep toward unit direction ([eastUnit], [northUnit]).
     * It moves the estimate only while no GPS velocity is fresh; otherwise GPS
     * velocity already carries the motion and the step is only counted.
     * Ignored before the first fix.
     */
    fun onStep(eastUnit: Double, northUnit: Double, nowMs: Long) {
        if (!initialised) return
        stepCount++
        if (velocityFresh(nowMs)) return
        predict(nowMs)
        east += eastUnit * strideM
        north += northUnit * strideM
        variance += strideSigmaM * strideSigmaM
    }

    private fun velocityFresh(nowMs: Long) = nowMs - velAtMs <= velocityFreshMs

    /**
     * Moves the estimate along the velocity for as long as it was fresh, and
     * grows the variance. Carrying it only up to its freshness horizon (not to
     * "now") means the metres walked just before the fixes stopped aren't lost
     * when the next update is a step after the velocity has gone stale.
     */
    private fun predict(nowMs: Long) {
        val dt = ((nowMs - lastUpdateMs) / 1000.0).coerceIn(0.0, 60.0)
        val moveUntilMs = minOf(nowMs, velAtMs + velocityFreshMs)
        val moveDt = ((moveUntilMs - lastUpdateMs) / 1000.0).coerceIn(0.0, 60.0)
        if (moveDt > 0) {
            east += velE * moveDt
            north += velN * moveDt
            variance += (velSigma * moveDt) * (velSigma * moveDt)
        }
        variance += driftM2PerS * dt
        lastUpdateMs = nowMs
    }

    private fun takeDopplerVelocity(speed: Double?, bearingDeg: Double?, speedAccuracy: Double?, nowMs: Long): Boolean {
        if (speed == null || speed.isNaN()) return false
        if (speed < stationaryMps) {
            velE = 0.0; velN = 0.0
        } else {
            if (bearingDeg == null || bearingDeg.isNaN()) return false
            val b = Math.toRadians(bearingDeg)
            velE = speed * sin(b)
            velN = speed * cos(b)
        }
        velSigma = speedAccuracy?.takeIf { it > 0 } ?: maxOf(0.5, 0.2 * speed)
        velAtMs = nowMs
        return true
    }

    /** Velocity from two consecutive GNSS-grade fixes, when the provider gave no Doppler. */
    private fun deriveVelocity(fe: Double, fn: Double, acc: Double, nowMs: Long) {
        if (!prevFixGood || acc > derivedVelocityMaxAccuracyM) return
        val dt = (nowMs - prevFixMs) / 1000.0
        if (dt < 0.5 || dt > 5.0) return
        val ve = (fe - prevFixE) / dt
        val vn = (fn - prevFixN) / dt
        val alpha = if (velocityFresh(nowMs)) 0.5 else 1.0 // smooth once running
        velE += alpha * (ve - velE)
        velN += alpha * (vn - velN)
        val speed = hypot(velE, velN)
        if (speed < stationaryMps) { velE = 0.0; velN = 0.0 }
        velSigma = maxOf(1.0, 0.5 * speed)
        velAtMs = nowMs
    }

    private fun rememberFix(fe: Double, fn: Double, acc: Double, nowMs: Long) {
        prevFixE = fe
        prevFixN = fn
        prevFixMs = nowMs
        prevFixGood = acc <= derivedVelocityMaxAccuracyM
    }

    private companion object {
        /** On the web build's 6371 km sphere. */
        const val METRES_PER_DEG_LAT = 6_371_000.0 * Math.PI / 180.0
    }
}
