package dev.alfieprojects.mirroredlotus.core.audio

import dev.alfieprojects.mirroredlotus.core.fx.Rng
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Supplies the listener's camera->scene orientation `[w,x,y,z]`; false when none yet. */
fun interface ListenerPose {
    fun read(out: DoubleArray): Boolean
}

/**
 * Wait-free single-producer single-consumer queue of burst triggers, from the
 * render thread (which runs the fireworks) to the audio thread. A full queue
 * drops the newest trigger - one missing bang beats a blocked render.
 */
class TriggerQueue(capacity: Int = 64) {
    private val data = FloatArray(capacity * 4)
    private val cap = capacity
    private val head = AtomicInteger(0) // consumer
    private val tail = AtomicInteger(0) // producer

    fun offer(x: Float, y: Float, z: Float, shell: Int): Boolean {
        val t = tail.get()
        if (t - head.get() >= cap) return false
        val o = (t % cap) * 4
        data[o] = x; data[o + 1] = y; data[o + 2] = z; data[o + 3] = shell.toFloat()
        tail.set(t + 1) // volatile write publishes the slot
        return true
    }

    /** Pops into [out] (x, y, z, shell); false when empty. */
    fun poll(out: FloatArray): Boolean {
        val h = head.get()
        if (h == tail.get()) return false
        val o = (h % cap) * 4
        out[0] = data[o]; out[1] = data[o + 1]; out[2] = data[o + 2]; out[3] = data[o + 3]
        head.set(h + 1)
        return true
    }
}

/**
 * Mixes explosion voices into interleaved stereo float for an `AudioTrack`.
 *
 * Replaces the web build's per-burst `PannerNode` (HRTF, inverse distance,
 * ref 30 m) with: Web Audio's own `equalpower` panning law on the burst's
 * azimuth relative to the camera, the same inverse-distance attenuation, and a
 * one-pole head-shadow low-pass on the far ear for some of HRTF's sense of
 * side. The listener follows the phone every block, so bursts stay put in space
 * as the wearer turns - as `syncAudioListener` did per animation frame.
 *
 * [render] runs on the audio thread and allocates nothing.
 */
class SpatialMixer(
    private val sampleRate: Int,
    private val listener: ListenerPose,
    voices: Int = 16,
    private val maxBlock: Int = 512,
    seed: Long = System.nanoTime(),
) {
    val queue = TriggerQueue()

    /** Toggled from the UI thread (auto-detect or the note button). */
    @Volatile
    var earphones = false

    private val pool = Array(voices) { ExplosionVoice(sampleRate) }
    private val rng = Rng(seed)
    private val trig = FloatArray(4)
    private val mono = FloatArray(maxBlock)
    private val send = FloatArray(maxBlock)
    private val dry = FloatArray(maxBlock * 2)
    private val pose = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    private val compressor = Compressor(sampleRate)
    private val reverb = Reverb(sampleRate)
    private var wet = 0f
    private val wetCoef = exp(-1.0 / (0.5 * sampleRate)).toFloat() // setTargetAtTime(.., 0.5)

    val activeVoices: Int get() = pool.count { it.active }

    /**
     * Drops queued triggers - bursts that happened while audio was paused would
     * otherwise all go off at once on resume. Audio thread only.
     */
    fun drainStale() {
        while (queue.poll(trig)) Unit
    }

    /** Fills [out] with [frames] interleaved stereo frames. */
    fun render(out: FloatArray, frames: Int) {
        while (queue.poll(trig)) start(trig[0], trig[1], trig[2], trig[3].toInt())
        listener.read(pose)
        var done = 0
        while (done < frames) {
            val n = minOf(maxBlock, frames - done)
            renderBlock(out, done * 2, n)
            done += n
        }
    }

    private fun start(x: Float, y: Float, z: Float, shell: Int) {
        val voice = pool.firstOrNull { !it.active } ?: return // all busy: drop, never block
        voice.trigger(shell, earphones, x, y, z, rng)
    }

    private fun renderBlock(out: FloatArray, offset: Int, n: Int) {
        java.util.Arrays.fill(dry, 0, n * 2, 0f)
        java.util.Arrays.fill(send, 0, n, 0f)
        val useReverb = earphones
        for (v in pool) {
            if (!v.active) continue
            v.render(mono, n)
            // Target gains for this block; ramp from the last block's to avoid zipper noise.
            val (tl, tr, shadow) = panFor(v.x, v.y, v.z)
            val gl0 = if (v.gainL < 0f) tl else v.gainL
            val gr0 = if (v.gainR < 0f) tr else v.gainR
            val a = shadowCoef(shadow)
            val leftIsFar = tr > tl
            var sL = v.shadowL
            var sR = v.shadowR
            val invN = 1f / n
            for (i in 0 until n) {
                val u = (i + 1) * invN
                val s = mono[i]
                var l = s * (gl0 + (tl - gl0) * u)
                var r = s * (gr0 + (tr - gr0) * u)
                if (leftIsFar) { sL = l + a * (sL - l); l = sL } else { sR = r + a * (sR - r); r = sR }
                dry[i * 2] += l
                dry[i * 2 + 1] += r
                if (useReverb) send[i] += (l + r) * 0.5f
            }
            v.shadowL = sL; v.shadowR = sR
            v.gainL = tl; v.gainR = tr
        }
        val wetTarget = if (useReverb) EARPHONE_WET else 0f
        for (i in 0 until n) {
            compressor.process(dry, i * 2)
            wet = wetTarget + (wet - wetTarget) * wetCoef
            val o = offset + i * 2
            out[o] = dry[i * 2]
            out[o + 1] = dry[i * 2 + 1]
            if (wet > 1e-4f) reverb.process(send[i], wet, out, o)
            out[o] = out[o].coerceIn(-1f, 1f)
            out[o + 1] = out[o + 1].coerceIn(-1f, 1f)
        }
    }

    // Returned as a reused triple to keep the loop allocation-free.
    private val pan = PanResult()

    private class PanResult {
        var left = 0f; var right = 0f; var shadow = 0f
        operator fun component1() = left
        operator fun component2() = right
        operator fun component3() = shadow
    }

    /**
     * Gains for a burst at scene ([x],[y],[z]) heard by the current [pose]:
     * `equalpower` on the azimuth folded into the frontal half-plane, times
     * `inverse` distance attenuation (ref 30 m, rolloff 1).
     */
    private fun panFor(x: Float, y: Float, z: Float): PanResult {
        val w = pose[0]; val qx = -pose[1]; val qy = -pose[2]; val qz = -pose[3] // conjugate
        // v_listener = conj(q) * p
        val tx = 2 * (qy * z - qz * y)
        val ty = 2 * (qz * x - qx * z)
        val tz = 2 * (qx * y - qy * x)
        val lx = x + w * tx + (qy * tz - qz * ty)
        val lz = z + w * tz + (qx * ty - qy * tx)
        var azimuth = Math.toDegrees(atan2(lx, -lz)) // + = to the right
        if (azimuth < -90) azimuth = -180 - azimuth
        if (azimuth > 90) azimuth = 180 - azimuth
        val u = (azimuth + 90) / 180
        val dist = sqrt((x * x + y * y + z * z).toDouble())
        val att = distanceGain(dist)
        pan.left = (cos(u * PI / 2) * att).toFloat()
        pan.right = (sin(u * PI / 2) * att).toFloat()
        pan.shadow = abs(sin(Math.toRadians(azimuth))).toFloat()
        return pan
    }

    private fun shadowCoef(amount: Float): Float {
        val cutoff = 18_000.0 - (18_000.0 - 3_500.0) * amount
        return exp(-2 * PI * cutoff / sampleRate).toFloat()
    }

    companion object {
        /** `reverbGain` target in earphone mode. */
        const val EARPHONE_WET = 0.28f
        const val REF_DISTANCE = 30.0

        /** Web Audio `inverse` model: ref / (ref + rolloff * (max(d, ref) - ref)). */
        fun distanceGain(d: Double): Double = REF_DISTANCE / (REF_DISTANCE + (max(d, REF_DISTANCE) - REF_DISTANCE))
    }
}
