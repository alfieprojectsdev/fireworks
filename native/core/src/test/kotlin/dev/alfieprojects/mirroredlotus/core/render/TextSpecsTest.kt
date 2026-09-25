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
    fun `pylons get one square cell per character`() {
        val l = TextSpecs.pylon("18 Years")
        assertEquals(8, l.charCount)
        assertEquals(256, l.width)
        assertEquals(256 * 8, l.height)
        assertEquals(180f, l.fontPx, 0f)
        assertEquals(128f, l.glyphCentreY(0), 0f)
        assertEquals(128f, l.glyphCentreX, 0f)
    }

    @Test
    fun `very long pylon text shrinks its cells to fit the texture limit`() {
        val l = TextSpecs.pylon("1234567890123456789 Hours", maxHeight = 4096)
        assertEquals(25, l.charCount)
        assertEquals(163, l.cell)
        assertEquals(true, l.height <= 4096)
        assertEquals(180f * 163 / 256, l.fontPx, 1e-4f)
    }

    @Test
    fun `argb packing`() {
        assertEquals(0x66FFDCA0, TextSpecs.GLOW.toArgb())
        assertEquals(0x99FFFFFF.toInt(), TextSpecs.PYLON_FILL.toArgb())
    }
}
