package dev.alfieprojects.mirroredlotus.core.record

import dev.alfieprojects.mirroredlotus.core.site.Site
import dev.alfieprojects.mirroredlotus.core.story.ActEvent
import dev.alfieprojects.mirroredlotus.core.ui.Alignment
import kotlin.math.abs
import kotlin.math.round

/**
 * Field-session log, exported as JSON with the same keys as the web build's
 * `session-recorder.js` so existing analysis keeps working. The native build
 * adds `app` and `perfSamples` (latency telemetry every 5 s); nothing it
 * writes removes or renames a web field.
 *
 * Thread-safe: GPS is recorded on the main thread, acts on the render thread.
 */
class SessionRecorder(
    private val sites: List<Site>,
    private val maxGps: Int = 600,     // 10 min at 1 Hz
    private val maxOrientation: Int = 120, // one sample per 5 s
    private val maxPerf: Int = 120,
    private val orientationIntervalMs: Long = 5000,
) {
    private data class Gps(
        val ts: Long, val lat: Double, val lng: Double, val acc: Double?, val src: String,
        val distM: Long, val bearingDeg: Double, val siteId: String?, val align: Alignment?,
    )

    private data class Ori(val ts: Long, val x: Double, val y: Double, val z: Double, val w: Double)
    private data class Act(val ts: Long, val name: String, val distM: Long, val expectedM: Int)
    private data class Perf(
        val ts: Long, val fps: Double, val camFps: Double, val camLatencyMs: Double,
        val sensorHz: Double, val poseLagMs: Double,
    )

    private val lock = Any()
    private val gps = ArrayDeque<Gps>()
    private val ori = ArrayDeque<Ori>()
    private val acts = ArrayList<Act>()
    private val perf = ArrayDeque<Perf>()
    private var lastOriMs = Long.MIN_VALUE / 2
    private var lastPerfMs = Long.MIN_VALUE / 2

    val gpsCount: Int get() = synchronized(lock) { gps.size }

    fun recordGps(
        tsMs: Long, lat: Double, lng: Double, accuracyM: Double?, source: String,
        distanceM: Double, bearingDeg: Double, alignment: Alignment?, siteId: String?,
    ) = synchronized(lock) {
        if (gps.size >= maxGps) gps.removeFirst()
        gps.addLast(
            Gps(tsMs, lat, lng, accuracyM?.let { round(it) }, source, Math.round(distanceM), round1(bearingDeg), siteId, alignment),
        )
    }

    /** Device-to-ENU quaternion (the rotation-vector sensor's own frame), sampled every 5 s. */
    fun recordOrientation(tsMs: Long, x: Double, y: Double, z: Double, w: Double) = synchronized(lock) {
        if (tsMs - lastOriMs < orientationIntervalMs) return@synchronized
        lastOriMs = tsMs
        if (ori.size >= maxOrientation) ori.removeFirst()
        ori.addLast(Ori(tsMs, round4(x), round4(y), round4(z), round4(w)))
    }

    fun recordAct(tsMs: Long, event: ActEvent, distanceM: Double) = synchronized(lock) {
        acts.add(Act(tsMs, event.id, Math.round(distanceM), event.expectedM))
    }

    fun recordPerf(
        tsMs: Long, fps: Double, cameraFps: Double, cameraLatencyMs: Double, sensorHz: Double, poseLagMs: Double,
    ) = synchronized(lock) {
        if (tsMs - lastPerfMs < orientationIntervalMs) return@synchronized
        lastPerfMs = tsMs
        if (perf.size >= maxPerf) perf.removeFirst()
        perf.addLast(Perf(tsMs, round1(fps), round1(cameraFps), round1(cameraLatencyMs), round1(sensorHz), round1(poseLagMs)))
    }

    /** Null when nothing has been recorded: the caller tells the wearer instead of sharing an empty file. */
    fun exportJson(exportedAtIso: String, appVersion: String): String? = synchronized(lock) {
        if (gps.isEmpty()) return@synchronized null
        val w = JsonWriter()
        w.beginObject()
        w.field("exportedAt", exportedAtIso)
        w.name("app").beginObject().field("platform", "android-native").field("version", appVersion).endObject()
        w.name("sites").beginArray()
        for (s in sites) {
            w.beginObject()
                .field("id", s.id).field("lat", s.lat).field("lng", s.lng)
                .field("anchor", s.anchor.toString()).field("marquee", s.marquee)
                .endObject()
        }
        w.endArray()
        writeSummary(w)
        w.name("actEvents").beginArray()
        for (a in acts) {
            w.beginObject()
                .field("ts", a.ts).field("name", a.name).field("distM", a.distM)
                .field("expectedM", a.expectedM).field("deltaM", a.distM - a.expectedM)
                .endObject()
        }
        w.endArray()
        w.name("gpsEvents").beginArray()
        for (g in gps) {
            w.beginObject()
                .field("ts", g.ts).field("lat", g.lat).field("lng", g.lng)
            // The web build wrote '?' for an unknown accuracy; keep that shape.
            if (g.acc != null) w.field("acc", g.acc) else w.field("acc", "?")
            w.field("src", g.src).field("distM", g.distM).field("bearingDeg", g.bearingDeg).field("site", g.siteId)
            g.align?.let { a ->
                w.name("align").beginObject()
                    .field("cam", round1(a.cameraDeg))
                    .field("ar", a.monumentDeg?.let { round1(it) })
                    .field("delta", a.deltaDeg?.let { round1(it) })
                    .endObject()
            }
            w.endObject()
        }
        w.endArray()
        w.name("oriSamples").beginArray()
        for (o in ori) {
            w.beginObject().field("ts", o.ts).field("x", o.x).field("y", o.y).field("z", o.z).field("w", o.w).endObject()
        }
        w.endArray()
        w.name("perfSamples").beginArray()
        for (p in perf) {
            w.beginObject()
                .field("ts", p.ts).field("fps", p.fps).field("camFps", p.camFps)
                .field("camLatencyMs", p.camLatencyMs).field("sensorHz", p.sensorHz).field("poseLagMs", p.poseLagMs)
                .endObject()
        }
        w.endArray()
        w.endObject()
        w.toString()
    }

    private fun writeSummary(w: JsonWriter) {
        val accs = gps.mapNotNull { it.acc }
        val deltas = gps.mapNotNull { it.align?.deltaDeg }.map { abs(it) }
        val duration = if (gps.size >= 2) Math.round((gps.last().ts - gps.first().ts) / 1000.0) else 0L
        w.name("summary").beginObject()
            .field("durationSeconds", duration)
            .field("gpsCount", gps.size)
            .field("avgAccuracyM", if (accs.isEmpty()) null else round1(accs.average()))
            .field("maxAccuracyM", accs.maxOrNull())
            .field("actCount", acts.size)
            .field("maxAlignmentDeltaDeg", deltas.maxOrNull()?.let { round1(it) })
            .endObject()
    }

    private fun round1(v: Double) = round(v * 10) / 10
    private fun round4(v: Double) = round(v * 10_000) / 10_000
}
