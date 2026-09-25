package dev.alfieprojects.mirroredlotus

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES30
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.alfieprojects.mirroredlotus.core.audio.ListenerPose
import dev.alfieprojects.mirroredlotus.core.audio.SpatialMixer
import dev.alfieprojects.mirroredlotus.core.camera.PreviewSizeSelector
import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldTestLog
import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldTests
import dev.alfieprojects.mirroredlotus.core.orientation.OrientationHistory
import dev.alfieprojects.mirroredlotus.core.render.ShaderSources
import dev.alfieprojects.mirroredlotus.fieldtest.FieldLogWriter
import dev.alfieprojects.mirroredlotus.gl.GlProgram
import dev.alfieprojects.mirroredlotus.sensors.OrientationSource
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
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
    fun fieldTestLogLandsWhereTheWearerCanFindIt() {
        val name = "smoke-${System.currentTimeMillis()}.json"
        val writer = FieldLogWriter.create(context, name)
        assertNotNull("log file created", writer)
        writer!!
        val log = FieldTestLog(t0Ms = 1_000)
        writer.append(log.header(FieldTests.standStill(), "2026-09-25T00:00:00Z", "smoke", "test", "device"))
        writer.flush()
        writer.append(log.note(1_500, "second chunk"))
        writer.close()
        assertTrue("writes finished", writer.awaitClosed(5_000))
        Log.i(TAG, "field log at ${writer.displayPath}")

        val text = writer.uri?.let { uri ->
            try {
                context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            } finally {
                context.contentResolver.delete(uri, null, null)
            }
        } ?: File(writer.displayPath).let { f -> f.readText().also { f.delete() } }
        val lines = text.trimEnd().split("\n")
        assertEquals(2, lines.size)
        assertEquals("run", JSONObject(lines[0]).getString("type"))
        assertEquals(500, JSONObject(lines[1]).getInt("t"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(writer.displayPath, writer.displayPath.startsWith("Download/LotusTests/"))
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

    /**
     * Renders 15 simultaneous bursts (the spawner's ceiling) for a second of
     * audio. The app's audio thread runs from launch, so it is warm long before
     * the first firework goes off; the budget is judged on a warm second, and the
     * cold one is only logged. A debuggable build runs ART in debug mode, which
     * is 3-5x slower on the A07, so the budget is only enforced on the field build
     * (the default test build type).
     */
    @Test
    fun audioMixerRendersFasterThanRealTime() {
        val cold = renderSecondWithFifteenVoices()
        val warm = renderSecondWithFifteenVoices()
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        Log.i(TAG, "15 voices, 1 s of audio: cold ${"%.1f".format(cold)} ms, warm ${"%.1f".format(warm)} ms (debuggable=$debuggable)")
        assumeFalse("timing budget only applies to the non-debuggable field build", debuggable)
        assertTrue("warm render $warm ms for 1 s of audio", warm < 250) // 4x headroom on a little core
    }

    private fun renderSecondWithFifteenVoices(): Double {
        val mixer = SpatialMixer(48_000, ListenerPose { false }, seed = 1)
        val buf = FloatArray(512 * 2)
        val t0 = SystemClock.elapsedRealtimeNanos()
        var done = 0
        while (done < 48_000) {
            repeat(15 - mixer.activeVoices) { mixer.queue.offer(0f, 30f, -60f, done % 6) } // keep 15 live
            mixer.render(buf, 512)
            done += 512
        }
        return (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
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
