package dev.alfieprojects.mirroredlotus.core.fieldtest

import dev.alfieprojects.mirroredlotus.core.record.JsonWriter

/**
 * A field-test run as JSON Lines: one self-contained object per line, so the
 * file can be appended to every few seconds and still parse up to its last
 * whole line after a crash or a thermal kill. Every line carries `type` and
 * `t`, milliseconds since the run started.
 */
class FieldTestLog(private val t0Ms: Long) {

    fun header(
        script: FieldTestScript,
        startedAtIso: String,
        app: String,
        variant: String,
        device: String,
        extras: Map<String, String> = emptyMap(),
    ): String = line("run", t0Ms) {
        field("testId", script.id).field("title", script.title).field("startedAt", startedAtIso)
            .field("app", app).field("variant", variant).field("device", device)
        name("phases").beginArray()
        for (p in script.phases) value(p.id)
        endArray()
        for ((k, v) in extras) field(k, v)
    }

    fun phase(tMs: Long, index: Int, phase: Phase): String = line("phase", tMs) {
        field("index", index).field("id", phase.id).field("text", phase.instruction)
        if (phase.actions.isNotEmpty()) {
            name("actions").beginArray()
            for (a in phase.actions) {
                value(
                    when (a) {
                        PhaseAction.StartShow -> "startShow"
                        PhaseAction.PlaceAhead -> "placeAhead"
                        is PhaseAction.SyncCamera -> if (a.on) "syncCamera:on" else "syncCamera:off"
                    },
                )
            }
            endArray()
        }
    }

    /** Orientation at 10 Hz: device-to-ENU `[w, x, y, z]`, walking heading, rotation rate. */
    fun ori(tMs: Long, q: DoubleArray, headingDeg: Double?, rateRadPerS: Double, compassDeg: Double?, correctionDeg: Double?): String =
        line("ori", tMs) {
            name("q").beginArray()
            for (c in q) value(round(c, 5))
            endArray()
            field("heading", headingDeg?.let { round(it, 2) })
            field("rate", round(rateRadPerS, 4))
            field("compass", compassDeg?.let { round(it, 2) })
            field("correction", correctionDeg?.let { round(it, 2) })
        }

    /** A credited footstep with the tracker's state after it. */
    fun step(tMs: Long, east: Double, north: Double, detectorSteps: Long, track: Track?): String = line("step", tMs) {
        field("e", round(east, 3)).field("n", round(north, 3)).field("steps", detectorSteps)
        track(track)
    }

    fun fix(
        tMs: Long,
        lat: Double,
        lng: Double,
        accuracyM: Double?,
        source: String,
        speedMps: Double?,
        bearingDeg: Double?,
        speedAccuracyMps: Double?,
        result: String,
        track: Track?,
    ): String = line("fix", tMs) {
        field("lat", round(lat, 7)).field("lng", round(lng, 7)).field("acc", accuracyM?.let { round(it, 1) })
            .field("src", source).field("speed", speedMps?.let { round(it, 2) })
            .field("bearing", bearingDeg?.let { round(it, 1) }).field("speedAcc", speedAccuracyMps?.let { round(it, 2) })
            .field("result", result)
        track(track)
    }

    fun perf(
        tMs: Long,
        fps: Double,
        cameraFps: Double,
        photonMs: Double,
        poseLagMs: Double,
        sensorHz: Double,
        thermalStatus: Int,
        headroom: Double,
        qualityLevel: Int,
    ): String = line("perf", tMs) {
        field("fps", round(fps, 1)).field("camFps", round(cameraFps, 1)).field("photonMs", round(photonMs, 1))
            .field("poseLagMs", round(poseLagMs, 1)).field("sensorHz", round(sensorHz, 1))
            .field("thermal", thermalStatus).field("headroom", round(headroom, 3)).field("quality", qualityLevel)
    }

    /** A tap, a choice, or anything else the wearer did. */
    fun input(tMs: Long, kind: String, value: String): String = line("input", tMs) {
        field("kind", kind).field("value", value)
    }

    fun note(tMs: Long, text: String): String = line("note", tMs) { field("text", text) }

    fun result(tMs: Long, results: List<CheckResult>, aborted: Boolean): String = line("result", tMs) {
        field("aborted", aborted)
        name("checks").beginArray()
        for (r in results) {
            beginObject().field("name", r.name).field("value", r.value).field("threshold", r.threshold)
                .field("verdict", r.verdict).endObject()
        }
        endArray()
    }

    /** The tracker's state, attached to fixes and steps. */
    class Track(val lat: Double, val lng: Double, val sigmaM: Double, val stepCount: Long, val rejectedFixes: Long, val speedMps: Double?)

    private fun JsonWriter.track(t: Track?) {
        if (t == null) return
        name("track").beginObject()
            .field("lat", round(t.lat, 7)).field("lng", round(t.lng, 7)).field("sigma", round(t.sigmaM, 2))
            .field("steps", t.stepCount).field("rejected", t.rejectedFixes).field("speed", t.speedMps?.let { round(it, 2) })
            .endObject()
    }

    private inline fun line(type: String, tMs: Long, body: JsonWriter.() -> Unit): String {
        val w = JsonWriter(compact = true)
        w.beginObject().field("type", type).field("t", tMs - t0Ms)
        w.body()
        w.endObject()
        return w.toString() + "\n"
    }

    private fun round(v: Double, digits: Int): Double {
        if (!v.isFinite()) return v
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(v * f) / f
    }
}
