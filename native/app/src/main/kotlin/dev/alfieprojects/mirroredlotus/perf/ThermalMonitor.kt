package dev.alfieprojects.mirroredlotus.perf

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy

/**
 * The phone's thermal status (API 29+) and forecast headroom (API 30+), for
 * `ThermalPolicy` and the logs. Main thread; [onChange] runs there too.
 */
class ThermalMonitor(context: Context, private val onChange: () -> Unit) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val mainExecutor = context.mainExecutorCompat()
    private var listener: Any? = null
    private var lastHeadroomAtMs = Long.MIN_VALUE / 2

    /** `PowerManager.THERMAL_STATUS_*`, or [ThermalPolicy.STATUS_UNKNOWN] before API 29. */
    var status = ThermalPolicy.STATUS_UNKNOWN
        private set

    /** 10 s forecast: 1.0 means SEVERE. NaN before API 30 or when the HAL has none. */
    var headroom = Double.NaN
        private set

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && listener == null) listener = register()
        pollHeadroom(System.currentTimeMillis(), force = true)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) unregister()
        listener = null
    }

    /**
     * Refreshes the headroom at most every [HEADROOM_INTERVAL_MS]: the platform
     * returns NaN to callers that ask more than about once a second. Returns
     * true when the value moved.
     */
    fun pollHeadroom(nowMs: Long, force: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!force && nowMs - lastHeadroomAtMs < HEADROOM_INTERVAL_MS) return false
        lastHeadroomAtMs = nowMs
        val h = power.getThermalHeadroom(FORECAST_SECONDS).toDouble()
        if (h.isNaN() || h == headroom) return false
        headroom = h
        return true
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun register(): Any {
        status = power.currentThermalStatus
        val l = PowerManager.OnThermalStatusChangedListener { s ->
            if (s != status) Log.i(TAG, "thermal ${ThermalPolicy.statusName(status)} -> ${ThermalPolicy.statusName(s)}")
            status = s
            onChange()
        }
        power.addThermalStatusListener(mainExecutor, l)
        return l
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun unregister() {
        (listener as? PowerManager.OnThermalStatusChangedListener)?.let { power.removeThermalStatusListener(it) }
    }

    private companion object {
        const val TAG = "LotusThermal"
        const val FORECAST_SECONDS = 10
        const val HEADROOM_INTERVAL_MS = 2_000L

        fun Context.mainExecutorCompat() = androidx.core.content.ContextCompat.getMainExecutor(this)
    }
}
