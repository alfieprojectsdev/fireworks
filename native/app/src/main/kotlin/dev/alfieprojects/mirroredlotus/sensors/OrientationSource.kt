package dev.alfieprojects.mirroredlotus.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import dev.alfieprojects.mirroredlotus.core.orientation.Frames
import dev.alfieprojects.mirroredlotus.core.orientation.HeadingFusion
import dev.alfieprojects.mirroredlotus.core.orientation.OrientationHistory
import dev.alfieprojects.mirroredlotus.core.telemetry.RateMeter
import dev.alfieprojects.mirroredlotus.core.ui.HeadingQuality

/**
 * Phone orientation straight from the sensor HAL into [history], on a dedicated
 * high-priority thread.
 *
 * This is the piece that replaces the web build's SensorBridge: there, each
 * rotation-vector event was serialised to JSON, posted across the Capacitor
 * bridge, parsed on the WebView's JS thread and applied whenever that thread
 * got round to it - at 50 Hz, delta-gated. Here a 200 Hz sample is a few
 * multiplies and an array write; the render thread reads it back at the exact
 * instant it needs.
 */
class OrientationSource(
    context: Context,
    private val history: OrientationHistory,
    headingTauSeconds: Double = 2.0,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val game: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val absolute: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val fusion = HeadingFusion(headingTauSeconds)
    private val rawQ = DoubleArray(4)
    private val outQ = DoubleArray(4)
    private var thread: HandlerThread? = null

    val rate = RateMeter()

    /** Last `onAccuracyChanged` status for the absolute sensor; -1 until reported. */
    @Volatile
    var absoluteStatus: Int = -1
        private set

    /** Last absolute sample's heading accuracy estimate (`values[4]`), radians; NaN if none. */
    @Volatile
    var headingAccuracyRad: Float = Float.NaN
        private set

    /** Wall-clock ms of the newest absolute / any sample; 0 until one arrives. For the compass banner. */
    @Volatile
    var lastAbsoluteAtMs: Long = 0
        private set

    @Volatile
    var lastAnyAtMs: Long = 0
        private set

    val hasGame: Boolean get() = game != null
    val hasCompass: Boolean get() = absolute != null

    /** "game+mag", "game only", "mag only" or "none" - the HUD's heading line. */
    val describe: String
        get() = when (fusion.mode) {
            HeadingFusion.Mode.FUSED -> "game+mag"
            HeadingFusion.Mode.GAME_ONLY -> if (absolute == null) "game (no compass)" else "game (waiting mag)"
            HeadingFusion.Mode.ABSOLUTE_ONLY -> "mag only"
            HeadingFusion.Mode.NONE -> "none"
        } + (if (headingAccuracyRad.isNaN()) "" else " ±${Math.round(Math.toDegrees(headingAccuracyRad.toDouble()))}°")

    fun headingGood(): Boolean = HeadingQuality.isGood(absoluteStatus, headingAccuracyRad)

    fun start() {
        if (thread != null) return
        fusion.reset()
        history.clear()
        val t = HandlerThread("orientation", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = t
        val h = Handler(t.looper)
        // 200 Hz for the stream the pose follows; the compass only steers a
        // 2 s filter, so 50 Hz is plenty for it.
        game?.let { sensorManager.registerListener(this, it, 5_000, h) }
        absolute?.let {
            sensorManager.registerListener(this, it, if (game != null) SensorManager.SENSOR_DELAY_GAME else 5_000, h)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        Frames.rotationVectorToQuat(event.values, event.values.size, rawQ)
        val now = System.currentTimeMillis()
        lastAnyAtMs = now
        val publish = when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> fusion.onGame(event.timestamp, rawQ, outQ)
            Sensor.TYPE_ROTATION_VECTOR -> {
                lastAbsoluteAtMs = now
                headingAccuracyRad = if (event.values.size >= 5 && event.values[4] > 0f) event.values[4] else Float.NaN
                fusion.onAbsolute(event.timestamp, rawQ, outQ)
            }
            else -> false
        }
        if (publish) {
            history.push(event.timestamp, outQ)
            rate.record(event.timestamp)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_ROTATION_VECTOR) absoluteStatus = accuracy
    }
}
