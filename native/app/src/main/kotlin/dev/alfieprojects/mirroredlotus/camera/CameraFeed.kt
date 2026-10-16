package dev.alfieprojects.mirroredlotus.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import dev.alfieprojects.mirroredlotus.core.camera.CameraIntrinsics
import dev.alfieprojects.mirroredlotus.core.camera.CameraTiming
import dev.alfieprojects.mirroredlotus.core.camera.FpsRangeSelector
import dev.alfieprojects.mirroredlotus.core.camera.PreviewSizeSelector
import java.util.Locale
import java.util.concurrent.Executor

/**
 * The rear camera streaming straight into the renderer's OES texture.
 *
 * The web build showed the camera through a `<video>` element fed by
 * getUserMedia - Chromium's media pipeline, then the compositor, on a different
 * clock from the WebGL canvas above it. Here Camera2 writes buffers into a
 * SurfaceTexture that the render thread samples in the same draw call as the
 * overlay, so picture and monument reach the screen in the same frame.
 *
 * Camera2 rather than CameraX for the same reasons as stable-still: per-frame
 * timestamps, exposure and rolling-shutter skew, and control over the
 * timestamp base.
 */
class CameraFeed(context: Context) {

    data class Config(
        val streamWidth: Int,
        val streamHeight: Int,
        val intrinsics: CameraIntrinsics,
        val timestampBase: CameraTiming.Base,
        val fpsRange: FpsRangeSelector.Range?,
        val description: String,
    )

    interface Callback {
        fun onConfigured(config: Config)
        fun onError(message: String)
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // One camera thread for the feed's whole life. Quitting it on close() would
    // drop an in-flight onOpened from a cancelled open, and that device would
    // then never be closed.
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)

    // open()/close() run on the main thread, the callbacks on the camera thread.
    // Every open gets a generation; a callback from an older one (the feed was
    // closed or reopened meanwhile) closes what it was handed and does nothing else.
    // Each "is this still current? then take ownership" step runs under
    // [stateLock], as does close()'s bump-and-detach, so a close can't slip in
    // between a callback's check and its write and orphan an open device.
    private val stateLock = Any()
    @Volatile private var generation = 0
    @Volatile private var opening = false
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    private var surface: Surface? = null

    /** Latest frame's exposure and readout skew, for mid-exposure pose sampling. */
    @Volatile var exposureNanos: Long = 0L; private set
    @Volatile var rollingSkewNanos: Long = 0L; private set

    /** True from [open] until [close] or a failure - including while the device is still opening. */
    val isOpen: Boolean get() = opening || device != null

    @SuppressLint("MissingPermission") // the activity checks CAMERA before calling
    fun open(texture: SurfaceTexture, displayShortSide: Int, callback: Callback) {
        close()
        val id = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: Exception) {
            null
        }
        if (id == null) {
            callback.onError("no back camera")
            return
        }
        // Throws too when policy disables the camera or its service has died.
        val chars = try {
            cameraManager.getCameraCharacteristics(id)
        } catch (e: Exception) {
            callback.onError(e.message ?: e.javaClass.simpleName)
            return
        }
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.map { PreviewSizeSelector.Size(it.width, it.height) }.orEmpty()
        val size = PreviewSizeSelector.choose(sizes, displayShortSide)
        if (size == null) {
            callback.onError("no preview sizes")
            return
        }
        val fps = FpsRangeSelector.choose(
            chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.map { FpsRangeSelector.Range(it.lower, it.upper) }.orEmpty(),
        )
        val intrinsics = intrinsicsOf(chars)
        texture.setDefaultBufferSize(size.width, size.height)
        val s = Surface(texture)
        surface = s

        val gen = synchronized(stateLock) {
            opening = true
            ++generation
        }
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!adopt(gen) { device = camera; opening = false }) {
                        camera.close() // closed or reopened while this was opening
                        return
                    }
                    createSession(gen, camera, s, chars, size, fps, intrinsics, callback)
                }

                override fun onDisconnected(camera: CameraDevice) = fail(camera, "camera disconnected")

                override fun onError(camera: CameraDevice, error: Int) = fail(camera, "camera error $error")

                private fun fail(camera: CameraDevice, message: String) {
                    camera.close()
                    if (adopt(gen) { device = null; opening = false }) callback.onError(message)
                }
            }, handler)
        } catch (e: Exception) {
            if (adopt(gen) { opening = false }) callback.onError(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Runs [update] and returns true only if [gen] is still the current open. */
    private inline fun adopt(gen: Int, update: () -> Unit): Boolean = synchronized(stateLock) {
        if (gen != generation) return@synchronized false
        update()
        true
    }

    private fun createSession(
        gen: Int,
        camera: CameraDevice,
        target: Surface,
        chars: CameraCharacteristics,
        size: PreviewSizeSelector.Size,
        fps: FpsRangeSelector.Range?,
        intrinsics: CameraIntrinsics,
        callback: Callback,
    ) {
        val h = handler
        var base = if (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        ) CameraTiming.Base.REALTIME else CameraTiming.Base.MONOTONIC

        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (!adopt(gen) { session = s }) {
                    s.close()
                    return
                }
                try {
                    s.setRepeatingRequest(buildRequest(camera, target, chars, fps), captureCallback, h)
                    if (gen == generation) callback.onConfigured(
                        Config(
                            size.width, size.height, intrinsics, base, fps,
                            "cam ${camera.id} ${size.width}x${size.height} @${fps?.let { "${it.lower}-${it.upper}" } ?: "auto"} " +
                                "f=${String.format(Locale.US, "%.2f", intrinsics.focalLengthMm)}mm ts=$base",
                        ),
                    )
                } catch (e: Exception) {
                    // A close() racing this leaves a closed session behind; that isn't an error.
                    if (gen == generation) callback.onError("repeating request failed: ${e.message}")
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                // Give the device back, or isOpen stays true and nothing retries.
                camera.close()
                if (adopt(gen) { device = null; opening = false }) callback.onError("session configuration failed")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val output = OutputConfiguration(target)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Ask for the sensors' clock outright, so no conversion is needed.
                    output.timestampBase = OutputConfiguration.TIMESTAMP_BASE_REALTIME
                    base = CameraTiming.Base.REALTIME
                }
                val executor = Executor { h.post(it) }
                camera.createCaptureSession(
                    SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(output), executor, stateCallback),
                )
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(listOf(target), stateCallback, h)
            }
        } catch (e: Exception) {
            if (gen == generation) callback.onError("session creation failed: ${e.message}")
        }
    }

    private fun buildRequest(
        camera: CameraDevice,
        target: Surface,
        chars: CameraCharacteristics,
        fps: FpsRangeSelector.Range?,
    ): CaptureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(target)
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet().orEmpty()
        when {
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes ->
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes ->
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.lower, it.upper)) }
        // Electronic stabilisation crops and re-warps each frame: it changes the
        // field of view the projection is built from and adds a frame of delay.
        // The overlay is stabilised by the pose instead.
        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
    }.build()

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { exposureNanos = it }
            // Some HALs under-declare this key and deliver it anyway (stable-still,
            // DEVICE-A07.md), so read it regardless of availableCaptureResultKeys.
            result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)?.let { rollingSkewNanos = it }
        }
    }

    fun close() {
        val (oldSession, oldDevice) = synchronized(stateLock) {
            generation++ // any callback still in flight is now stale
            opening = false
            val detached = session to device
            session = null
            device = null
            detached
        }
        runCatching { oldSession?.close() }
        runCatching { oldDevice?.close() }
        runCatching { surface?.release() }
        surface = null
    }

    /** Ends the camera thread. The feed can't be reopened afterwards. */
    fun release() {
        close()
        thread.quitSafely()
    }

    private fun intrinsicsOf(chars: CameraCharacteristics): CameraIntrinsics {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val physical = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val pixels = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        if (focal == null || focal <= 0f || physical == null) {
            Log.w(TAG, "Camera intrinsics missing; assuming a 66 degree long-side field of view")
            return CameraIntrinsics.fallback().copy(sensorOrientationDeg = orientation)
        }
        // The physical size covers the whole pixel array; streams come from the active area.
        val fx = if (pixels != null && active != null && pixels.width > 0) active.width().toDouble() / pixels.width else 1.0
        val fy = if (pixels != null && active != null && pixels.height > 0) active.height().toDouble() / pixels.height else 1.0
        return CameraIntrinsics(focal.toDouble(), physical.width * fx, physical.height * fy, orientation)
    }

    private companion object {
        const val TAG = "LotusCamera"
    }
}
