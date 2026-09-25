package dev.alfieprojects.mirroredlotus.core.audio

import dev.alfieprojects.mirroredlotus.core.fx.FireworkSystem
import dev.alfieprojects.mirroredlotus.core.fx.Rng

/**
 * One firework's report, synthesised sample by sample - a streaming port of
 * `playExplosion()` in audio.js, which built a fresh Web Audio node graph per
 * burst. Here a fixed pool of these is reused, so a burst costs no allocation
 * and no graph setup on the audio thread.
 *
 * Four layers, all starting at the burst:
 * - **thump**: triangle oscillator gliding down to 0.4x over 0.3 s, soft-clipped
 *   into harmonics small speakers can play, decaying to -60 dB by 0.35 s;
 * - **body**: band-passed noise where phone speakers have output (300-600 Hz);
 * - **sizzle**: high-passed noise, the burning sparks, 0.6-1.1 s long;
 * - **crackle**: six delayed pops, chrysanthemum only.
 *
 * Earphone mode drops the thump to frequencies earphones can reproduce as real
 * bass instead of harmonics.
 */
class ExplosionVoice(private val sampleRate: Int) {
    var active = false
        private set

    /** Scene position of the burst, for the mixer's panning. */
    var x = 0f; private set
    var y = 0f; private set
    var z = 0f; private set

    // Per-voice head-shadow filter state, owned by the mixer.
    var shadowL = 0f
    var shadowR = 0f
    var gainL = 0f
    var gainR = 0f

    private var shell = 0
    private var n = 0

    private var thumpPhase = 0.0
    private var thumpFreq = 0.0
    private var thumpFreqFactor = 1.0
    private var thumpGlideSamples = 0
    private val thumpGain = ExpRamp()
    private var thumpEnd = 0

    private val bodyFilter = Biquad()
    private val bodyGain = ExpRamp()
    private var bodyEnd = 0

    private val sizzleFilter = Biquad()
    private val sizzleGain = ExpRamp()
    private var sizzleEnd = 0

    private val crackleStart = IntArray(CRACKLES)
    private val crackleGain = FloatArray(CRACKLES)
    private val crackleFactor = FloatArray(CRACKLES)
    private val crackleLevel = FloatArray(CRACKLES)
    private var crackles = 0
    private val crackleLen = (0.06 * sampleRate).toInt()

    private var end = 0
    private val noise = Rng(0x5eed)

    fun trigger(shell: Int, earphones: Boolean, px: Float, py: Float, pz: Float, rng: Rng) {
        this.shell = shell
        x = px; y = py; z = pz
        n = 0
        shadowL = 0f; shadowR = 0f
        gainL = -1f; gainR = -1f // "not yet panned": the mixer jumps straight to the target

        val f0 = thumpHz(shell, earphones)
        thumpFreq = f0
        thumpPhase = 0.0
        thumpGlideSamples = (0.3 * sampleRate).toInt()
        thumpFreqFactor = Math.pow(0.4, 1.0 / thumpGlideSamples)
        val g0 = when {
            shell == FireworkSystem.HEART -> 0.4f
            shell >= FireworkSystem.LEMNISCATE -> 0.55f
            else -> 0.75f
        }
        thumpGain.start(g0, 0.001f, 0.35, sampleRate)
        thumpEnd = (0.35 * sampleRate).toInt()

        val bodyDur = when {
            shell == FireworkSystem.HEART -> 0.08
            shell >= FireworkSystem.LEMNISCATE -> 0.10
            else -> 0.14
        }
        bodyFilter.reset()
        bodyFilter.bandpass(sampleRate, bodyHz(shell), 0.7)
        bodyGain.start(1f, 0.001f, bodyDur, sampleRate)
        bodyEnd = (bodyDur * sampleRate).toInt()

        // Web Audio's highpass Q is a resonance in dB; its default of 1 dB is ~1.12 linear.
        val sizzleDur = 0.6 + rng.nextDouble() * 0.5
        sizzleFilter.reset()
        sizzleFilter.highpass(sampleRate, 3000.0, 1.122)
        sizzleGain.start(0.12f, 0.001f, sizzleDur, sampleRate)
        sizzleEnd = (sizzleDur * sampleRate).toInt()

        crackles = 0
        var last = sizzleEnd
        if (shell == FireworkSystem.CHRYSANTHEMUM) {
            crackles = CRACKLES
            val len = (0.04 * sampleRate).toInt()
            for (k in 0 until CRACKLES) {
                val delay = 0.06 + rng.nextDouble() * 0.5
                crackleStart[k] = (delay * sampleRate).toInt()
                crackleGain[k] = (0.25 + rng.nextDouble() * 0.3).toFloat()
                crackleFactor[k] = Math.pow(0.001 / crackleGain[k], 1.0 / len).toFloat()
                crackleLevel[k] = crackleGain[k]
                last = maxOf(last, crackleStart[k] + crackleLen)
            }
        }
        end = maxOf(last, thumpEnd, bodyEnd)
        active = true
    }

    /** Writes the next [frames] mono samples into [out] (overwriting). Returns false once finished. */
    fun render(out: FloatArray, frames: Int): Boolean {
        if (!active) return false
        for (i in 0 until frames) {
            var s = 0f
            if (n < thumpEnd) {
                // Triangle in [-1, 1].
                val p = thumpPhase
                val tri = (if (p < 0.5) 4 * p - 1 else 3 - 4 * p).toFloat()
                s += SoftClip.shape(tri) * thumpGain.next()
                thumpPhase += thumpFreq / sampleRate
                if (thumpPhase >= 1.0) thumpPhase -= 1.0
                if (n < thumpGlideSamples) thumpFreq *= thumpFreqFactor
            }
            val white = (noise.nextFloat() * 2f - 1f)
            if (n < bodyEnd) s += bodyFilter.process(white) * bodyGain.next()
            if (n < sizzleEnd) s += sizzleFilter.process(white) * sizzleGain.next()
            for (k in 0 until crackles) {
                val start = crackleStart[k]
                if (n >= start && n < start + crackleLen) {
                    s += (noise.nextFloat() * 2f - 1f) * crackleLevel[k]
                    if (crackleLevel[k] > 0.001f) crackleLevel[k] *= crackleFactor[k]
                }
            }
            out[i] = s
            n++
            if (n >= end) {
                for (j in i + 1 until frames) out[j] = 0f
                active = false
                return false
            }
        }
        return true
    }

    companion object {
        const val CRACKLES = 6

        fun thumpHz(shell: Int, earphones: Boolean): Double = if (earphones) {
            when (shell) {
                FireworkSystem.WILLOW -> 80.0
                FireworkSystem.HEART -> 120.0
                FireworkSystem.LEMNISCATE -> 95.0
                FireworkSystem.LISSAJOUS -> 110.0
                else -> 100.0
            }
        } else {
            when (shell) {
                FireworkSystem.WILLOW -> 120.0
                FireworkSystem.HEART -> 180.0
                FireworkSystem.LEMNISCATE -> 135.0
                FireworkSystem.LISSAJOUS -> 160.0
                else -> 150.0
            }
        }

        fun bodyHz(shell: Int): Double = when (shell) {
            FireworkSystem.WILLOW -> 300.0
            FireworkSystem.HEART -> 600.0
            FireworkSystem.LEMNISCATE -> 380.0
            FireworkSystem.LISSAJOUS -> 560.0
            else -> 420.0
        }
    }
}
