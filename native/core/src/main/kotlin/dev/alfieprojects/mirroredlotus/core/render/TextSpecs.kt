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
    // Digits only (Alfie's call, 16 Oct 2026): the number stacked one digit per
    // square cell, the unit running sideways in a strip on the right. The web
    // build stacked the whole caption ("252 Months"), number on top, which put
    // the number above the frame by the time it was lit.
    const val PYLON_CELL_PX = 256
    /** Digit size in a 256 px cell: a digit's height is about 0.65 of its cell. */
    const val PYLON_DIGIT_FONT_PX = 230f
    /** Unit strip width as a share of a cell (1.4 m beside 4 m digits). */
    const val PYLON_UNIT_STRIP = 0.35f
    /** Unit letters' size as a share of the strip's width. */
    const val PYLON_UNIT_FONT = 0.8f
    /** The unit may run at most this share of the stack's height. */
    const val PYLON_UNIT_MAX_LENGTH = 0.9f
    const val PYLON_GLOW_BLUR = 12f
    val PYLON_FILL = Rgba(255, 255, 255, 0.9f)

    data class PylonLayout(
        val width: Int,
        val height: Int,
        val cell: Int,
        val digitFontPx: Float,
        val digitCount: Int,
        val stripPx: Int,
        val unitFontPx: Float,
    ) {
        fun digitCentreY(i: Int): Float = cell * (i + 0.5f)
        val digitCentreX: Float get() = cell / 2f
        val unitCentreX: Float get() = cell + stripPx / 2f
        val unitCentreY: Float get() = height / 2f
        /** Longest the unit may run along the stack; the app squeezes it to fit. */
        val unitMaxLengthPx: Float get() = height * PYLON_UNIT_MAX_LENGTH
    }

    /** One square cell per digit, stacked top to bottom, plus the unit strip. */
    fun pylon(number: String, maxHeight: Int = 4096): PylonLayout {
        val n = max(1, number.codePointCount(0, number.length))
        val cell = min(PYLON_CELL_PX, maxHeight / n)
        val strip = (cell * PYLON_UNIT_STRIP).roundToInt()
        return PylonLayout(
            width = cell + strip,
            height = cell * n,
            cell = cell,
            digitFontPx = PYLON_DIGIT_FONT_PX * cell / PYLON_CELL_PX,
            digitCount = n,
            stripPx = strip,
            unitFontPx = strip * PYLON_UNIT_FONT,
        )
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
