package dev.alfieprojects.mirroredlotus.core.camera

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.tan

/**
 * Lens and sensor as Camera2 describes them, in the sensor's native
 * (landscape) orientation. [sensorWidthMm]/[sensorHeightMm] should be the
 * physical size of the *active* array.
 */
data class CameraIntrinsics(
    val focalLengthMm: Double,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val sensorOrientationDeg: Int,
) {
    companion object {
        /**
         * Stand-in when the characteristics are missing: a typical phone main
         * camera with a 66 degree field across the long side (the A07 measures
         * 66.5). The web build used a flat 60 degrees vertical for every phone.
         */
        fun fallback(longSideFovDeg: Double = 66.0): CameraIntrinsics {
            val w = 6.4
            val f = (w / 2) / tan(Math.toRadians(longSideFovDeg / 2))
            return CameraIntrinsics(f, w, w * 3 / 4, 90)
        }
    }
}

/**
 * How the camera stream fills the screen, and the virtual camera that lines up
 * with it.
 *
 * The overlay only stays locked to the world while panning if the virtual
 * field of view equals the real one *as displayed*. That depends on three
 * crops: the HAL cropping the sensor to the stream's aspect ratio, the rotation
 * to portrait, and the cover-fit crop to the screen's aspect ratio. A wrong FOV
 * shows up as the monument sliding against the church whenever the phone
 * turns - indistinguishable, in the field, from latency.
 */
data class ViewFit(
    /** Projection focal lengths in normalised device units. */
    val fxNdc: Float,
    val fyNdc: Float,
    /** Visible window of the upright camera image, as UV scale and offset. */
    val uvScaleX: Float,
    val uvScaleY: Float,
    val uvOffsetX: Float,
    val uvOffsetY: Float,
    val verticalFovDeg: Double,
    val horizontalFovDeg: Double,
)

object CameraProjection {

    /**
     * [streamWidth] x [streamHeight] is the buffer size handed to the camera
     * (sensor orientation, e.g. 1920x1080); [viewportWidth] x [viewportHeight]
     * is the GL surface in pixels.
     */
    fun fit(
        intrinsics: CameraIntrinsics,
        streamWidth: Int,
        streamHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
    ): ViewFit {
        // 1. The HAL's default crop: the largest centred region of the active
        //    array with the stream's aspect ratio.
        val streamAspect = streamWidth.toDouble() / streamHeight
        val sensorAspect = intrinsics.sensorWidthMm / intrinsics.sensorHeightMm
        val usedWidthMm = if (streamAspect >= sensorAspect) {
            intrinsics.sensorWidthMm
        } else {
            intrinsics.sensorHeightMm * streamAspect
        }
        val focalPx = intrinsics.focalLengthMm * streamWidth / usedWidthMm

        // 2. Upright (display-orientation) image size.
        val rotated = abs(intrinsics.sensorOrientationDeg) % 180 == 90
        val imgW = if (rotated) streamHeight else streamWidth
        val imgH = if (rotated) streamWidth else streamHeight

        // 3. Cover fit: scale until the screen is filled, crop the overflow.
        val scale = max(viewportWidth.toDouble() / imgW, viewportHeight.toDouble() / imgH)
        val visibleX = viewportWidth / (imgW * scale)
        val visibleY = viewportHeight / (imgH * scale)
        val focalViewportPx = focalPx * scale

        return ViewFit(
            fxNdc = (2 * focalViewportPx / viewportWidth).toFloat(),
            fyNdc = (2 * focalViewportPx / viewportHeight).toFloat(),
            uvScaleX = visibleX.toFloat(),
            uvScaleY = visibleY.toFloat(),
            uvOffsetX = ((1 - visibleX) / 2).toFloat(),
            uvOffsetY = ((1 - visibleY) / 2).toFloat(),
            verticalFovDeg = Math.toDegrees(2 * atan(viewportHeight / 2.0 / focalViewportPx)),
            horizontalFovDeg = Math.toDegrees(2 * atan(viewportWidth / 2.0 / focalViewportPx)),
        )
    }
}

/** Picks the camera stream size for the preview texture. */
object PreviewSizeSelector {
    data class Size(val width: Int, val height: Int) {
        val longSide get() = max(width, height)
        val shortSide get() = minOf(width, height)
        val aspect get() = longSide.toDouble() / shortSide
    }

    /** Stream sizes past this cost bandwidth and GPU time and add nothing on a phone screen. */
    const val MAX_LONG_SIDE = 1920

    /**
     * Prefers 16:9 (closest to a 20:9 phone, so the cover-fit discards the
     * least), the smallest one that still covers the screen's short side, and
     * never more than 1080p. The A07's 720-wide panel gets 1280x720; the A56's
     * 1080-wide one gets 1920x1080.
     */
    fun choose(available: List<Size>, displayShortSide: Int): Size? {
        val candidates = available.filter { it.longSide <= MAX_LONG_SIDE }.ifEmpty { available }
        if (candidates.isEmpty()) return null
        val wide = candidates.filter { abs(it.aspect - 16.0 / 9) < 0.01 }
        val pool = wide.ifEmpty {
            val best = candidates.minOf { abs(it.aspect - 16.0 / 9) }
            candidates.filter { abs(abs(it.aspect - 16.0 / 9) - best) < 1e-9 }
        }
        return pool.filter { it.shortSide >= displayShortSide }.minByOrNull { it.width * it.height }
            ?: pool.maxBy { it.width * it.height }
    }
}
