package dev.alfieprojects.mirroredlotus.fieldtest

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.alfieprojects.mirroredlotus.BuildConfig
import dev.alfieprojects.mirroredlotus.R
import dev.alfieprojects.mirroredlotus.core.fieldtest.Advance
import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldSample
import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldTestLog
import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldTestRunner
import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldTestRunner.Event
import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldTestScript
import dev.alfieprojects.mirroredlotus.core.fieldtest.PhaseAction
import dev.alfieprojects.mirroredlotus.core.ui.PerfSnapshot
import dev.alfieprojects.mirroredlotus.location.Fix
import dev.alfieprojects.mirroredlotus.ui.Haptics
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Runs a guided field test on the phone itself: draws the instruction card,
 * buzzes at phase changes, does what each phase asks of the app, and writes
 * the run's log. The runner in `:core` makes every decision. Main thread.
 */
class FieldTestController(
    private val activity: Activity,
    private val haptics: Haptics,
    private val host: Host,
) {
    /** What a phase can ask the app to do. */
    interface Host {
        fun startShow()
        fun placeAhead()
        fun setSyncCamera(on: Boolean)
        fun restoreSyncCamera()
    }

    private val card: View = activity.findViewById(R.id.field_card)
    private val title: TextView = activity.findViewById(R.id.field_title)
    private val instruction: TextView = activity.findViewById(R.id.field_instruction)
    private val timer: TextView = activity.findViewById(R.id.field_timer)
    private val metric: TextView = activity.findViewById(R.id.field_metric)
    private val buttons: LinearLayout = activity.findViewById(R.id.field_buttons)
    private val abort: TextView = activity.findViewById(R.id.field_abort)

    private var runner: FieldTestRunner? = null
    private var log: FieldTestLog? = null
    private var writer: FieldLogWriter? = null
    private var logPath: String? = null
    private var lastFlushMs = 0L
    private var lastPerfMs = Long.MIN_VALUE / 2
    private var shownButtons: List<String>? = null

    /** A run is in progress (its result card doesn't count). */
    val running: Boolean get() = runner?.finished == false

    init {
        abort.setOnLongClickListener {
            val r = runner
            if (r != null && !r.finished) {
                val now = SystemClock.elapsedRealtime()
                append { input(now, "abort", "hold") }
                handle(r.abort(now), now)
                render(now)
            }
            true
        }
    }

    fun start(script: FieldTestScript, first: FieldSample, extras: Map<String, String>) {
        if (running) return
        val r = FieldTestRunner(script)
        runner = r
        shownButtons = null
        val l = FieldTestLog(first.tMs)
        log = l
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US))
        val w = FieldLogWriter.create(activity, "${stamp}_${script.id}.json")
        writer = w
        logPath = w?.displayPath
        val device = "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"
        w?.append(
            l.header(
                script, Instant.now().toString(), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                BuildConfig.BUILD_TYPE, device, extras,
            ),
        )
        Log.i(TAG, "field test ${script.id} started, log ${logPath ?: "unavailable"}")
        card.visibility = View.VISIBLE
        lastFlushMs = first.tMs
        handle(r.start(first), first.tMs)
        render(first.tMs)
        w?.flush()
    }

    /** Every UI tick (10 Hz). */
    fun onTick(s: FieldSample, p: PerfSnapshot, qualityLevel: Int) {
        val r = runner ?: return
        if (r.finished) return
        val events = r.onSample(s)
        s.quat?.let { q -> append { ori(s.tMs, q, r.headingDeg, r.rateRadPerS, s.compassHeadingDeg, s.yawCorrectionDeg) } }
        if (s.tMs - lastPerfMs >= PERF_INTERVAL_MS) {
            lastPerfMs = s.tMs
            append {
                perf(s.tMs, p.renderFps, p.cameraFps, p.cameraLatencyMs, p.poseLagMs, p.sensorHz, s.thermalStatus, s.headroom, qualityLevel)
            }
        }
        handle(events, s.tMs)
        render(s.tMs)
        if (s.tMs - lastFlushMs >= FLUSH_MS) {
            lastFlushMs = s.tMs
            writer?.flush()
        }
    }

    fun onStep(tMs: Long, east: Double, north: Double, detectorSteps: Long, track: FieldTestLog.Track?) {
        if (running) append { step(tMs, east, north, detectorSteps, track) }
    }

    fun onFix(tMs: Long, f: Fix, outcome: String, track: FieldTestLog.Track?) {
        if (!running) return
        append { fix(tMs, f.lat, f.lng, f.accuracyM, f.source, f.speedMps, f.bearingDeg, f.speedAccuracyMps, outcome, track) }
    }

    /** Anything else worth a line: quality changes, pauses. */
    fun note(tMs: Long, text: String) {
        if (running) append { note(tMs, text) }
    }

    fun onPause() {
        if (!running) return
        note(SystemClock.elapsedRealtime(), "pause")
        writer?.flush()
    }

    fun onResume() = note(SystemClock.elapsedRealtime(), "resume")

    fun close() {
        val r = runner ?: return
        if (!r.finished) {
            val now = SystemClock.elapsedRealtime()
            append { note(now, "activity destroyed") }
            handle(r.abort(now), now)
        }
        writer?.close()
    }

    // ---- internals ----------------------------------------------------------

    private inline fun append(line: FieldTestLog.() -> String) {
        val l = log ?: return
        writer?.append(l.line())
    }

    private fun handle(events: List<Event>, tMs: Long) {
        for (e in events) {
            when (e) {
                is Event.PhaseStarted -> {
                    append { phase(tMs, e.index, e.phase) }
                    for (a in e.phase.actions) act(a)
                    haptics.confirm()
                }
                Event.Warning -> haptics.tick()
                is Event.Finished -> finish(e, tMs)
            }
        }
    }

    private fun act(a: PhaseAction) = when (a) {
        PhaseAction.StartShow -> host.startShow()
        PhaseAction.PlaceAhead -> host.placeAhead()
        is PhaseAction.SyncCamera -> host.setSyncCamera(a.on)
    }

    private fun finish(e: Event.Finished, tMs: Long) {
        append { result(tMs, e.results, e.aborted) }
        writer?.close()
        host.restoreSyncCamera()
        haptics.confirm()
        runner?.resultLines()?.forEach { Log.i(TAG, it) }
    }

    private fun render(tMs: Long) {
        val r = runner ?: return
        val c = r.card(tMs)
        setText(title, "${c.title}   ${c.progress}")
        setText(instruction, c.instruction)
        if (r.finished) {
            setText(timer, logPath?.let { activity.getString(R.string.field_log_path, it) } ?: activity.getString(R.string.field_log_unavailable))
            setText(metric, r.resultLines().joinToString("\n"))
        } else {
            setText(timer, c.timer)
            setText(metric, c.metric)
        }
        abort.visibility = if (r.finished) View.GONE else View.VISIBLE
        if (c.buttons != shownButtons) {
            shownButtons = c.buttons
            buttons.removeAllViews()
            c.buttons.forEachIndexed { i, label -> buttons.addView(button(i, label)) }
            buttons.visibility = if (c.buttons.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun button(index: Int, label: String): TextView = TextView(activity).apply {
        text = label
        setTextColor(activity.getColor(R.color.lotus_white))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, activity.resources.getDimension(R.dimen.field_button_text))
        setBackgroundResource(R.drawable.hud_background)
        val padH = dp(18)
        val padV = dp(10)
        setPadding(padH, padV, padH, padV)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(12) }
        setOnClickListener { onButton(index, label) }
    }

    private fun onButton(index: Int, label: String) {
        val r = runner ?: return
        if (r.finished) {
            dismiss()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val advance = r.phase?.advance
        append { input(now, if (advance is Advance.Choice) "choice" else "tap", label) }
        val events = when (advance) {
            is Advance.Tap -> r.onTap(now)
            is Advance.Choice -> r.onChoice(index, now)
            else -> emptyList()
        }
        handle(events, now)
        render(now)
    }

    private fun dismiss() {
        card.visibility = View.GONE
        runner = null
        log = null
        writer = null
    }

    private fun setText(view: TextView, text: String?) {
        if (view.text?.toString() == (text ?: "")) return
        view.text = text ?: ""
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun dp(v: Int) = Math.round(v * activity.resources.displayMetrics.density)

    private companion object {
        const val TAG = "LotusFieldTest"
        const val FLUSH_MS = 5_000L
        const val PERF_INTERVAL_MS = 1_000L
    }
}
