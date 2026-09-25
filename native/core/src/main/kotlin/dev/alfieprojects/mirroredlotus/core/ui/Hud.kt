package dev.alfieprojects.mirroredlotus.core.ui

import dev.alfieprojects.mirroredlotus.core.geo.Geo
import dev.alfieprojects.mirroredlotus.core.site.Checkpoints
import dev.alfieprojects.mirroredlotus.core.story.Acts
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.roundToLong

/** Splash distance hint: 10 m steps under 1 km, 0.1 km steps above, always rounded down. */
sealed interface DistanceHint {
    data class Metres(val metres: Long) : DistanceHint
    data class Kilometres(val text: String) : DistanceHint

    companion object {
        fun of(metres: Double): DistanceHint {
            if (metres < 1000) return Metres(floor(metres / 10).toLong() * 10)
            // floor(m/100)/10 printed the way JavaScript prints a number:
            // "1.4", but "2" rather than "2.0".
            val tenths = floor(metres / 100).toLong()
            val text = if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
            return Kilometres(text)
        }
    }
}

/** Where the camera points versus where the monument is (`getAlignmentData`). */
data class Alignment(val cameraDeg: Double, val monumentDeg: Double?, val deltaDeg: Double?) {
    companion object {
        /** [fwdX]/[fwdZ]: camera forward in scene axes; group position null before the trigger. */
        fun of(fwdX: Double, fwdZ: Double, groupX: Double?, groupZ: Double?): Alignment {
            val cam = Geo.normaliseDeg(Math.toDegrees(atan2(fwdX, -fwdZ)))
            if (groupX == null || groupZ == null) return Alignment(cam, null, null)
            val ar = Geo.normaliseDeg(Math.toDegrees(atan2(groupX, -groupZ)))
            var delta = cam - ar
            if (delta > 180) delta -= 360
            if (delta < -180) delta += 360
            return Alignment(cam, ar, delta)
        }
    }
}

/** Live pipeline numbers for the native HUD's extra lines. */
data class PerfSnapshot(
    val renderFps: Double,
    val cameraFps: Double,
    val cameraLatencyMs: Double,
    val sensorHz: Double,
    val poseSyncedToCamera: Boolean,
    val poseLagMs: Double,
    val headingSource: String,
)

/**
 * The position tracker's state for the HUD. [speedMps] is the fresh GPS
 * velocity's speed, or null when footsteps are driving the estimate.
 */
data class TrackSnapshot(
    val sigmaM: Double,
    val steps: Long,
    val rejectedFixes: Long,
    val fusedDistanceM: Double?,
    val speedMps: Double? = null,
)

/**
 * The debug HUD's lines (`_buildHUD` in sensors.js), as plain text. The
 * export control is a separate view, so rebuilding these never swallows a tap.
 */
object HudFormatter {
    fun lines(
        source: String,
        accuracyM: Double?,
        lat: Double,
        lng: Double,
        distanceM: Double,
        bearingDeg: Double,
        siteId: String?,
        alignment: Alignment?,
        perf: PerfSnapshot?,
        track: TrackSnapshot? = null,
    ): List<String> {
        val acc = accuracyM?.roundToLong()?.toString() ?: "?"
        val siteRef = if (siteId == "church") {
            val cp = Checkpoints.nearest(lat, lng)
            val diff = (distanceM - cp.expectedM).let { Math.round(it) }
            val sign = if (diff >= 0) "+" else ""
            "≈ ${cp.name}  Δ$sign${diff}m"
        } else {
            "≈ —"
        }
        val cam = alignment?.let { fmt1(it.cameraDeg) + "°" } ?: "—"
        val ar = alignment?.monumentDeg?.let { fmt1(it) + "°" } ?: "—"
        val d = alignment?.deltaDeg?.let { fmt1(it) + "°" } ?: "—"
        val out = mutableListOf(
            "[$source] ±${acc}m",
            "${fmt(lat, 4)} / ${fmt(lng, 4)}",
            "dist: ${Math.round(distanceM)}m  brg: ${fmt1(bearingDeg)}°",
            "site: ${siteId ?: "—"}  $siteRef",
            "stage: ${Acts.stageLabel(distanceM)}",
            "cam: $cam  AR: $ar  Δ: $d",
        )
        if (track != null) {
            val fused = track.fusedDistanceM?.let { "fused ${Math.round(it)}m " } ?: ""
            val motion = track.speedMps?.let { "gps ${fmt1(it)}m/s" } ?: "steps"
            out += "track: $fused±${Math.round(track.sigmaM)}m  by $motion  steps ${track.steps}  gps-rej ${track.rejectedFixes}"
        }
        if (perf != null) {
            out += "fps: ${fmt1(perf.renderFps)}  cam: ${fmt1(perf.cameraFps)}  sensor: ${Math.round(perf.sensorHz)}Hz"
            out += "photon: ${Math.round(perf.cameraLatencyMs)}ms  pose: " +
                (if (perf.poseSyncedToCamera) "cam-sync" else "latest") + " lag ${Math.round(perf.poseLagMs)}ms"
            out += "heading: ${perf.headingSource}"
        }
        return out
    }

    private fun fmt(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)
    private fun fmt1(v: Double) = fmt(v, 1)
}
