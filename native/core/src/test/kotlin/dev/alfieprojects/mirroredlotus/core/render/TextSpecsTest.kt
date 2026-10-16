package dev.alfieprojects.mirroredlotus.core.render

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSpecsTest {

    @Test
    fun `short marquee keeps the web build's power-of-two width`() {
        val l = TextSpecs.marquee(1000f)
        assertEquals(4096, l.width) // 3 * (1000 + 300) = 3900 -> 4096
        assertEquals(1f, l.scaleX, 0f)
        assertEquals(512, l.height)
        assertEquals(683f, l.copyCentresX[0], 0f)
        assertEquals(2048f, l.copyCentresX[1], 0f)
        assertEquals(3413f, l.copyCentresX[2], 0f)
    }

    @Test
    fun `long marquee is capped and squeezed, keeping its share of the circumference`() {
        val l = TextSpecs.marquee(1700.4f)
        // 3 * (1701 + 300) = 6003 -> 8192 logical, capped at 4096.
        assertEquals(4096, l.width)
        assertEquals(0.5f, l.scaleX, 0f)
        assertEquals(683f, l.copyCentresX[0], 0.5f)
        // Words still cover 1701/8192 of the texture per copy, as before the cap.
        val shareOfWidth = 1701 * l.scaleX / l.width
        assertEquals(1701f / 8192f, shareOfWidth, 1e-6f)
    }

    @Test
    fun `pylons get one square cell per digit and a unit strip beside them`() {
        val l = TextSpecs.pylon("252")
        assertEquals(3, l.digitCount)
        assertEquals(256, l.cell)
        assertEquals(90, l.stripPx) // 0.35 of a cell
        assertEquals(256 + 90, l.width)
        assertEquals(256 * 3, l.height)
        assertEquals(230f, l.digitFontPx, 0f)
        assertEquals(128f, l.digitCentreY(0), 0f)
        assertEquals(640f, l.digitCentreY(2), 0f)
        assertEquals(128f, l.digitCentreX, 0f)
        assertEquals(301f, l.unitCentreX, 0f)
        assertEquals(384f, l.unitCentreY, 0f)
        assertEquals(72f, l.unitFontPx, 1e-4f)
        assertEquals(691.2f, l.unitMaxLengthPx, 1e-3f)
    }

    @Test
    fun `every stat today fits the texture at full cell size`() {
        // Hours is the longest: six digits at both sites until 2119.
        for (digits in listOf("21", "252", "7671", "184104")) {
            val l = TextSpecs.pylon(digits)
            assertEquals(256, l.cell)
            assertEquals(digits.length * 256, l.height)
        }
    }

    @Test
    fun `a very long number shrinks its cells to fit the texture limit`() {
        val l = TextSpecs.pylon("12345678901234567890123456789", maxHeight = 4096)
        assertEquals(29, l.digitCount)
        assertEquals(141, l.cell)
        assertEquals(true, l.height <= 4096)
        assertEquals(230f * 141 / 256, l.digitFontPx, 1e-4f)
        assertEquals(49, l.stripPx)
    }

    @Test
    fun `argb packing`() {
        assertEquals(0x66FFDCA0, TextSpecs.GLOW.toArgb())
        assertEquals(0xE6FFFFFF.toInt(), TextSpecs.PYLON_FILL.toArgb())
    }
}
