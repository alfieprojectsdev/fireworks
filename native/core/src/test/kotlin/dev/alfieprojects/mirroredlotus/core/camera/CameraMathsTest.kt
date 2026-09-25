package dev.alfieprojects.mirroredlotus.core.camera

import dev.alfieprojects.mirroredlotus.core.camera.PreviewSizeSelector.Size
import dev.alfieprojects.mirroredlotus.core.math.Mat4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.tan

class CameraMathsTest {

    /** Galaxy A07 5G main camera, as probed in stable-still (SM-A076B). */
    private val a07 = CameraIntrinsics(3.98, 5.22, 3.93, 90)

    @Test
    fun `A07 portrait preview shows the lens's full 66_5 degree long side`() {
        val fit = CameraProjection.fit(a07, 1280, 720, 720, 1600)
        assertEquals(66.5, fit.verticalFovDeg, 0.1)
        // Cover-fit trims the sides: 720 of the 900 scaled pixels are visible.
        assertEquals(0.8f, fit.uvScaleX, 1e-6f)
        assertEquals(1f, fit.uvScaleY, 1e-6f)
        assertEquals(0.1f, fit.uvOffsetX, 1e-6f)
        assertEquals(0f, fit.uvOffsetY, 1e-6f)
        assertEquals(32.9, fit.horizontalFovDeg, 0.1)
    }

    @Test
    fun `the field of view doesn't depend on the stream resolution`() {
        val small = CameraProjection.fit(a07, 1280, 720, 1080, 2340)
        val large = CameraProjection.fit(a07, 1920, 1080, 1080, 2340)
        assertEquals(small.verticalFovDeg, large.verticalFovDeg, 1e-9)
        assertEquals(small.fxNdc, large.fxNdc, 1e-6f)
    }

    @Test
    fun `a 4-3 stream keeps the whole sensor`() {
        val fit = CameraProjection.fit(a07, 1440, 1080, 1080, 1440)
        assertEquals(66.5, fit.verticalFovDeg, 0.1)
        assertEquals(1f, fit.uvScaleX, 1e-6f)
    }

    @Test
    fun `a point at the top edge of the view projects to the top of the screen`() {
        val fit = CameraProjection.fit(a07, 1280, 720, 720, 1600)
        val proj = Mat4.perspective(FloatArray(16), fit.fxNdc, fit.fyNdc, 0.1f, 4000f)
        val half = Math.toRadians(fit.verticalFovDeg / 2)
        val clip = Mat4.transformPoint(proj, 0f, (100 * tan(half)).toFloat(), -100f, FloatArray(4))
        assertEquals(1f, clip[1] / clip[3], 1e-4f)
    }

    @Test
    fun `fallback intrinsics give the requested long-side field`() {
        val fit = CameraProjection.fit(CameraIntrinsics.fallback(66.0), 1920, 1080, 1080, 2340)
        assertEquals(66.0, fit.verticalFovDeg, 0.01)
    }

    @Test
    fun `frame centre adds half the exposure and half the readout`() {
        val t = CameraTiming.frameCentreRealtime(
            frameNanos = 10_000_000_000L, base = CameraTiming.Base.REALTIME,
            exposureNanos = 30_000_000L, rollingShutterSkewNanos = 27_400_000L,
            realtimeNow = 10_080_000_000L, monotonicNow = 5_000_000_000L,
        )
        assertEquals(10_028_700_000L, t)
    }

    @Test
    fun `monotonic stamps are moved onto the sensor clock`() {
        val t = CameraTiming.frameCentreRealtime(
            frameNanos = 4_950_000_000L, base = CameraTiming.Base.MONOTONIC,
            exposureNanos = 0, rollingShutterSkewNanos = 0,
            realtimeNow = 10_000_000_000L, monotonicNow = 5_000_000_000L,
        )
        assertEquals(9_950_000_000L, t)
    }

    @Test
    fun `implausible stamps are refused rather than trusted`() {
        // A "REALTIME" stamp that is really monotonic is five seconds in the past.
        val t = CameraTiming.frameCentreRealtime(
            4_950_000_000L, CameraTiming.Base.REALTIME, 0, 0, 10_000_000_000L, 5_000_000_000L,
        )
        assertEquals(CameraTiming.NO_TIME, t)
        assertEquals(
            CameraTiming.NO_TIME,
            CameraTiming.frameCentreRealtime(10_100_000_000L, CameraTiming.Base.REALTIME, 0, 0, 10_000_000_000L, 0),
        )
        assertEquals(CameraTiming.NO_TIME, CameraTiming.frameCentreRealtime(0, CameraTiming.Base.REALTIME, 0, 0, 1, 1))
    }

    @Test
    fun `preview size matches each target phone's panel`() {
        val sizes = listOf(
            Size(4080, 3060), Size(3840, 2160), Size(1920, 1440), Size(1920, 1080),
            Size(1440, 1080), Size(1280, 720), Size(960, 540), Size(640, 480),
        )
        assertEquals(Size(1280, 720), PreviewSizeSelector.choose(sizes, 720))  // A07: 720x1600
        assertEquals(Size(1920, 1080), PreviewSizeSelector.choose(sizes, 1080)) // A56: 1080x2340
        assertEquals(Size(1920, 1080), PreviewSizeSelector.choose(sizes, 1440)) // never past 1080p
    }

    @Test
    fun `without 16-9 the nearest aspect wins`() {
        val sizes = listOf(Size(1600, 1200), Size(1920, 1200), Size(1280, 800))
        assertEquals(Size(1280, 800), PreviewSizeSelector.choose(sizes, 720))
        assertNull(PreviewSizeSelector.choose(emptyList(), 720))
    }

    @Test
    fun `fps range prefers a fixed 30`() {
        fun range(a: Int, b: Int) = FpsRangeSelector.Range(a, b)
        assertEquals(range(30, 30), FpsRangeSelector.choose(listOf(range(15, 30), range(30, 30), range(7, 60))))
        assertEquals(range(24, 30), FpsRangeSelector.choose(listOf(range(15, 30), range(24, 30), range(60, 60))))
        assertEquals(range(15, 20), FpsRangeSelector.choose(listOf(range(15, 20), range(7, 15))))
        assertNull(FpsRangeSelector.choose(emptyList()))
    }
}
