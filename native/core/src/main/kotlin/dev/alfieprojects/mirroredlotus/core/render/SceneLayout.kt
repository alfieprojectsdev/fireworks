package dev.alfieprojects.mirroredlotus.core.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The monument's fixed geometry, in metres relative to the group origin (which
 * floats 15 m up, so the ground is local y = -15). Shrine scale, after the
 * Quezon Memorial Shrine's ~66 m: four stat pylons rising from the ground to
 * world y = 50, capped by the message crown at world y = 50-62.
 *
 * An earlier "Tower of Light" tractor-beam cone was rejected as tacky; do not
 * reintroduce light-beam forms (the beacon is a thin pillar, not a beam).
 */
object SceneLayout {
    const val CROWN_RADIUS = 35f
    const val CROWN_HEIGHT = 12f
    const val CROWN_Y = 41f // spans local 35-47, world 50-62
    const val CROWN_SEGMENTS = 64

    const val BEACON_RADIUS = 8f
    const val BEACON_HEIGHT = 2000f
    const val BEACON_Y = 1000f // base at ground level, apex 2 km up
    const val BEACON_SEGMENTS = 32
    val BEACON_COLOUR = floatArrayOf(0.85f, 0.95f, 1.0f) // icy cyan-white

    const val PYLON_HEIGHT = 50f
    const val PYLON_Y = 10f // bottom at local -15 (ground), top at +35 (world 50)
    const val PYLON_RING = 35f // flush with the crown radius

    /** Y rotation of the Years, Months, Hours, Days pylons (south, east, north, west faces). */
    val PYLON_ROTATIONS = floatArrayOf(0f, (PI / 2).toFloat(), PI.toFloat(), (-PI / 2).toFloat())

    const val GROUND_INNER = 12f
    const val GROUND_OUTER = 24f
    const val GROUND_SEGMENTS = 64
    const val GROUND_Y = -15f
    val GROUND_COLOUR = floatArrayOf(0.85f, 0.55f, 1.0f)

    const val MESSAGE_RADIUS = 12f
    const val MESSAGE_HEIGHT = 3f
    const val MESSAGE_SEGMENTS = 64

    /** Pylons keep square glyph cells: width = height / characters. */
    fun pylonWidth(charCount: Int): Float = PYLON_HEIGHT / charCount

    /** Local position of pylon [i] after Three's `rotation.y = r; translateZ(35)`. */
    fun pylonOffsetX(i: Int): Float = PYLON_RING * sin(PYLON_ROTATIONS[i])
    fun pylonOffsetZ(i: Int): Float = PYLON_RING * cos(PYLON_ROTATIONS[i])
}
