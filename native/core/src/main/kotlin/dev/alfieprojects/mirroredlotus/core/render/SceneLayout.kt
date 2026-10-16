package dev.alfieprojects.mirroredlotus.core.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The monument's fixed geometry, in metres relative to the group origin (which
 * floats 15 m up, so the ground is local y = -15). Shrine scale, after the
 * Quezon Memorial Shrine's ~66 m: four faint legs rising from the ground to
 * world y = 50, capped by the message crown at world y = 50-62, with a stat
 * panel of stacked digits at the foot of each leg.
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

    /**
     * The four legs: faint 50 m pillars from the ground to the crown, kept so
     * the monument still reads as the shrine. They carry no text.
     */
    const val LEG_WIDTH = 5f
    const val LEG_HEIGHT = 50f
    const val LEG_Y = 10f // bottom at local -15 (ground), top at +35 (world 50)
    val LEG_COLOUR = BEACON_COLOUR
    /** Leg brightness as a share of the crown's, so they never compete with the digits. */
    const val LEG_SHARE_OF_CROWN = 0.1

    /**
     * Each leg's stat panel starts at the ground: digits stacked in [DIGIT_CELL_M]
     * cells, the unit in a [UNIT_STRIP_M] strip beside them. A 4 m cell holds a
     * ~2.6 m digit, readable on the A07 (about 22 px per degree, 18 px needed)
     * from about 180 m. Matches TextSpecs.PYLON_UNIT_STRIP (1.4 / 4 = 0.35).
     */
    const val DIGIT_CELL_M = 4f
    const val UNIT_STRIP_M = 1.4f
    const val PYLON_RING = 35f // flush with the crown radius

    /** Y rotation of the Years, Months, Hours, Days pylons (south, east, north, west faces). */
    val PYLON_ROTATIONS = floatArrayOf(0f, (PI / 2).toFloat(), PI.toFloat(), (-PI / 2).toFloat())

    fun panelWidth(): Float = DIGIT_CELL_M + UNIT_STRIP_M
    fun panelHeight(digitCount: Int): Float = DIGIT_CELL_M * digitCount
    /** Local y of a panel's centre, so its bottom sits on the ground. */
    fun panelCentreY(digitCount: Int): Float = GROUND_Y + panelHeight(digitCount) / 2

    const val GROUND_INNER = 12f
    const val GROUND_OUTER = 24f
    const val GROUND_SEGMENTS = 64
    const val GROUND_Y = -15f
    val GROUND_COLOUR = floatArrayOf(0.85f, 0.55f, 1.0f)

    const val MESSAGE_RADIUS = 12f
    const val MESSAGE_HEIGHT = 3f
    const val MESSAGE_SEGMENTS = 64

    /** Local position of pylon [i] after Three's `rotation.y = r; translateZ(35)`. */
    fun pylonOffsetX(i: Int): Float = PYLON_RING * sin(PYLON_ROTATIONS[i])
    fun pylonOffsetZ(i: Int): Float = PYLON_RING * cos(PYLON_ROTATIONS[i])
}
