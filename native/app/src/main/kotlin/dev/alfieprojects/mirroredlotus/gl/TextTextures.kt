package dev.alfieprojects.mirroredlotus.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import dev.alfieprojects.mirroredlotus.core.render.Rgba
import dev.alfieprojects.mirroredlotus.core.render.TextSpecs

/**
 * Rasterises the monument's text with an Android Canvas - the drawing half of
 * the web build's canvas textures. Sizes and positions come from
 * [TextSpecs]; this class only turns them into pixels.
 */
object TextTextures {

    class Pylon(val bitmap: Bitmap, val charCount: Int)

    fun marquee(text: String, maxWidth: Int): Bitmap {
        val paint = textPaint(TextSpecs.MARQUEE_FONT_PX, TextSpecs.MARQUEE_FILL, TextSpecs.MARQUEE_GLOW_BLUR)
        val layout = TextSpecs.marquee(paint.measureText(text), maxWidth)
        paint.textScaleX = layout.scaleX
        val bmp = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val baseline = layout.baselineCentreY - (paint.descent() + paint.ascent()) / 2
        for (x in layout.copyCentresX) canvas.drawText(text, x, baseline, paint)
        return bmp
    }

    fun pylon(text: String, maxHeight: Int): Pylon {
        val layout = TextSpecs.pylon(text, maxHeight)
        val paint = textPaint(layout.fontPx, TextSpecs.PYLON_FILL, TextSpecs.PYLON_GLOW_BLUR * layout.cell / TextSpecs.PYLON_CELL_PX)
        val bmp = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val centreOffset = -(paint.descent() + paint.ascent()) / 2
        var i = 0
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            val glyph = String(Character.toChars(cp))
            canvas.drawText(glyph, layout.glyphCentreX, layout.glyphCentreY(i) + centreOffset, paint)
            offset += Character.charCount(cp)
            i++
        }
        return Pylon(bmp, layout.charCount)
    }

    fun message(line: String): Bitmap {
        val paint = textPaint(TextSpecs.MESSAGE_FONT_PX, TextSpecs.MESSAGE_FILL, 0f)
        val bmp = Bitmap.createBitmap(TextSpecs.MESSAGE_WIDTH_PX, TextSpecs.MESSAGE_HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val baseline = TextSpecs.MESSAGE_HEIGHT_PX / 2f - (paint.descent() + paint.ascent()) / 2
        for (x in TextSpecs.MESSAGE_COPY_CENTRES) canvas.drawText(line, x, baseline, paint)
        return bmp
    }

    private fun textPaint(sizePx: Float, fill: Rgba, glowBlur: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SANS_SERIF
        textSize = sizePx
        textAlign = Paint.Align.CENTER
        color = fill.toArgb()
        // Canvas shadowBlur is roughly twice a Gaussian sigma; Android's radius is the blur radius.
        if (glowBlur > 0f) setShadowLayer(glowBlur, 0f, 0f, TextSpecs.GLOW.toArgb())
    }
}
