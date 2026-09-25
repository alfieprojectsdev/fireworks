package dev.alfieprojects.mirroredlotus.core.fx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Called when a rocket bursts, in scene coordinates. The audio engine listens. */
fun interface ExplosionListener {
    fun onExplosion(x: Float, y: Float, z: Float, shell: Int)
}

/**
 * The fireworks of `public/js/fireworks.js`, re-laid out for a phone GPU.
 *
 * The web build allocated a `BufferGeometry`, a material and hundreds of
 * `Vector3`s per burst and drew each firework as its own object. Here every
 * rocket and spark lives in preallocated structure-of-arrays slots, one
 * [update] advances them all, and [writeVertices] emits one interleaved
 * line-segment buffer for a single draw call. Nothing is allocated after
 * construction.
 *
 * The physics is a per-tick port (constants were tuned for ~60 Hz steps, which
 * is what [ExperienceDirector] feeds it), including the order of operations
 * and the order random numbers are drawn, so shapes, colours, timings and
 * shell mix match the original.
 */
class FireworkSystem(
    private val rng: Rng,
    private val listener: ExplosionListener? = null,
) {
    companion object {
        /** The spawner in the director never exceeds 15 live fireworks. */
        const val MAX_FIREWORKS = 16
        /** Chrysanthemum is the largest shell. */
        const val MAX_PARTICLES = 300
        /** Floats per emitted vertex: x, y, z, then premultiplied r, g, b. */
        const val FLOATS_PER_VERTEX = 6
        /** Upper bound on emitted vertices: two per particle plus a rocket streak. */
        const val MAX_VERTICES = MAX_FIREWORKS * (MAX_PARTICLES * 2 + 2)

        const val PEONY = 0
        const val WILLOW = 1
        const val CHRYSANTHEMUM = 2
        const val HEART = 3
        const val LEMNISCATE = 4
        const val LISSAJOUS = 5

        private const val WIND_X = 0.015f
        private const val WIND_Y = 0f
        private const val WIND_Z = -0.005f
        private const val ROCKET_GRAVITY = 0.2
        private const val ILLUMINATION_RANGE = 150.0

        fun particleCount(shell: Int): Int = when (shell) {
            CHRYSANTHEMUM -> 300
            HEART -> 120
            LEMNISCATE -> 160
            LISSAJOUS -> 180
            else -> 220
        }
    }

    // ---- per-firework state -------------------------------------------------
    private val active = BooleanArray(MAX_FIREWORKS)
    private val isRocket = BooleanArray(MAX_FIREWORKS)
    private val posX = DoubleArray(MAX_FIREWORKS)
    private val posY = DoubleArray(MAX_FIREWORKS)
    private val posZ = DoubleArray(MAX_FIREWORKS)
    private val velX = DoubleArray(MAX_FIREWORKS)
    private val velY = DoubleArray(MAX_FIREWORKS)
    private val velZ = DoubleArray(MAX_FIREWORKS)
    private val life = IntArray(MAX_FIREWORKS)
    private val maxLife = DoubleArray(MAX_FIREWORKS)
    private val shell = IntArray(MAX_FIREWORKS)
    private val count = IntArray(MAX_FIREWORKS)
    private val colour = FloatArray(MAX_FIREWORKS * 3)
    private val opacity = FloatArray(MAX_FIREWORKS)

    // ---- per-particle state, slot-major: index = slot * MAX_PARTICLES + i ---
    private val headX = FloatArray(MAX_FIREWORKS * MAX_PARTICLES)
    private val headY = FloatArray(MAX_FIREWORKS * MAX_PARTICLES)
    private val headZ = FloatArray(MAX_FIREWORKS * MAX_PARTICLES)
    private val tailX = FloatArray(MAX_FIREWORKS * MAX_PARTICLES)
    private val tailY = FloatArray(MAX_FIREWORKS * MAX_PARTICLES)
    private val tailZ = FloatArray(MAX_FIREWORKS * MAX_PARTICLES)
    private val pvX = FloatArray(MAX_FIREWORKS * MAX_PARTICLES)
    private val pvY = FloatArray(MAX_FIREWORKS * MAX_PARTICLES)
    private val pvZ = FloatArray(MAX_FIREWORKS * MAX_PARTICLES)
    private val lifespan = FloatArray(MAX_FIREWORKS * MAX_PARTICLES)
    private val alive = BooleanArray(MAX_FIREWORKS * MAX_PARTICLES)

    /** Brightness pulse for the marquee and pylons (`_textIllumination`). */
    var textIllumination: Double = 0.0
        private set

    /** Number of live fireworks, rockets and bursts alike (`fireworks.length`). */
    var liveCount: Int = 0
        private set

    /** Decays the illumination pulse by one tick (`tickIllumination`). */
    fun tickIllumination() {
        textIllumination = maxOf(0.0, textIllumination - 0.02)
    }

    /**
     * Launches a rocket from 30-60 m around the group's ground footprint, on its
     * north side (the web build's spawn geometry). Returns false when full.
     */
    fun spawn(groupX: Double, groupZ: Double): Boolean {
        val s = active.indexOfFirst { !it }
        if (s < 0) return false
        active[s] = true
        isRocket[s] = true
        liveCount++

        val hue = rng.nextDouble()
        Hsl.toRgb(hue, 1.0, 0.6, colour, s * 3)

        val dist = 30 + rng.nextDouble() * 30
        val angle = (rng.nextDouble() - 0.5) * 1.5
        posX[s] = sin(angle) * dist + groupX
        posY[s] = -40.0
        posZ[s] = -cos(angle) * dist + groupZ
        velX[s] = (rng.nextDouble() - 0.5) * 2
        velY[s] = 4 + rng.nextDouble() * 3
        velZ[s] = (rng.nextDouble() - 0.5) * 2

        life[s] = 0
        maxLife[s] = 120 + rng.nextDouble() * 60
        count[s] = 0
        opacity[s] = 1f
        return true
    }

    /**
     * Advances every firework one tick. [hasGroup] and the group position feed
     * the illumination pulse, exactly as `explode()` measured it.
     */
    fun update(hasGroup: Boolean, groupX: Double, groupY: Double, groupZ: Double) {
        for (s in 0 until MAX_FIREWORKS) {
            if (!active[s]) continue
            if (isRocket[s]) updateRocket(s, hasGroup, groupX, groupY, groupZ) else updateBurst(s)
        }
    }

    private fun updateRocket(s: Int, hasGroup: Boolean, gx: Double, gy: Double, gz: Double) {
        posX[s] += velX[s]; posY[s] += velY[s]; posZ[s] += velZ[s]
        velY[s] -= ROCKET_GRAVITY
        velX[s] += WIND_X; velY[s] += WIND_Y; velZ[s] += WIND_Z
        opacity[s] = (0.5 + rng.nextDouble() * 0.5).toFloat()
        if (velY[s] <= 1) explode(s, hasGroup, gx, gy, gz)
    }

    private fun explode(s: Int, hasGroup: Boolean, gx: Double, gy: Double, gz: Double) {
        isRocket[s] = false

        val r = rng.nextDouble()
        val type = when {
            r < 0.10 -> HEART
            r < 0.18 -> LEMNISCATE
            r < 0.26 -> LISSAJOUS
            else -> floor(rng.nextDouble() * 3).toInt()
        }
        shell[s] = type
        when (type) {
            HEART -> Hsl.toRgb(0.93, 1.0, 0.75, colour, s * 3)      // hot pink
            LEMNISCATE -> Hsl.toRgb(0.12, 1.0, 0.65, colour, s * 3) // amber gold
            LISSAJOUS -> Hsl.toRgb(0.55, 1.0, 0.70, colour, s * 3)  // electric cyan
        }

        val n = particleCount(type)
        count[s] = n
        val base = s * MAX_PARTICLES
        val px = posX[s]; val py = posY[s]; val pz = posZ[s]
        for (i in 0 until n) {
            // Shaped shells pre-place each spark on the curve so the outline is
            // there at the instant of the burst; velocity is a slow outward drift.
            var ox = 0.0; var oy = 0.0; var oz = 0.0
            var vx: Double; var vy: Double; var vz: Double
            val t = i.toDouble() / n * PI * 2
            when (type) {
                HEART -> {
                    val hx = 16 * sin(t).pow(3)
                    val hy = (rng.nextDouble() - 0.5) * 2
                    val hz = -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t))
                    val sc = 12.0 / 21
                    ox = hx * sc; oy = hy * sc; oz = hz * sc
                    val speed = 0.8 + rng.nextDouble() * 0.4
                    val len = norm(hx, hy, hz)
                    vx = hx / len * speed; vy = hy / len * speed; vz = hz / len * speed
                }
                LEMNISCATE -> {
                    val d = 1 + sin(t) * sin(t)
                    val lx = cos(t) / d
                    val lz = sin(t) * cos(t) / d
                    val ly = (rng.nextDouble() - 0.5) * 0.12
                    ox = lx * 13; oy = ly * 13; oz = lz * 13
                    val speed = 0.8 + rng.nextDouble() * 0.4
                    val len = norm(lx, ly, lz)
                    vx = lx / len * speed; vy = ly / len * speed; vz = lz / len * speed
                }
                LISSAJOUS -> {
                    val lx = sin(3 * t + PI / 2)
                    val lz = sin(2 * t)
                    val ly = (rng.nextDouble() - 0.5) * 0.12
                    ox = lx * 11; oy = ly * 11; oz = lz * 11
                    val speed = 0.8 + rng.nextDouble() * 0.4
                    val len = norm(lx, ly, lz)
                    vx = lx / len * speed; vy = ly / len * speed; vz = lz / len * speed
                }
                else -> {
                    val rx = rng.nextDouble() - 0.5
                    val ry = rng.nextDouble() - 0.5
                    val rz = rng.nextDouble() - 0.5
                    val len = norm(rx, ry, rz)
                    val speed = when (type) {
                        PEONY -> rng.nextDouble() * 3 + 4
                        WILLOW -> rng.nextDouble() * 2 + 2
                        else -> rng.nextDouble() * 4 + 5
                    }
                    vx = rx / len * speed; vy = ry / len * speed; vz = rz / len * speed
                }
            }
            val k = base + i
            pvX[k] = vx.toFloat(); pvY[k] = vy.toFloat(); pvZ[k] = vz.toFloat()
            val sx = (px + ox).toFloat(); val sy = (py + oy).toFloat(); val sz = (pz + oz).toFloat()
            headX[k] = sx; headY[k] = sy; headZ[k] = sz
            tailX[k] = sx; tailY[k] = sy; tailZ[k] = sz
            lifespan[k] = (if (type >= HEART) 0.88 + rng.nextDouble() * 0.18 else 0.7 + rng.nextDouble() * 0.6).toFloat()
            alive[k] = true
        }

        if (hasGroup) {
            val dx = px - gx; val dy = py - gy; val dz = pz - gz
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist < ILLUMINATION_RANGE) {
                textIllumination = maxOf(textIllumination, 1.0 - dist / ILLUMINATION_RANGE)
            }
        }
        listener?.onExplosion(px.toFloat(), py.toFloat(), pz.toFloat(), type)
    }

    private fun updateBurst(s: Int) {
        life[s]++
        val fadeRate = life[s] / maxLife[s]
        val type = shell[s]
        val streak = if (type == CHRYSANTHEMUM) 3f else if (type >= HEART) 0.8f else 1.5f
        val gravity = if (type == WILLOW) 0.05f else if (type >= HEART) 0.004f else 0.03f
        val drag = if (type >= HEART) 0.985f else 0.96f
        val base = s * MAX_PARTICLES
        var allDead = true
        for (i in 0 until count[s]) {
            val k = base + i
            if (fadeRate * lifespan[k] > 1) {
                alive[k] = false
                continue
            }
            allDead = false
            val vx = pvX[k]; val vy = pvY[k]; val vz = pvZ[k]
            headX[k] += vx; headY[k] += vy; headZ[k] += vz
            tailX[k] = headX[k] - vx * streak
            tailY[k] = headY[k] - vy * streak
            tailZ[k] = headZ[k] - vz * streak
            pvX[k] = vx * drag + WIND_X
            pvY[k] = (vy - gravity) * drag + WIND_Y
            pvZ[k] = vz * drag + WIND_Z
        }
        val baseOpacity = 1 - fadeRate * fadeRate
        opacity[s] = (baseOpacity * (0.6 + rng.nextDouble() * 0.4)).toFloat()
        if (allDead || life[s] >= maxLife[s]) {
            active[s] = false
            liveCount--
        }
    }

    /**
     * Writes every visible streak as a `GL_LINES` pair into [out], six floats a
     * vertex with colour premultiplied by opacity (so the shader is a pass-
     * through and blending is `ONE, ONE`). Returns the vertex count.
     */
    fun writeVertices(out: FloatArray): Int {
        var v = 0
        var o = 0
        for (s in 0 until MAX_FIREWORKS) {
            if (!active[s]) continue
            val a = opacity[s]
            if (isRocket[s]) {
                // 0xffeebb, the rocket's warm white.
                val r = 1f * a; val g = (0xee / 255f) * a; val b = (0xbb / 255f) * a
                o = put(out, o, posX[s].toFloat(), posY[s].toFloat(), posZ[s].toFloat(), r, g, b)
                o = put(
                    out, o,
                    (posX[s] - velX[s] * 2).toFloat(),
                    (posY[s] - velY[s] * 2).toFloat(),
                    (posZ[s] - velZ[s] * 2).toFloat(),
                    r, g, b,
                )
                v += 2
                continue
            }
            val r = colour[s * 3] * a; val g = colour[s * 3 + 1] * a; val b = colour[s * 3 + 2] * a
            val base = s * MAX_PARTICLES
            for (i in 0 until count[s]) {
                val k = base + i
                if (!alive[k]) continue
                o = put(out, o, headX[k], headY[k], headZ[k], r, g, b)
                o = put(out, o, tailX[k], tailY[k], tailZ[k], r, g, b)
                v += 2
            }
        }
        return v
    }

    private fun put(out: FloatArray, o: Int, x: Float, y: Float, z: Float, r: Float, g: Float, b: Float): Int {
        out[o] = x; out[o + 1] = y; out[o + 2] = z
        out[o + 3] = r; out[o + 4] = g; out[o + 5] = b
        return o + FLOATS_PER_VERTEX
    }

    private fun norm(x: Double, y: Double, z: Double): Double {
        val n = sqrt(x * x + y * y + z * z)
        return if (n == 0.0) 1.0 else n // THREE.Vector3.normalize divides by (length || 1)
    }

    // ---- read-only views for tests and telemetry ----------------------------
    fun isActive(slot: Int) = active[slot]
    fun isRocketAt(slot: Int) = isRocket[slot]
    fun shellAt(slot: Int) = shell[slot]
    fun particleCountAt(slot: Int) = count[slot]
    fun rocketY(slot: Int) = posY[slot]
    fun rocketVy(slot: Int) = velY[slot]
}
