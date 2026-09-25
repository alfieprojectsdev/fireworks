package dev.alfieprojects.mirroredlotus.core.render

import kotlin.math.hypot

/**
 * What `mesh.frag` does with a fragment seen from behind (`uBackFaces`).
 *
 * Every web material was double-sided and additive with no depth test, so the
 * far side of the crown and the back of a pylon showed through as mirrored
 * text. Only the side facing the viewer should read. But the crown is meant to
 * be walked into, and from inside a cylinder *every* visible face is a back
 * face, so text cylinders flip to showing their backs mirrored (which then
 * read correctly) once the viewer is inside them.
 *
 * Mesh winding makes outward faces counter-clockwise, as in Three.js, so
 * `gl_FrontFacing` is true on the outside (`BackFacesTest` checks this).
 */
object BackFaces {
    /** Discard back faces: only the side facing the viewer draws. */
    const val HIDE = 0
    /** Draw back faces with the texture mirrored horizontally, so text reads from inside. */
    const val MIRROR = 1
    /** Draw both sides as they are (the untextured beacon and ground ring). */
    const val SHOW = 2

    /**
     * Mode for text wrapped on a cylinder of [radius] about the group's vertical
     * axis, seen from the camera at the scene origin. Inside is judged
     * horizontally, because the crown floats overhead.
     */
    fun forCylinderText(groupX: Double, groupZ: Double, radius: Float): Int =
        if (hypot(groupX, groupZ) < radius) MIRROR else HIDE

    /** Pylons are flat panels: only their front reads, from anywhere. */
    const val FOR_PANEL_TEXT = HIDE
}
