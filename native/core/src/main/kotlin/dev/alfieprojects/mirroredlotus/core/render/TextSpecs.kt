package dev.alfieprojects.mirroredlotus.core.render

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Straight (non-premultiplied) ARGB colour as the web canvas code specified it. */
data class Rgba(val r: Int, val g: Int, val b: Int, val a: Float) {
    fun toArgb(): Int = ((a * 255).roundToInt().coerceIn(0, 255) shl 24) or (r shl 16) or (g shl 8) or b
}

/**
 * Where each piece of text goes on its texture - the layout half of
 * `_createMarqueeTexture`, `_createPylonTexture` and the message canvas. The
 * app does the rasterising with an Android Canvas; everything that decides a
 * size or a position is here and tested.
 */
object TextSpecs {
    val GLOW = Rgba(255, 220, 160, 0.4f)

    // ---- crown marquee ------------------------------------------------------
    const val MARQUEE_FONT_PX = 144f
    const val MARQUEE_COPIES = 3
    /** Minimum blank pixels between adjacent copies, including across the wrap seam (GH #6). */
    const val MARQUEE_GAP_PX = 300
    const val MARQUEE_HEIGHT_PX = 512
    const val MARQUEE_GLOW_BLUR = 16f
    val MARQUEE_FILL = Rgba(255, 255, 255, 1f)

    /**
     * Texture layout for a marquee line whose measured width at
     * [MARQUEE_FONT_PX] is [textWidthPx].
     *
     * The web build rounded the canvas up to a power of two (WebGL 1 can't
     * repeat-wrap otherwise), which also fixes how much of the crown's
     * circumference the words occupy. That proportion is kept. GLES 3 doesn't
     * need the rounding, but a 25-character line at 144 px lands on 8192 px -
     * 16 MB of RGBA - so the texture is capped at [maxWidth] and glyphs are
     * squeezed horizontally by [Layout.scaleX]. The cylinder's UV mapping
     * stretches them back, so the crown looks identical with a quarter of the
     * memory.
     */
    fun marquee(textWidthPx: Float, maxWidth: Int = 4096): Layout {
        val textW = ceil(textWidthPx).toInt()
        val logical = nextPow2(MARQUEE_COPIES * (textW + MARQUEE_GAP_PX))
        val width = min(logical, maxWidth)
        val scaleX = width.toFloat() / logical
        val centres = FloatArray(MARQUEE_COPIES) { i -> ((i + 0.5f) * logical / MARQUEE_COPIES).roundToInt() * scaleX }
        return Layout(width, MARQUEE_HEIGHT_PX, scaleX, centres, MARQUEE_HEIGHT_PX / 2f)
    }

    // ---- stat pylons --------------------------------------------------------
    const val PYLON_CELL_PX = 256
    const val PYLON_FONT_PX = 180f
    const val PYLON_GLOW_BLUR = 12f
    val PYLON_FILL = Rgba(255, 255, 255, 0.6f)

    data class PylonLayout(val width: Int, val height: Int, val cell: Int, val fontPx: Float, val charCount: Int) {
        fun glyphCentreY(i: Int): Float = cell * (i + 0.5f)
        val glyphCentreX: Float get() = cell / 2f
    }

    /** One square cell per character, stacked top to bottom (spaces included). */
    fun pylon(text: String, maxHeight: Int = 4096): PylonLayout {
        val n = max(1, text.codePointCount(0, text.length))
        val cell = min(PYLON_CELL_PX, maxHeight / n)
        val fontPx = PYLON_FONT_PX * cell / PYLON_CELL_PX
        return PylonLayout(cell, cell * n, cell, fontPx, n)
    }

    // ---- personal message ---------------------------------------------------
    const val MESSAGE_WIDTH_PX = 2048
    const val MESSAGE_HEIGHT_PX = 192
    const val MESSAGE_FONT_PX = 56f
    val MESSAGE_FILL = Rgba(255, 210, 235, 0.9f)
    val MESSAGE_COPY_CENTRES = floatArrayOf(512f, 1536f)

    data class Layout(
        val width: Int,
        val height: Int,
        val scaleX: Float,
        val copyCentresX: FloatArray,
        val baselineCentreY: Float,
    )

    fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }
}
