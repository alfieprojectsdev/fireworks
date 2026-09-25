package dev.alfieprojects.mirroredlotus.core.orientation

import dev.alfieprojects.mirroredlotus.core.math.Quat

/**
 * A short, timestamped record of orientations that can be read back at any
 * instant - the heart of the camera-synchronised pose.
 *
 * The camera image on screen shows the world as it was when the frame was
 * exposed, tens of milliseconds ago. Drawing the overlay with the orientation
 * of *that* instant, rather than the newest sensor sample, keeps the monument
 * glued to the church in the picture while the phone turns; drawing it with
 * the newest sample makes it slide ahead of the image and snap back, which is
 * the "swimming" the WebView build had on top of its bridge latency.
 *
 * One writer (the sensor thread), any number of readers (render, audio). The
 * lock is held for a few array reads; everything is preallocated.
 */
class OrientationHistory(capacity: Int = 512) {

    companion object {
        const val EMPTY = 0
        const val INTERPOLATED = 1
        /** Asked for a time older than anything retained; oldest sample returned. */
        const val CLAMPED_OLDEST = 2
        /** Asked for a time newer than the newest sample; newest returned. */
        const val CLAMPED_NEWEST = 3
    }

    private val times = LongArray(capacity)
    private val quats = DoubleArray(capacity * 4)
    private var head = 0 // next write slot
    private var size = 0
    private val lock = Any()

    val count: Int get() = synchronized(lock) { size }

    /** Appends a sample. Samples that don't advance time are dropped, not reordered. */
    fun push(tNanos: Long, q: DoubleArray) = synchronized(lock) {
        if (size > 0 && tNanos <= times[(head - 1 + times.size) % times.size]) return@synchronized
        times[head] = tNanos
        val o = head * 4
        quats[o] = q[0]; quats[o + 1] = q[1]; quats[o + 2] = q[2]; quats[o + 3] = q[3]
        head = (head + 1) % times.size
        if (size < times.size) size++
    }

    fun clear() = synchronized(lock) {
        size = 0
        head = 0
    }

    /** Newest sample into [out]; returns its timestamp, or `Long.MIN_VALUE` when empty. */
    fun latest(out: DoubleArray): Long = synchronized(lock) {
        if (size == 0) return@synchronized Long.MIN_VALUE
        val i = (head - 1 + times.size) % times.size
        copy(i, out)
        times[i]
    }

    /**
     * Orientation at [tNanos] into [out], slerped between the two samples that
     * bracket it. Returns one of the status constants; [out] is untouched only
     * when [EMPTY].
     */
    fun sampleAt(tNanos: Long, out: DoubleArray): Int = synchronized(lock) {
        if (size == 0) return@synchronized EMPTY
        val n = times.size
        val newest = (head - 1 + n) % n
        if (tNanos >= times[newest]) {
            copy(newest, out)
            return@synchronized if (tNanos == times[newest]) INTERPOLATED else CLAMPED_NEWEST
        }
        // Walk back from the newest: render asks for ~30-80 ms ago, a handful of steps at 200 Hz.
        var later = newest
        for (k in 1 until size) {
            val earlier = (newest - k + n) % n
            if (times[earlier] <= tNanos) {
                val span = (times[later] - times[earlier]).toDouble()
                val u = if (span <= 0) 0.0 else (tNanos - times[earlier]) / span
                Quat.slerpInto(quats, earlier * 4, quats, later * 4, u, out)
                return@synchronized INTERPOLATED
            }
            later = earlier
        }
        copy(later, out)
        CLAMPED_OLDEST
    }

    private fun copy(i: Int, out: DoubleArray) {
        val o = i * 4
        out[0] = quats[o]; out[1] = quats[o + 1]; out[2] = quats[o + 2]; out[3] = quats[o + 3]
    }
}
