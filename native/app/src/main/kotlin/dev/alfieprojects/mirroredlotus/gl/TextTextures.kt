package dev.alfieprojects.mirroredlotus.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import dev.alfieprojects.mirroredlotus.core.render.Rgba
import dev.alfieprojects.mirroredlotus.core.render.TextSpecs
import dev.alfieprojects.mirroredlotus.core.story.AnniversaryStats

/**
 * Rasterises the monument's text with an Android Canvas - the drawing half of
 * the web build's canvas textures. Sizes and positions come from
 * [TextSpecs]; this class only turns them into pixels.
 */
object TextTextures {

    class Pylon(val bitmap: Bitmap, val digitCount: Int)

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

    /** Digits stacked top to bottom, the unit reading downward in the strip on the right. */
    fun pylon(stat: AnniversaryStats.PylonStat, maxHeight: Int): Pylon {
        val layout = TextSpecs.pylon(stat.number, maxHeight)
        val glow = TextSpecs.PYLON_GLOW_BLUR * layout.cell / TextSpecs.PYLON_CELL_PX
        val bmp = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val digits = textPaint(layout.digitFontPx, TextSpecs.PYLON_FILL, glow).apply { isFakeBoldText = true }
        val digitOffset = -(digits.descent() + digits.ascent()) / 2
        stat.number.forEachIndexed { i, ch ->
            canvas.drawText(ch.toString(), layout.digitCentreX, layout.digitCentreY(i) + digitOffset, digits)
        }

        val unit = textPaint(layout.unitFontPx, TextSpecs.PYLON_FILL, glow).apply { letterSpacing = 0.12f }
        val length = unit.measureText(stat.unit)
        if (length > layout.unitMaxLengthPx) unit.textScaleX = layout.unitMaxLengthPx / length
        canvas.save()
        canvas.rotate(90f, layout.unitCentreX, layout.unitCentreY)
        canvas.drawText(stat.unit, layout.unitCentreX, layout.unitCentreY - (unit.descent() + unit.ascent()) / 2, unit)
        canvas.restore()
        return Pylon(bmp, layout.digitCount)
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
