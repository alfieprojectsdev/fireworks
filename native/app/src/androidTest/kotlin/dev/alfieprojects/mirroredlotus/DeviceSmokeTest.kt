package dev.alfieprojects.mirroredlotus

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alfieprojects.mirroredlotus.core.audio.ListenerPose
import dev.alfieprojects.mirroredlotus.core.audio.SpatialMixer
import dev.alfieprojects.mirroredlotus.core.camera.PreviewSizeSelector
import dev.alfieprojects.mirroredlotus.core.orientation.OrientationHistory
import dev.alfieprojects.mirroredlotus.core.render.ShaderSources
import dev.alfieprojects.mirroredlotus.gl.GlProgram
import dev.alfieprojects.mirroredlotus.sensors.OrientationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks for the things the JVM can't see. Run on the A56 and the
 * A07 with `./gradlew :app:connectedDebugAndroidTest`; results and the probe
 * lines land in logcat under `LotusSmoke`.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun shadersCompileOnThisGpu() {
        withGles3 {
            for (p in ShaderSources.Program.entries) {
                val program = GlProgram.build(p)
                assertTrue("${p.name} linked", program.id != 0)
            }
            assertEquals(GLES30.GL_NO_ERROR, GLES30.glGetError())
        }
    }

    @Test
    fun rotationVectorsStreamFastEnough() {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        assertNotNull("rotation vector", sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR))
        val history = OrientationHistory()
        val source = OrientationSource(context, history)
        source.start()
        SystemClock.sleep(2_000)
        val hz = source.rate.rateHz(SystemClock.elapsedRealtimeNanos())
        source.stop()
        Log.i(TAG, "orientation ${source.describe} at ${"%.1f".format(hz)} Hz, ${history.count} samples")
        // Some HALs only report on change; a phone on a desk still jitters enough for 50 Hz.
        assertTrue("orientation rate $hz Hz", hz >= 50.0)
    }

    @Test
    fun backCameraOffersAUsablePreview() {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.first {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        val c = cm.getCameraCharacteristics(id)
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(SurfaceTexture::class.java).map { PreviewSizeSelector.Size(it.width, it.height) }
        val chosen = PreviewSizeSelector.choose(sizes, context.resources.displayMetrics.widthPixels)
        val source = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        Log.i(TAG, "camera $id preview=$chosen timestampSource=$source focal=${c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList()}")
        assertNotNull(chosen)
        assertNotNull(c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE))
    }

    @Test
    fun audioMixerRendersFasterThanRealTime() {
        val mixer = SpatialMixer(48_000, ListenerPose { false }, seed = 1)
        repeat(15) { mixer.queue.offer(0f, 30f, -60f, it % 6) }
        val frames = 48_000
        val buf = FloatArray(512 * 2)
        val t0 = SystemClock.elapsedRealtimeNanos()
        var done = 0
        while (done < frames) { mixer.render(buf, 512); done += 512 }
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        Log.i(TAG, "15 voices, 1 s of audio rendered in ${"%.1f".format(ms)} ms")
        assertTrue("render budget $ms ms", ms < 250) // 4x headroom on the slowest core
    }

    private fun withGles3(block: () -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        assertTrue(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0,
            configs, 0, 1, count, 0,
        )
        assertTrue("GLES3 config", count[0] > 0)
        val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, ctx))
            Log.i(TAG, "GL ${GLES30.glGetString(GLES30.GL_RENDERER)} / ${GLES30.glGetString(GLES30.GL_VERSION)}")
            block()
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, ctx)
            EGL14.eglTerminate(display)
        }
    }

    private companion object {
        const val TAG = "LotusSmoke"
    }
}
