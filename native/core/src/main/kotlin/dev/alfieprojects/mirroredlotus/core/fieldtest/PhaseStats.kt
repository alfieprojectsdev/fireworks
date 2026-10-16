package dev.alfieprojects.mirroredlotus.core.fieldtest

import dev.alfieprojects.mirroredlotus.core.geo.Geo

/** The first moment a phase saw the phone reach a (worse) thermal status. */
data class ThermalMark(val status: Int, val atMs: Long, val fps: Double, val photonMs: Double)

/**
 * Everything a check might ask about one phase, accumulated sample by sample
 * so a 20-minute soak costs no more memory than a 2-second pose.
 */
class PhaseStats(
    val id: String,
    val index: Int,
    val startMs: Long,
    private val steps0: Long,
    first: FieldSample?,
) {
    var endMs = startMs
        internal set
    val durationMs: Long get() = endMs - startMs

    /** Steps credited during the phase. */
    var steps = 0L
        private set

    /** Set when an [Advance.Until] phase ran out of time instead of seeing its pose. */
    var timedOut = false
        internal set

    var lastDeltaDeg: Double? = null
        private set

    // Tracker drift from the first fused position seen in the phase.
    private var lat0: Double? = first?.fusedLat
    private var lng0: Double? = first?.fusedLng
    var maxDriftM: Double? = null
        private set
    var finalDriftM: Double? = null
        private set

    // Pipeline and heat.
    var samples = 0
        private set
    private var cameraSamples = 0
    private var fpsSum = 0.0
    private var photonSum = 0.0
    private var photonWithinBudget = 0
    var minFps = Double.POSITIVE_INFINITY
        private set
    var maxPhotonMs = 0.0
        private set
    val startThermal: Int = first?.thermalStatus ?: -1
    val startHeadroom: Double = first?.headroom ?: Double.NaN
    private var worstThermal = startThermal
    private val marks = ArrayList<ThermalMark>()
    val thermalMarks: List<ThermalMark> get() = marks

    // Headings, as offsets from the first one, so 359° to 1° is 2° not 358°.
    private val heading = CircularRange()
    private val compass = CircularRange()
    private var correctionMin = Double.POSITIVE_INFINITY
    private var correctionMax = Double.NEGATIVE_INFINITY

    val meanFps: Double get() = if (samples == 0) 0.0 else fpsSum / samples
    val meanPhotonMs: Double get() = if (cameraSamples == 0) 0.0 else photonSum / cameraSamples

    /** Fraction of camera-live samples with a photon age within [PHOTON_BUDGET_MS]; null without camera. */
    val photonWithinBudgetFraction: Double?
        get() = if (cameraSamples == 0) null else photonWithinBudget.toDouble() / cameraSamples

    val headingSpreadDeg: Double? get() = heading.spread
    val compassSpreadDeg: Double? get() = compass.spread
    val correctionRangeDeg: Double?
        get() = if (correctionMin > correctionMax) null else correctionMax - correctionMin

    internal fun add(s: FieldSample, headingDeg: Double?) {
        endMs = s.tMs
        steps = s.steps - steps0
        s.deltaDeg?.let { lastDeltaDeg = it }

        val lat = s.fusedLat
        val lng = s.fusedLng
        if (lat != null && lng != null) {
            if (lat0 == null || lng0 == null) {
                lat0 = lat
                lng0 = lng
            }
            val d = Geo.distanceM(lat0!!, lng0!!, lat, lng)
            finalDriftM = d
            maxDriftM = maxOf(maxDriftM ?: 0.0, d)
        }

        samples++
        fpsSum += s.renderFps
        if (s.cameraFps > 0) {
            // Only while the show is live: before the trigger the loop idles at 0 fps.
            minFps = minOf(minFps, s.renderFps)
            cameraSamples++
            photonSum += s.photonMs
            maxPhotonMs = maxOf(maxPhotonMs, s.photonMs)
            if (s.photonMs <= PHOTON_BUDGET_MS) photonWithinBudget++
        }
        if (s.thermalStatus > worstThermal) {
            worstThermal = s.thermalStatus
            marks += ThermalMark(s.thermalStatus, s.tMs - startMs, s.renderFps, s.photonMs)
        }

        headingDeg?.let { heading.add(it) }
        s.compassHeadingDeg?.let { compass.add(it) }
        s.yawCorrectionDeg?.let {
            correctionMin = minOf(correctionMin, it)
            correctionMax = maxOf(correctionMax, it)
        }
    }

    /** First time the phase reached [status] or worse, if it did. */
    fun reached(status: Int): ThermalMark? = thermalMarks.firstOrNull { it.status >= status }

    private class CircularRange {
        private var first = Double.NaN
        private var min = 0.0
        private var max = 0.0
        val spread: Double? get() = if (first.isNaN()) null else max - min

        fun add(deg: Double) {
            if (first.isNaN()) {
                first = deg
                return
            }
            var d = (deg - first) % 360.0
            if (d > 180) d -= 360
            if (d < -180) d += 360
            min = minOf(min, d)
            max = maxOf(max, d)
        }
    }

    companion object {
        /** The port's latency goal: under 100 ms from exposure to screen. */
        const val PHOTON_BUDGET_MS = 100.0
    }
}
