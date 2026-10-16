package dev.alfieprojects.mirroredlotus

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.view.Display

/**
 * One-shot device facts to logcat at launch (`adb logcat -s LotusProbe`): the
 * things the latency design depends on and the web build could never see -
 * sensor hardware and rates, the camera's timestamp clock and lens, the panel's
 * refresh rate. Run it once on the A56 and the A07 and paste the output into
 * docs/HANDOVER.md.
 */
object DeviceProbe {
    private const val TAG = "LotusProbe"

    fun log(context: Context, display: Display?) {
        runCatching {
            Log.i(TAG, "device ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
            display?.let { d ->
                val rates = d.supportedModes.joinToString { "%.0f".format(java.util.Locale.US, it.refreshRate) }
                Log.i(TAG, "display refresh ${"%.1f".format(java.util.Locale.US, d.refreshRate)} Hz (modes: $rates)")
            }
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            for (type in listOf(
                Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR,
                Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD,
            )) {
                val s = sm.getDefaultSensor(type)
                if (s == null) {
                    Log.i(TAG, "sensor type $type: ABSENT")
                } else {
                    Log.i(TAG, "sensor ${s.stringType}: ${s.name} / ${s.vendor} minDelay=${s.minDelay}us fifo=${s.fifoMaxEventCount}")
                }
            }
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val source = when (c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME"
                    else -> "UNKNOWN"
                }
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.joinToString()
                val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString()
                Log.i(
                    TAG,
                    "camera $id back: ts=$source focal=[$focal]mm sensor=${size}mm " +
                        "orientation=${c.get(CameraCharacteristics.SENSOR_ORIENTATION)} ois=[$ois] fps=[$fps]",
                )
            }
        }.onFailure { Log.w(TAG, "probe failed: ${it.message}") }
    }
}
