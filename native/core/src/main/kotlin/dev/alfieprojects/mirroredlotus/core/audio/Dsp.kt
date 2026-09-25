package dev.alfieprojects.mirroredlotus.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** RBJ-cookbook biquad in transposed direct form II. */
class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    fun reset() {
        z1 = 0f; z2 = 0f
    }

    /** Constant 0 dB peak gain bandpass, as Web Audio's `bandpass`. */
    fun bandpass(sampleRate: Int, freq: Double, q: Double) {
        val w0 = 2 * PI * freq / sampleRate
        val alpha = sin(w0) / (2 * q)
        set(alpha, 0.0, -alpha, 1 + alpha, -2 * cos(w0), 1 - alpha)
    }

    fun highpass(sampleRate: Int, freq: Double, q: Double) {
        val w0 = 2 * PI * freq / sampleRate
        val alpha = sin(w0) / (2 * q)
        val c = cos(w0)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * Web Audio's `exponentialRampToValueAtTime` as a per-sample multiplier: from
 * [from] to [to] over [seconds], then holding [to].
 */
class ExpRamp {
    var value = 0f
        private set
    private var factor = 1f
    private var remaining = 0
    private var target = 0f

    fun start(from: Float, to: Float, seconds: Double, sampleRate: Int) {
        value = from
        target = to
        remaining = maxOf(1, (seconds * sampleRate).toInt())
        factor = (to.toDouble() / from).pow(1.0 / remaining).toFloat()
    }

    fun next(): Float {
        val v = value
        if (remaining > 0) {
            value *= factor
            if (--remaining == 0) value = target
        }
        return v
    }
}

/**
 * The web build's soft-clip curve, `(pi + 200) x / (pi + 200 |x|)` sampled at
 * 256 points, applied with `WaveShaperNode`'s interpolation. It turns the thump
 * oscillator into a harmonic-rich pulse a phone speaker can actually reproduce.
 */
object SoftClip {
    private val curve = FloatArray(256) { i ->
        val x = i * 2.0 / 256 - 1
        ((PI + 200) * x / (PI + 200 * kotlin.math.abs(x))).toFloat()
    }

    fun shape(x: Float): Float {
        val v = (curve.size - 1) * 0.5f * (x + 1)
        if (v <= 0f) return curve[0]
        if (v >= curve.size - 1) return curve[curve.size - 1]
        val i = floor(v).toInt()
        val f = v - i
        return curve[i] + (curve[i + 1] - curve[i]) * f
    }
}

/**
 * Stereo-linked feed-forward compressor standing in for the web build's
 * `DynamicsCompressorNode` (threshold -6 dB, knee 3 dB, ratio 4, attack 1 ms,
 * release 100 ms). Keeps a volley of bursts from clipping the phone speaker.
 */
class Compressor(
    sampleRate: Int,
    private val thresholdDb: Float = -6f,
    private val kneeDb: Float = 3f,
    private val ratio: Float = 4f,
    attackSeconds: Double = 0.001,
    releaseSeconds: Double = 0.1,
) {
    private val attack = exp(-1.0 / (attackSeconds * sampleRate)).toFloat()
    private val release = exp(-1.0 / (releaseSeconds * sampleRate)).toFloat()
    private var envDb = 0f

    /** Current gain reduction in dB (<= 0), for tests and meters. */
    val reductionDb: Float get() = envDb

    fun staticCurveDb(inputDb: Float): Float {
        val over = inputDb - thresholdDb
        return when {
            2 * over < -kneeDb -> inputDb
            2 * kotlin.math.abs(over) <= kneeDb -> {
                val t = over + kneeDb / 2
                inputDb + (1 / ratio - 1) * t * t / (2 * kneeDb)
            }
            else -> thresholdDb + over / ratio
        }
    }

    /** Processes one stereo frame in place in [buf] at [i] (interleaved L, R). */
    fun process(buf: FloatArray, i: Int) {
        val peak = maxOf(kotlin.math.abs(buf[i]), kotlin.math.abs(buf[i + 1]), 1e-9f)
        val inDb = 20 * log10(peak)
        val want = staticCurveDb(inDb) - inDb
        envDb = if (want < envDb) attack * envDb + (1 - attack) * want else release * envDb + (1 - release) * want
        val g = 10f.pow(envDb / 20)
        buf[i] *= g
        buf[i + 1] *= g
    }
}

/**
 * Small Freeverb (Schroeder-Moorer): four damped combs and two allpasses per
 * channel. Stands in for the web build's 2 s synthesised-noise convolver, which
 * would be ~96k taps per channel in the time domain - far too heavy for a phone
 * audio thread. Only used in earphone mode.
 */
class Reverb(sampleRate: Int, roomSize: Float = 0.84f, damping: Float = 0.2f) {
    private val scale = sampleRate / 44_100.0
    private val combL = intArrayOf(1116, 1188, 1277, 1356).map { Comb(len(it), roomSize, damping) }
    private val combR = intArrayOf(1139, 1211, 1300, 1379).map { Comb(len(it), roomSize, damping) }
    private val apL = intArrayOf(556, 441).map { Allpass(len(it)) }
    private val apR = intArrayOf(579, 464).map { Allpass(len(it)) }

    private fun len(n: Int) = maxOf(1, (n * scale).toInt())

    /** Mono in, adds the wet stereo signal times [gain] into `out[i]`, `out[i+1]`. */
    fun process(input: Float, gain: Float, out: FloatArray, i: Int) {
        val x = input * INPUT_GAIN
        var l = 0f
        var r = 0f
        for (c in combL) l += c.process(x)
        for (c in combR) r += c.process(x)
        for (a in apL) l = a.process(l)
        for (a in apR) r = a.process(r)
        out[i] += l * gain
        out[i + 1] += r * gain
    }

    private class Comb(size: Int, private val feedback: Float, private val damp: Float) {
        private val buf = FloatArray(size)
        private var idx = 0
        private var store = 0f
        fun process(x: Float): Float {
            val y = buf[idx]
            store = y * (1 - damp) + store * damp
            buf[idx] = x + store * feedback
            if (++idx == buf.size) idx = 0
            return y
        }
    }

    private class Allpass(size: Int) {
        private val buf = FloatArray(size)
        private var idx = 0
        fun process(x: Float): Float {
            val b = buf[idx]
            buf[idx] = x + b * 0.5f
            if (++idx == buf.size) idx = 0
            return b - x
        }
    }

    companion object {
        /** Sets the impulse response's energy near a normalised Web Audio convolver's (~0.15). */
        const val INPUT_GAIN = 0.03f
    }
}
