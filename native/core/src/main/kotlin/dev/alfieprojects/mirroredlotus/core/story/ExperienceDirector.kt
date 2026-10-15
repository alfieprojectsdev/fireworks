package dev.alfieprojects.mirroredlotus.core.story

import dev.alfieprojects.mirroredlotus.core.fx.ExplosionListener
import dev.alfieprojects.mirroredlotus.core.fx.FireworkSystem
import dev.alfieprojects.mirroredlotus.core.fx.Rng
import dev.alfieprojects.mirroredlotus.core.geo.SceneGeo
import dev.alfieprojects.mirroredlotus.core.site.Site
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Milestones leave the render thread through this; the app records and logs them. */
fun interface ActListener {
    fun onAct(event: ActEvent, distanceM: Double)
}

/**
 * Everything `scene.js` did per animation frame, as a deterministic state
 * machine advanced in fixed [TICK_SECONDS] steps.
 *
 * The web build tuned every per-frame constant (lerp rates, spin, gravity) at
 * ~60 Hz and then had to gate its physics against 120 Hz displays. Here the
 * renderer runs as fast as the panel wants and calls [tick] from a fixed-step
 * accumulator, so the story plays at the same speed on the A56's 120 Hz panel,
 * the A07's 60 Hz one, and a JUnit test.
 *
 * Confined to the render thread. Locations from the main thread arrive through
 * `GLSurfaceView.queueEvent`, never by direct call.
 */
class ExperienceDirector(
    rng: Rng = Rng(),
    explosionListener: ExplosionListener? = null,
    private val actListener: ActListener? = null,
) {
    companion object {
        const val TICK_HZ = 60
        const val TICK_SECONDS = 1.0 / TICK_HZ
        const val TICK_MS = 1000.0 / TICK_HZ

        /** The group origin floats this high; its ground is at local y = -15. */
        const val GROUP_HEIGHT_M = 15.0
        /** Test mode puts the monument this far in front of the wearer. */
        const val TEST_MODE_DISTANCE_M = 40.0

        const val MESSAGE_DELAY_MS = 60_000.0
        const val MESSAGE_MAX_OPACITY = 0.65
        /** +0.008 every 50 ms in the web build. */
        const val MESSAGE_FADE_PER_MS = 0.008 / 50.0

        /** Camera feed fades in over 2 s, starting 1 s after the trigger (the splash's cross-fade). */
        const val CAMERA_FADE_DELAY_MS = 1000.0
        const val CAMERA_FADE_MS = 2000.0
    }

    private val rng = rng
    val fireworks = FireworkSystem(rng, explosionListener)

    var started = false
        private set
    var testMode = false
        private set
    var site: Site? = null
        private set

    /** Distance from the wearer to the active site; 0 in test mode. */
    var userDistanceM = Double.POSITIVE_INFINITY
        private set

    var groupX = 0.0; private set
    var groupY = GROUP_HEIGHT_M; private set
    var groupZ = 0.0; private set
    private var targetX = 0.0
    private var targetZ = 0.0
    private var anchorPending = false

    var beaconBase = 0.0; private set
    var marqueeBase = 0.0; private set
    var statsBase = 0.0; private set
    var showFireworks = false; private set

    /** Opacities as the materials would have been set this tick. */
    var marqueeOpacity = 0.0; private set
    var statsOpacity = 0.0; private set
    var beaconOpacity = 0.0; private set

    var marqueeRotation = 0.0; private set
    var messageRotation = 0.0; private set

    private var messageArmedAtMs = -1.0
    var messageVisible = false; private set
    var messageOpacity = 0.0; private set

    private val fired = BooleanArray(ActEvent.entries.size)

    /**
     * Cap on live fireworks: the web build's 15, lowered by the thermal ladder
     * (`ThermalPolicy`) when the phone runs hot. Render thread.
     */
    var maxLiveFireworks = 15

    var ticks = 0L
        private set

    /** Story time since [start], in milliseconds of fixed ticks. */
    val elapsedMs: Double get() = ticks * TICK_MS

    /**
     * Locks the experience to [site] (the nearest in-range site at trigger time)
     * for the rest of the session. A second call is ignored, as in the web build.
     *
     * In test mode the monument stands [TEST_MODE_DISTANCE_M] ahead of the
     * wearer along ([forwardX], [forwardZ]) - the horizontal direction the
     * camera faces - and every act is revealed at once. The web build always
     * put it 40 m *north*, which is only "ahead" when facing north.
     */
    fun start(
        site: Site,
        userLat: Double,
        userLng: Double,
        testMode: Boolean,
        forwardX: Double = 0.0,
        forwardZ: Double = -1.0,
    ) {
        if (started) return
        started = true
        this.site = site
        this.testMode = testMode
        if (testMode) {
            placeAheadNow(forwardX, forwardZ)
        } else {
            val o = SceneGeo.offset(userLat, userLng, site.lat, site.lng)
            groupX = o.x
            groupZ = o.z
            userDistanceM = o.distanceM
        }
        groupY = GROUP_HEIGHT_M
        targetX = groupX
        targetZ = groupZ
    }

    /**
     * Field tests: puts the monument [TEST_MODE_DISTANCE_M] ahead along
     * ([forwardX], [forwardZ]) at once, with no glide, so a measurement taken
     * a moment later sees it where it belongs. Switches to test-mode staging
     * (every act shown, later fixes ignored) and starts on [site] if needed.
     */
    fun placeAhead(site: Site, forwardX: Double, forwardZ: Double) {
        if (!started) {
            start(site, site.lat, site.lng, testMode = true, forwardX = forwardX, forwardZ = forwardZ)
            return
        }
        testMode = true
        anchorPending = false
        placeAheadNow(forwardX, forwardZ)
        groupY = GROUP_HEIGHT_M
        targetX = groupX
        targetZ = groupZ
    }

    private fun placeAheadNow(forwardX: Double, forwardZ: Double) {
        val n = sqrt(forwardX * forwardX + forwardZ * forwardZ)
        val fx = if (n > 1e-6) forwardX / n else 0.0
        val fz = if (n > 1e-6) forwardZ / n else -1.0
        groupX = fx * TEST_MODE_DISTANCE_M
        groupZ = fz * TEST_MODE_DISTANCE_M
        userDistanceM = 0.0
    }

    /**
     * A GPS fix after the trigger. The camera never translates, so the monument
     * must be re-placed on every fix to stay pinned to the site as the wearer
     * walks; [tick] glides toward the new spot so GPS jitter never teleports it.
     * Ignored in test mode, whose staging is deliberately frozen at "arrived".
     */
    fun onLocation(lat: Double, lng: Double) {
        val s = site ?: return
        if (!started || testMode) return
        val o = SceneGeo.offset(lat, lng, s.lat, s.lng)
        userDistanceM = o.distanceM
        targetX = o.x
        targetZ = o.z
        anchorPending = true
    }

    /** One fixed step of `_animate()`'s physics branch. */
    fun tick() {
        // Story time starts at start(): frames drawn before the trigger don't count.
        if (!started) return
        ticks++

        fireworks.tickIllumination()
        val illumination = fireworks.textIllumination

        if (anchorPending) {
            groupX += (targetX - groupX) * 0.02
            groupY += (GROUP_HEIGHT_M - groupY) * 0.02
            groupZ += (targetZ - groupZ) * 0.02
            val dx = targetX - groupX; val dz = targetZ - groupZ; val dy = GROUP_HEIGHT_M - groupY
            if (sqrt(dx * dx + dy * dy + dz * dz) < 0.5) anchorPending = false
        }

        val d = userDistanceM
        showFireworks = Acts.fireworksOn(d)
        beaconBase += (Acts.beaconTarget(d) * 0.7 - beaconBase) * 0.02
        marqueeBase += (Acts.marqueeTarget(d) * 0.8 - marqueeBase) * 0.02
        statsBase += (Acts.statsTarget(d) * 0.8 - statsBase) * 0.02

        fireOnce(ActEvent.BEACON_ON, beaconBase > 0.01)
        fireOnce(ActEvent.FIREWORKS_ON, showFireworks)
        fireOnce(ActEvent.MARQUEE_ON, marqueeBase > 0.01)
        fireOnce(ActEvent.STATS_ON, statsBase > 0.01)

        // The pulse is scaled by each act's own base opacity, so a burst can't
        // reveal the marquee or pylons before their act begins.
        val mqPulse = illumination * 0.6 * min(1.0, marqueeBase * 5)
        val stPulse = illumination * 0.4 * min(1.0, statsBase * 5)
        marqueeOpacity = marqueeBase + mqPulse
        statsOpacity = statsBase * 0.5 + stPulse
        beaconOpacity = beaconBase

        marqueeRotation -= 0.002
        tickMessage()

        if (showFireworks && fireworks.liveCount < maxLiveFireworks) {
            if (rng.nextDouble() < 0.1) fireworks.spawn(groupX, groupZ)
            if (rng.nextDouble() < 0.02 && fireworks.liveCount < maxLiveFireworks - 2) {
                fireworks.spawn(groupX, groupZ)
                fireworks.spawn(groupX, groupZ)
            }
        }
        fireworks.update(true, groupX, groupY, groupZ)
    }

    /**
     * The personal message arms once the wearer is truly close, then appears a
     * minute later. Counting from the trigger instead would play the payoff
     * while the monument is still ~1.8 km away.
     */
    private fun tickMessage() {
        if (messageArmedAtMs < 0 && userDistanceM <= Acts.MESSAGE_ARM_M) messageArmedAtMs = elapsedMs
        if (!messageVisible && messageArmedAtMs >= 0 && elapsedMs - messageArmedAtMs >= MESSAGE_DELAY_MS) {
            messageVisible = true
        }
        if (messageVisible) {
            messageOpacity = min(MESSAGE_MAX_OPACITY, messageOpacity + MESSAGE_FADE_PER_MS * TICK_MS)
            messageRotation += 0.003
        }
    }

    private fun fireOnce(event: ActEvent, condition: Boolean) {
        if (!condition || fired[event.ordinal]) return
        fired[event.ordinal] = true
        actListener?.onAct(event, userDistanceM)
    }

    /** Faint breathing of the ground ring, on story time. */
    fun groundOpacity(): Double = 0.025 + 0.015 * sin(elapsedMs * 0.0006)

    /** Camera-feed brightness: black, then a 2 s ease-in-out from 1 s after the trigger. */
    fun cameraFade(): Double {
        if (!started) return 0.0
        val t = ((elapsedMs - CAMERA_FADE_DELAY_MS) / CAMERA_FADE_MS).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    fun hasFired(event: ActEvent) = fired[event.ordinal]
}
