package dev.alfieprojects.mirroredlotus

import android.content.Intent
import dev.alfieprojects.mirroredlotus.core.site.Site
import dev.alfieprojects.mirroredlotus.core.site.Sites

/**
 * Launch-time switches, set from a laptop with `adb shell am start` - synthetic
 * taps are blocked on the Galaxy A07 (stable-still found `input tap` silently
 * does nothing), so everything worth toggling in a test is reachable here.
 *
 * ```
 * adb shell am start -n dev.alfieprojects.mirroredlotus/.MainActivity \
 *     --ez testMode true --ez hud true
 * adb shell am start -n dev.alfieprojects.mirroredlotus/.MainActivity \
 *     --es simulate church --ef simSpeed 20 --ez hud true
 * ```
 */
data class DebugOptions(
    /** Start straight into test mode (the triple-tap), once permissions are in. */
    val testMode: Boolean = false,
    /** Show the HUD from launch. */
    val hud: Boolean = false,
    /** Pose follows camera exposure time (default) or the newest sample (A/B). */
    val syncCamera: Boolean = true,
    /** Replace GPS with a scripted walk-in toward this site. */
    val simulate: Site? = null,
    val simStartM: Double = 2000.0,
    val simSpeedMps: Double = 1.4,
    val simFromBearingDeg: Double = 225.0,
    /** Compass smoothing time constant for the heading fusion, seconds. */
    val headingTauS: Double = 2.0,
) {
    companion object {
        fun from(intent: Intent?): DebugOptions {
            if (intent == null) return DebugOptions()
            val d = DebugOptions()
            return DebugOptions(
                testMode = intent.getBooleanExtra("testMode", d.testMode),
                hud = intent.getBooleanExtra("hud", d.hud),
                syncCamera = intent.getBooleanExtra("syncCamera", d.syncCamera),
                simulate = intent.getStringExtra("simulate")?.let { Sites.byId(it) ?: Sites.CHURCH },
                simStartM = intent.getFloatExtra("simStartM", d.simStartM.toFloat()).toDouble(),
                simSpeedMps = intent.getFloatExtra("simSpeed", d.simSpeedMps.toFloat()).toDouble(),
                simFromBearingDeg = intent.getFloatExtra("simBearing", d.simFromBearingDeg.toFloat()).toDouble(),
                headingTauS = intent.getFloatExtra("headingTau", d.headingTauS.toFloat()).toDouble().coerceIn(0.1, 30.0),
            )
        }
    }
}
