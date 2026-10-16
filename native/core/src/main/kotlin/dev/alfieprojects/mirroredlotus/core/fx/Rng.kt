package dev.alfieprojects.mirroredlotus.core.fx

/**
 * Small seedable PRNG (SplitMix64). Stands in for `Math.random()` so the
 * fireworks and the synth are reproducible under test. Not thread-safe: each
 * thread that needs randomness owns its own instance.
 */
class Rng(seed: Long = System.nanoTime()) {
    private var state = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Uniform in `[0, 1)`, like `Math.random()`. */
    fun nextDouble(): Double = (nextLong() ushr 11) * (1.0 / (1L shl 53))

    fun nextFloat(): Float = (nextLong() ushr 40) * (1.0f / (1 shl 24))
}
