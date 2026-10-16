package dev.alfieprojects.mirroredlotus.core.perf

/**
 * How the render loop is driven.
 * - [IDLE]: not at all. Before the trigger an opaque splash covers the GL view.
 * - [FULL]: continuously at the panel's 60 Hz, so fireworks animate at the story rate.
 * - [CAMERA]: once per camera frame (~30 Hz). With the pose synced to the
 *   frame's exposure, a 60 Hz loop redraws each picture twice with the same
 *   pose and only the fireworks move between them, so this halves the GPU
 *   work while keeping the monument just as locked to the church.
 */
enum class RenderPace { IDLE, FULL, CAMERA }

/**
 * One rung of the degrade ladder. [renderScale] shrinks the GL surface (the
 * compositor upscales it), cutting fill and MSAA cost with the square.
 * [maxLiveFireworks] caps the spawner (15 is the web build's show).
 */
data class RenderQuality(
    val level: Int,
    val pace: RenderPace,
    val renderScale: Double,
    val maxLiveFireworks: Int,
) {
    val describe: String
        get() = "L$level ${pace.name.lowercase()} ${Math.round(renderScale * 100)}% fw $maxLiveFireworks"
}

/**
 * Chooses the render quality from the phone's thermal state.
 *
 * On the A07, 25 minutes of handling in Manila daytime took the phone to
 * SEVERE (skin 51 °C) and the app from 60 fps and a 62 ms photon age to 34 fps
 * and 289 ms: the lag this port exists to remove, back through throttling. A
 * walk-in from the 1.8 km trigger is 20+ minutes of camera, GL and GPS, so the
 * show gives ground before the governor takes it.
 *
 * Inputs are Android's thermal status (`PowerManager.THERMAL_STATUS_*`, API
 * 29+; -1 when unknown) and the forecast headroom (`getThermalHeadroom(10)`,
 * API 30+; 1.0 means SEVERE is forecast in 10 s; NaN when unknown). The status
 * already has hysteresis in the thermal HAL. The headroom doesn't, so it gets
 * its own: a level entered at one headroom is left only 0.1 below it.
 * Single-threaded (the main thread).
 */
class ThermalPolicy {
    private var headroomLevel = 0

    var level = 0
        private set

    val quality: RenderQuality get() = LADDER[level]

    /** Returns true when the level changed. */
    fun update(status: Int, headroom: Double): Boolean {
        if (!headroom.isNaN()) {
            val up = when {
                headroom >= 1.0 -> 2
                headroom >= 0.85 -> 1
                else -> 0
            }
            val down = when {
                headroom >= 0.9 -> 2
                headroom >= 0.75 -> 1
                else -> 0
            }
            headroomLevel = maxOf(up, minOf(headroomLevel, down))
        }
        val statusLevel = when {
            status >= STATUS_CRITICAL -> 3
            status == STATUS_SEVERE -> 2
            status == STATUS_MODERATE -> 1
            else -> 0
        }
        val next = maxOf(statusLevel, headroomLevel)
        if (next == level) return false
        level = next
        return true
    }

    /** The pace to run at: idle until the experience starts. */
    fun pace(started: Boolean): RenderPace = if (started) quality.pace else RenderPace.IDLE

    companion object {
        // PowerManager.THERMAL_STATUS_*, mirrored so :core stays Android-free.
        const val STATUS_UNKNOWN = -1
        const val STATUS_NONE = 0
        const val STATUS_LIGHT = 1
        const val STATUS_MODERATE = 2
        const val STATUS_SEVERE = 3
        const val STATUS_CRITICAL = 4
        const val STATUS_EMERGENCY = 5
        const val STATUS_SHUTDOWN = 6

        val LADDER = listOf(
            RenderQuality(0, RenderPace.FULL, 1.0, 15),
            RenderQuality(1, RenderPace.CAMERA, 1.0, 9),
            RenderQuality(2, RenderPace.CAMERA, 0.75, 6),
            RenderQuality(3, RenderPace.CAMERA, 0.5, 4),
        )

        fun statusName(status: Int): String = when (status) {
            STATUS_NONE -> "none"
            STATUS_LIGHT -> "light"
            STATUS_MODERATE -> "moderate"
            STATUS_SEVERE -> "severe"
            STATUS_CRITICAL -> "critical"
            STATUS_EMERGENCY -> "emergency"
            STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }
}
