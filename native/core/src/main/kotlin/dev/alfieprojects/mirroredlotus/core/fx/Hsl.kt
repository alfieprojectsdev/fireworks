package dev.alfieprojects.mirroredlotus.core.fx

/**
 * `THREE.Color.setHSL` from the vendored r128, bit for bit, so shells keep the
 * colours they had in the web build. (r128 predates Three's colour management,
 * so there is no sRGB conversion to reproduce.)
 */
object Hsl {
    fun toRgb(h: Double, s: Double, l: Double, out: FloatArray, offset: Int = 0) {
        val hh = ((h % 1.0) + 1.0) % 1.0
        val ss = s.coerceIn(0.0, 1.0)
        val ll = l.coerceIn(0.0, 1.0)
        if (ss == 0.0) {
            out[offset] = ll.toFloat(); out[offset + 1] = ll.toFloat(); out[offset + 2] = ll.toFloat()
            return
        }
        val p = if (ll <= 0.5) ll * (1 + ss) else ll + ss - ll * ss
        val q = 2 * ll - p
        out[offset] = hue2rgb(q, p, hh + 1.0 / 3).toFloat()
        out[offset + 1] = hue2rgb(q, p, hh).toFloat()
        out[offset + 2] = hue2rgb(q, p, hh - 1.0 / 3).toFloat()
    }

    private fun hue2rgb(p: Double, q: Double, tIn: Double): Double {
        var t = tIn
        if (t < 0) t += 1
        if (t > 1) t -= 1
        return when {
            t < 1.0 / 6 -> p + (q - p) * 6 * t
            t < 0.5 -> q
            t < 2.0 / 3 -> p + (q - p) * 6 * (2.0 / 3 - t)
            else -> p
        }
    }
}
