package dev.alfieprojects.mirroredlotus.core.fieldtest

/** What a phase waits for before it ends. */
sealed interface Advance {
    /** A fixed time. The runner warns 3 s before the end. */
    data class After(val ms: Long) : Advance

    /**
     * A pose held for [holdMs] while the phone is still. After [timeoutMs] the
     * phase ends anyway, marked timed out, so a check can fail it rather than
     * the run hanging.
     */
    data class Until(val pose: Pose, val holdMs: Long, val timeoutMs: Long? = null) : Advance

    /** The wearer taps [label]. */
    data class Tap(val label: String) : Advance

    /** The wearer picks one of [options]. */
    data class Choice(val options: List<String>) : Advance
}

enum class Pose(val waiting: String) {
    STILL("hold still"),
    FACE_UP("face-up and still"),
    UPRIGHT("upright and still"),
}

/** What the app does as a phase begins. */
sealed interface PhaseAction {
    /** Every act lit in test-mode staging: starts the show, or switches a GPS-triggered one over. */
    data object StartShow : PhaseAction

    /** Put the test monument 40 m ahead along the phone's heading now, starting the show if needed. */
    data object PlaceAhead : PhaseAction

    /** Pose the overlay from the camera frame's exposure time (on) or the newest sample (off). */
    data class SyncCamera(val on: Boolean) : PhaseAction
}

/** The live number the card shows during a phase. */
enum class Metric { NONE, STEPS, DELTA, DRIFT, THERMAL, HEADING }

data class Phase(
    val id: String,
    val instruction: String,
    val advance: Advance,
    val actions: List<PhaseAction> = emptyList(),
    val metric: Metric = Metric.NONE,
)

/** One line of a result card. [pass] is null for a number that is logged, not judged. */
data class CheckResult(val name: String, val value: String, val threshold: String? = null, val pass: Boolean? = null) {
    val verdict: String get() = when (pass) {
        true -> "PASS"
        false -> "FAIL"
        null -> "info"
    }
}

class FieldTestScript(
    val id: String,
    val title: String,
    /** One line for the menu. */
    val summary: String,
    val phases: List<Phase>,
    val evaluate: (FieldRun) -> List<CheckResult>,
)

/** A finished run, as the checks see it. */
class FieldRun(val stats: List<PhaseStats>, val choices: Map<String, Int>) {
    fun phase(id: String): PhaseStats = stats.first { it.id == id }
}
