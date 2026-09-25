package dev.alfieprojects.mirroredlotus.core.fieldtest

import dev.alfieprojects.mirroredlotus.core.math.Quat
import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy
import java.util.Locale

/**
 * Runs one [FieldTestScript]: steps through its phases on timers, held poses,
 * taps and choices, accumulates [PhaseStats], and scores the run at the end.
 *
 * The app feeds it a [FieldSample] every UI tick and does what the returned
 * [Event]s say (run a phase's actions, buzz, show the results). It draws
 * [card] and writes the log; every decision is made here, where it is tested.
 * Single-threaded (the main thread).
 */
class FieldTestRunner(val script: FieldTestScript) {

    sealed interface Event {
        /** A phase began: run its actions, confirm with a buzz. */
        data class PhaseStarted(val index: Int, val phase: Phase) : Event

        /** A timed phase ends in [WARN_MS]. */
        data object Warning : Event

        data class Finished(val results: List<CheckResult>, val aborted: Boolean) : Event
    }

    /** What the instruction card shows. [buttons] are the phase's own; the app adds Abort. */
    data class Card(
        val title: String,
        val progress: String,
        val instruction: String,
        val timer: String?,
        val metric: String?,
        val buttons: List<String>,
    )

    private val stats = ArrayList<PhaseStats>()
    private val choices = LinkedHashMap<String, Int>()
    private var last: FieldSample? = null
    private val prevQ = DoubleArray(4)
    private var prevQMs = Long.MIN_VALUE
    private var holdSinceMs: Long? = null
    private var warned = false
    private val scratch = DoubleArray(2)

    var index = -1
        private set
    var finished = false
        private set
    var results: List<CheckResult> = emptyList()
        private set

    /** Rotation rate between the last two samples, rad/s; NaN until known. */
    var rateRadPerS = Double.NaN
        private set

    /** The newest sample's walking heading, degrees; null without a pose. */
    var headingDeg: Double? = null
        private set

    val phase: Phase? get() = if (finished) null else script.phases.getOrNull(index)

    fun start(s: FieldSample): List<Event> {
        check(index == -1) { "already started" }
        observe(s)
        return enter(0, s.tMs)
    }

    fun onSample(s: FieldSample): List<Event> {
        if (finished || index < 0) return emptyList()
        observe(s)
        val st = stats.last()
        st.add(s, headingDeg)
        val elapsed = s.tMs - st.startMs
        when (val a = phase!!.advance) {
            is Advance.After -> {
                if (elapsed >= a.ms) return advance(s.tMs)
                if (!warned && a.ms >= MIN_WARNED_MS && elapsed >= a.ms - WARN_MS) {
                    warned = true
                    return listOf(Event.Warning)
                }
            }
            is Advance.Until -> {
                if (poseHeld(a.pose, s)) {
                    val since = holdSinceMs ?: s.tMs.also { holdSinceMs = it }
                    if (s.tMs - since >= a.holdMs) return advance(s.tMs)
                } else {
                    holdSinceMs = null
                }
                if (a.timeoutMs != null && elapsed >= a.timeoutMs) {
                    st.timedOut = true
                    return advance(s.tMs)
                }
            }
            is Advance.Tap, is Advance.Choice -> Unit
        }
        return emptyList()
    }

    fun onTap(nowMs: Long): List<Event> {
        if (phase?.advance !is Advance.Tap) return emptyList()
        return advance(nowMs)
    }

    fun onChoice(option: Int, nowMs: Long): List<Event> {
        val p = phase ?: return emptyList()
        val a = p.advance as? Advance.Choice ?: return emptyList()
        if (option !in a.options.indices) return emptyList()
        choices[p.id] = option
        return advance(nowMs)
    }

    fun abort(nowMs: Long): List<Event> {
        if (finished) return emptyList()
        stats.lastOrNull()?.endMs = nowMs
        finished = true
        val where = script.phases.getOrNull(index)?.id ?: "start"
        results = listOf(CheckResult("run", "aborted during $where", pass = false))
        return listOf(Event.Finished(results, aborted = true))
    }

    fun card(nowMs: Long): Card {
        val title = "${script.id.uppercase(Locale.US)}  ${script.title}"
        val p = phase
        if (p == null) {
            val passed = results.count { it.pass == true }
            val judged = results.count { it.pass != null }
            return Card(title, "done", "Results: $passed of $judged passed", null, null, listOf("Close"))
        }
        val st = stats.last()
        val elapsed = nowMs - st.startMs
        val timer = when (val a = p.advance) {
            is Advance.After -> clock(a.ms - elapsed)
            is Advance.Until -> holdSinceMs?.let {
                String.format(Locale.US, "holding %.1f / %.1f s", (nowMs - it) / 1000.0, a.holdMs / 1000.0)
            } ?: "waiting: ${a.pose.waiting}"
            else -> null
        }
        val buttons = when (val a = p.advance) {
            is Advance.Tap -> listOf(a.label)
            is Advance.Choice -> a.options
            else -> emptyList()
        }
        return Card(title, "step ${index + 1} of ${script.phases.size}", p.instruction, timer, metric(p.metric, st, elapsed), buttons)
    }

    /** One line per check, for the result card and the log. */
    fun resultLines(): List<String> = results.map { r ->
        val threshold = r.threshold?.let { "  ($it)" } ?: ""
        "${r.verdict.padEnd(4)}  ${r.name}: ${r.value}$threshold"
    }

    // ---- internals ----------------------------------------------------------

    private fun observe(s: FieldSample) {
        val q = s.quat
        if (q != null) {
            if (prevQMs != Long.MIN_VALUE) {
                val dt = (s.tMs - prevQMs) / 1000.0
                if (dt in MIN_RATE_DT_S..MAX_RATE_DT_S) rateRadPerS = Quat.angleBetween(prevQ, q) / dt
            }
            q.copyInto(prevQ)
            prevQMs = s.tMs
            headingDeg = FieldPose.headingDeg(q, scratch)
        }
        last = s
    }

    private fun poseHeld(pose: Pose, s: FieldSample): Boolean {
        val q = s.quat ?: return false
        if (!(rateRadPerS < STILL_RAD_PER_S)) return false // NaN (unknown) is not still
        return when (pose) {
            Pose.STILL -> true
            Pose.FACE_UP -> FieldPose.faceUp(q)
            Pose.UPRIGHT -> FieldPose.upright(q)
        }
    }

    private fun advance(nowMs: Long): List<Event> {
        stats.lastOrNull()?.endMs = nowMs
        val next = index + 1
        if (next < script.phases.size) return enter(next, nowMs)
        finished = true
        results = script.evaluate(FieldRun(stats.toList(), choices.toMap()))
        return listOf(Event.Finished(results, aborted = false))
    }

    private fun enter(i: Int, nowMs: Long): List<Event> {
        index = i
        holdSinceMs = null
        warned = false
        val s = last
        stats += PhaseStats(script.phases[i].id, i, nowMs, s?.steps ?: 0L, s)
        return listOf(Event.PhaseStarted(i, script.phases[i]))
    }

    private fun metric(m: Metric, st: PhaseStats, elapsedMs: Long): String? {
        val s = last
        return when (m) {
            Metric.NONE -> null
            Metric.STEPS -> "steps ${st.steps}"
            Metric.DELTA -> "Δ " + (s?.deltaDeg?.let { fmt1(it) + "°" } ?: "—")
            Metric.DRIFT -> "steps ${st.steps}  drift " + (st.finalDriftM?.let { fmt1(it) + " m" } ?: "— (no fix)")
            Metric.THERMAL -> if (s == null) null else {
                "${ThermalPolicy.statusName(s.thermalStatus)}  ${Math.round(s.renderFps)} fps  " +
                    "photon ${Math.round(s.photonMs)} ms  ${clock(elapsedMs)}"
            }
            Metric.HEADING -> "heading " + (headingDeg?.let { fmt1(it) + "°" } ?: "—") +
                "  spread " + (st.headingSpreadDeg?.let { fmt1(it) + "°" } ?: "—")
        }
    }

    companion object {
        const val WARN_MS = 3_000L
        /** Short timed phases get no warning buzz; it would land on top of the start one. */
        const val MIN_WARNED_MS = 8_000L
        /** Held still: under ~9 °/s between ticks. Handheld tremor is well under this. */
        const val STILL_RAD_PER_S = 0.15
        private const val MIN_RATE_DT_S = 0.02
        private const val MAX_RATE_DT_S = 1.0

        fun clock(ms: Long): String {
            val s = maxOf(0L, (ms + 999) / 1000)
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }

        fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
    }
}
