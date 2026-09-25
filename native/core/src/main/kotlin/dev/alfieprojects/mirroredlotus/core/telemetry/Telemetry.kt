package dev.alfieprojects.mirroredlotus.core.telemetry

/**
 * Event rate over a sliding window, from event timestamps. Written by one
 * thread (sensor, render or camera) and read by the HUD at ~2 Hz; the lock is
 * uncontended in practice and the ring is preallocated.
 */
class RateMeter(private val windowNanos: Long = 1_000_000_000L, capacity: Int = 512) {
    private val stamps = LongArray(capacity)
    private var head = 0
    private var size = 0
    private val lock = Any()

    fun record(tNanos: Long) = synchronized(lock) {
        stamps[head] = tNanos
        head = (head + 1) % stamps.size
        if (size < stamps.size) size++
    }

    /** Events per second over the last window ending at [nowNanos]; 0 when idle. */
    fun rateHz(nowNanos: Long): Double = synchronized(lock) {
        var n = 0
        var oldest = Long.MAX_VALUE
        var newest = Long.MIN_VALUE
        for (k in 0 until size) {
            val t = stamps[(head - 1 - k + stamps.size) % stamps.size]
            if (nowNanos - t > windowNanos) break
            n++
            if (t < oldest) oldest = t
            if (t > newest) newest = t
        }
        if (n < 2 || newest <= oldest) return@synchronized 0.0
        (n - 1) * 1e9 / (newest - oldest)
    }
}

/** Exponential moving average; single writer, torn reads of a double are harmless for a readout. */
class Ema(private val alpha: Double = 0.1) {
    @Volatile
    var value: Double = Double.NaN
        private set

    fun add(sample: Double) {
        value = if (value.isNaN()) sample else value + alpha * (sample - value)
    }
}
