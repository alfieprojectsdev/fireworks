package dev.alfieprojects.mirroredlotus.core.fieldtest

import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldTestRunner.Companion.clock
import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldTestRunner.Companion.fmt1
import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy
import java.util.Locale
import kotlin.math.abs

/**
 * The guided on-phone tests. Each answers a question that so far needed a
 * laptop, adb and someone relaying what the wearer did: the phone gives the
 * instructions, times the phases and logs everything itself. All six work
 * indoors; the walk-in and the church heading check stay outdoor jobs.
 */
object FieldTests {

    /** Menu order. [seed] randomises the swim test's A/B order. */
    fun all(seed: Long): List<FieldTestScript> =
        listOf(faceUpPlacement(), standStill(), stepCount(), swim(syncFirst = (seed and 1L) == 0L), thermalSoak(), compass())

    fun byId(id: String, seed: Long): FieldTestScript? = all(seed).firstOrNull { it.id == id.lowercase(Locale.US) }

    const val ROUNDS = 3
    const val MAX_DELTA_DEG = 15.0
    const val STAND_STILL_MS = 45_000L
    const val MAX_PHANTOM_STEPS = 2L
    const val WALK_STEPS = 20L
    const val STEP_TOLERANCE = 2L
    const val SWIM_TRIAL_MS = 15_000L
    const val SOAK_MS = 20 * 60_000L
    const val PHOTON_SHARE = 0.9
    const val COMPASS_HOLD_MS = 60_000L

    /** T1: does test mode place the monument ahead when launched from a phone lying face-up? */
    fun faceUpPlacement() = FieldTestScript(
        id = "t1",
        title = "Face-up placement",
        summary = "Monument placed from a face-up phone lands ahead ($ROUNDS rounds)",
        phases = (1..ROUNDS).flatMap { r ->
            listOf(
                Phase(
                    "faceup-$r",
                    "Round $r of $ROUNDS. Lay the phone face-up with its top edge pointing at something you'll recognise." +
                        if (r > 1) " Point it a different way from last time." else "",
                    Advance.Until(Pose.FACE_UP, holdMs = 1_500),
                ),
                Phase(
                    "lift-$r",
                    "Lift it into portrait, face the thing the top edge pointed at, and hold still.",
                    Advance.Until(Pose.UPRIGHT, holdMs = 2_000, timeoutMs = 30_000),
                    listOf(PhaseAction.PlaceAhead),
                    Metric.DELTA,
                ),
            )
        },
        evaluate = { run ->
            (1..ROUNDS).map { r ->
                val st = run.phase("lift-$r")
                val d = st.lastDeltaDeg
                CheckResult(
                    "round $r Δ",
                    when {
                        st.timedOut -> "never held upright"
                        d == null -> "no monument"
                        else -> fmt1(d) + "°"
                    },
                    "|Δ| < ${MAX_DELTA_DEG.toInt()}°",
                    !st.timedOut && d != null && abs(d) < MAX_DELTA_DEG,
                )
            }
        },
    )

    /** T2: does ordinary handling, with the feet still, count as walking? */
    fun standStill(): FieldTestScript {
        val parts = listOf(
            "steady" to "Hold the phone up and keep it steady.",
            "pan" to "Pan slowly left and right, as if looking round the sky. Feet still.",
            "handle" to "Handle it normally: tilt up to the crown, look around, shift your grip. Feet still.",
        )
        return FieldTestScript(
            id = "t2",
            title = "Stand still",
            summary = "No phantom steps while standing: steady, panning, handling",
            phases = listOf(
                Phase("intro", "Stand in one spot. Don't take a step until the end: three parts of 45 s.", Advance.Tap("Start")),
            ) + parts.map { (id, text) -> Phase(id, text, Advance.After(STAND_STILL_MS), metric = Metric.DRIFT) },
            evaluate = { run ->
                parts.flatMap { (id, _) ->
                    val st = run.phase(id)
                    listOf(
                        CheckResult("$id steps", "${st.steps}", "≤ $MAX_PHANTOM_STEPS", st.steps <= MAX_PHANTOM_STEPS),
                        CheckResult("$id drift", st.maxDriftM?.let { fmt1(it) + " m max" } ?: "no fix"),
                    )
                }
            },
        )
    }

    /** T3: does a walk count step for step, phone held up and phone at the side? */
    fun stepCount() = FieldTestScript(
        id = "t3",
        title = "Step count",
        summary = "Walk $WALK_STEPS steps twice: phone held up, then at your side",
        phases = listOf(
            Phase("intro", "Find a clear stretch of floor for $WALK_STEPS steps. Tap Start, then walk.", Advance.Tap("Start")),
            Phase(
                "held",
                "Phone held up in front of you. Walk exactly $WALK_STEPS steps, counting aloud, then tap Done.",
                Advance.Tap("Done"),
                metric = Metric.STEPS,
            ),
            Phase("back", "Walk back to where you started. Tap Start when you're ready.", Advance.Tap("Start")),
            Phase(
                "side",
                "Phone down at your side. Walk exactly $WALK_STEPS steps, counting aloud, then tap Done.",
                Advance.Tap("Done"),
                metric = Metric.STEPS,
            ),
        ),
        evaluate = { run ->
            listOf("held", "side").map { id ->
                val n = run.phase(id).steps
                CheckResult("$id: $WALK_STEPS steps", "$n counted", "$WALK_STEPS ± $STEP_TOLERANCE", abs(n - WALK_STEPS) <= STEP_TOLERANCE)
            }
        },
    )

    /**
     * T4: does syncing the pose to the camera frame keep the overlay stuck
     * while panning? A blind A/B: the wearer doesn't know which trial is synced.
     */
    fun swim(syncFirst: Boolean): FieldTestScript {
        val syncedTrial = if (syncFirst) 0 else 1
        val labels = listOf("A", "B", "Same")
        return FieldTestScript(
            id = "t4",
            title = "Swim A/B",
            summary = "Pan briskly twice; say which trial kept the beacon stuck",
            phases = listOf(
                Phase("aim-a", "Point at a fixed object across the room or out of a window, and hold still.", Advance.Until(Pose.UPRIGHT, 1_500, 30_000)),
                Phase(
                    "trial-a",
                    "Trial A. Note where the beacon sits on the object, then pan left and right briskly.",
                    Advance.After(SWIM_TRIAL_MS),
                    listOf(PhaseAction.PlaceAhead, PhaseAction.SyncCamera(syncFirst)),
                ),
                Phase("aim-b", "Point back at the same object and hold still.", Advance.Until(Pose.UPRIGHT, 1_500, 30_000)),
                Phase(
                    "trial-b",
                    "Trial B. The same again: pan left and right briskly.",
                    Advance.After(SWIM_TRIAL_MS),
                    listOf(PhaseAction.PlaceAhead, PhaseAction.SyncCamera(!syncFirst)),
                ),
                Phase("ask", "Which trial kept the beacon stuck to the object?", Advance.Choice(labels)),
            ),
            evaluate = { run ->
                val pick = run.choices["ask"]
                listOf(
                    CheckResult(
                        "stuck better",
                        "${pick?.let { labels[it] } ?: "—"} (synced: ${labels[syncedTrial]})",
                        "the synced trial",
                        when (pick) {
                            syncedTrial -> true
                            2, null -> null
                            else -> false
                        },
                    ),
                )
            },
        )
    }

    /** T5: how long until the phone runs hot, and what does the ladder keep? */
    fun thermalSoak() = FieldTestScript(
        id = "t5",
        title = "Thermal soak",
        summary = "The full show for 20 minutes; time to MODERATE and SEVERE",
        phases = listOf(
            Phase(
                "intro",
                "Start with the phone cool. For 20 minutes keep the show on screen, in hand or on a stand. Don't switch apps.",
                Advance.Tap("Start"),
            ),
            Phase("soak", "Keep the show running. The card counts down.", Advance.After(SOAK_MS), listOf(PhaseAction.StartShow), Metric.THERMAL),
        ),
        evaluate = { run ->
            val st = run.phase("soak")
            val headroom = if (st.startHeadroom.isNaN()) "—" else String.format(Locale.US, "%.2f", st.startHeadroom)
            val share = st.photonWithinBudgetFraction
            listOf(
                CheckResult("start", "${ThermalPolicy.statusName(st.startThermal)}, headroom $headroom"),
                reachedLine("to moderate", st, ThermalPolicy.STATUS_MODERATE),
                reachedLine("to severe", st, ThermalPolicy.STATUS_SEVERE),
                CheckResult(
                    "photon ≤ ${PhaseStats.PHOTON_BUDGET_MS.toInt()} ms",
                    share?.let { "${Math.round(it * 100)}% of the time" } ?: "no camera",
                    "≥ ${Math.round(PHOTON_SHARE * 100)}%",
                    share?.let { it >= PHOTON_SHARE },
                ),
                CheckResult(
                    "worst",
                    if (st.minFps.isInfinite()) "no camera" else "${Math.round(st.minFps)} fps, ${Math.round(st.maxPhotonMs)} ms",
                ),
            )
        },
    )

    /** T6: how steady is the heading held on one spot? Informational indoors, near steel. */
    fun compass() = FieldTestScript(
        id = "t6",
        title = "Compass steadiness",
        summary = "Hold on one spot for 60 s; heading and compass spread",
        phases = listOf(
            Phase("aim", "Point at a fixed object and hold still.", Advance.Until(Pose.UPRIGHT, 1_500, 30_000)),
            Phase("hold", "Keep holding still on it.", Advance.After(COMPASS_HOLD_MS), metric = Metric.HEADING),
        ),
        evaluate = { run ->
            val st = run.phase("hold")
            fun deg(v: Double?) = v?.let { fmt1(it) + "°" } ?: "—"
            listOf(
                CheckResult("heading spread", deg(st.headingSpreadDeg)),
                CheckResult("raw compass spread", deg(st.compassSpreadDeg)),
                CheckResult("fusion correction range", deg(st.correctionRangeDeg)),
            )
        },
    )

    private fun reachedLine(name: String, st: PhaseStats, status: Int): CheckResult {
        val m = st.reached(status) ?: return CheckResult(name, "not reached")
        return CheckResult(name, "${clock(m.atMs)} (${Math.round(m.fps)} fps, ${Math.round(m.photonMs)} ms)")
    }
}
