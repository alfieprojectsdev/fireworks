package dev.alfieprojects.mirroredlotus.core.story

import dev.alfieprojects.mirroredlotus.core.fx.Rng
import dev.alfieprojects.mirroredlotus.core.geo.Geo
import dev.alfieprojects.mirroredlotus.core.site.Sites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ExperienceDirectorTest {

    private val church = Sites.CHURCH

    private fun ticksFor(seconds: Double) = (seconds * ExperienceDirector.TICK_HZ).toInt()

    @Test
    fun `start places the monument at the site's scene offset, 15 m up`() {
        val user = Geo.destination(church.lat, church.lng, Math.toRadians(180.0), 1200.0) // south of it
        val d = ExperienceDirector(Rng(1))
        d.start(church, user.lat, user.lng, testMode = false)
        assertEquals(0.0, d.groupX, 0.5)
        assertEquals(-1200.0, d.groupZ, 0.5) // due north of the wearer
        assertEquals(15.0, d.groupY, 0.0)
        assertEquals(1200.0, d.userDistanceM, 0.5)
    }

    @Test
    fun `a second start is ignored`() {
        val d = ExperienceDirector(Rng(1))
        d.start(church, church.lat, church.lng, testMode = true)
        d.start(Sites.ORIGIN, 0.0, 0.0, testMode = false)
        assertEquals(church, d.site)
        assertTrue(d.testMode)
    }

    @Test
    fun `test mode puts the monument 40 m along the camera heading and reveals everything`() {
        val d = ExperienceDirector(Rng(1))
        d.start(church, 0.0, 0.0, testMode = true, forwardX = 1.0, forwardZ = 0.0) // facing east
        assertEquals(40.0, d.groupX, 1e-9)
        assertEquals(0.0, d.groupZ, 1e-9)
        assertEquals(0.0, d.userDistanceM, 0.0)
        repeat(ticksFor(5.0)) { d.tick() }
        assertTrue(d.showFireworks)
        assertTrue(d.marqueeBase > 0.5)
        assertTrue(d.statsBase > 0.5)
        assertEquals(0.0, d.beaconBase, 1e-9) // no beacon inside 650 m
        // GPS is ignored in test mode.
        d.onLocation(church.lat + 0.02, church.lng)
        assertEquals(0.0, d.userDistanceM, 0.0)
    }

    @Test
    fun `test mode without a heading falls back to north, like the web build`() {
        val d = ExperienceDirector(Rng(1))
        d.start(church, 0.0, 0.0, testMode = true, forwardX = 0.0, forwardZ = 0.0)
        assertEquals(0.0, d.groupX, 1e-9)
        assertEquals(-40.0, d.groupZ, 1e-9)
    }

    @Test
    fun `walking in fires every act once, in order, near its design distance`() {
        val acts = mutableListOf<Pair<ActEvent, Double>>()
        val d = ExperienceDirector(Rng(3), actListener = { e, m -> acts += e to m })
        val speed = 1.4 // m/s walking
        var remaining = 1790.0
        var pos = Geo.destination(church.lat, church.lng, Math.toRadians(225.0), remaining)
        d.start(church, pos.lat, pos.lng, testMode = false)
        var t = 0
        while (remaining > 0) {
            d.tick()
            t++
            if (t % ExperienceDirector.TICK_HZ == 0) { // a 1 Hz GPS fix
                remaining = maxOf(0.0, remaining - speed)
                pos = Geo.destination(church.lat, church.lng, Math.toRadians(225.0), remaining)
                d.onLocation(pos.lat, pos.lng)
            }
        }
        assertEquals(ActEvent.entries.toList(), acts.map { it.first })
        // Opacity acts fire when the lerped base passes 0.01, which trails the
        // design edge by a few metres of walking (the web build's deltaM too).
        assertEquals(1790.0, acts[0].second, 2.0) // beacon: already inside its ring
        assertEquals(800.0, acts[1].second, 2.0)
        assertTrue(acts[2].second in 394.0..400.0)
        assertTrue(acts[3].second in 96.0..100.0)
    }

    @Test
    fun `the monument glides to each new fix instead of teleporting`() {
        val d = ExperienceDirector(Rng(1))
        val start = Geo.destination(church.lat, church.lng, Math.toRadians(180.0), 500.0)
        d.start(church, start.lat, start.lng, testMode = false)
        val next = Geo.destination(church.lat, church.lng, Math.toRadians(180.0), 400.0)
        d.onLocation(next.lat, next.lng)
        d.tick()
        assertEquals(-498.0, d.groupZ, 0.1) // 2% of the 100 m step
        repeat(ticksFor(10.0)) { d.tick() }
        assertEquals(-400.0, d.groupZ, 0.5)
    }

    @Test
    fun `the personal message arms at 100 m and appears a minute later`() {
        val d = ExperienceDirector(Rng(1))
        val near = Geo.destination(church.lat, church.lng, 0.0, 150.0)
        d.start(church, near.lat, near.lng, testMode = false)
        repeat(ticksFor(120.0)) { d.tick() }
        assertFalse("not armed outside 100 m", d.messageVisible)

        val close = Geo.destination(church.lat, church.lng, 0.0, 90.0)
        d.onLocation(close.lat, close.lng)
        repeat(ticksFor(59.9)) { d.tick() }
        assertFalse(d.messageVisible)
        repeat(ticksFor(0.2)) { d.tick() }
        assertTrue(d.messageVisible)
        repeat(ticksFor(10.0)) { d.tick() }
        assertEquals(0.65, d.messageOpacity, 1e-9)
        assertTrue(d.messageRotation > 0)
    }

    @Test
    fun `no more than 15 fireworks are ever live`() {
        val d = ExperienceDirector(Rng(8))
        d.start(church, church.lat, church.lng, testMode = true)
        var peak = 0
        repeat(ticksFor(300.0)) {
            d.tick()
            peak = maxOf(peak, d.fireworks.liveCount)
            assertTrue(d.fireworks.liveCount <= 15)
        }
        assertTrue("the show actually saturates", peak >= 10)
    }

    @Test
    fun `a field test can re-place the monument ahead, even over a GPS-anchored session`() {
        val user = Geo.destination(church.lat, church.lng, Math.toRadians(180.0), 600.0)
        val d = ExperienceDirector(Rng(3))
        d.start(church, user.lat, user.lng, testMode = false)
        repeat(30) { d.tick() }
        d.placeAhead(church, forwardX = 1.0, forwardZ = 0.0) // facing east
        assertTrue(d.testMode)
        assertEquals(40.0, d.groupX, 1e-9)
        assertEquals(0.0, d.groupZ, 1e-9)
        assertEquals(0.0, d.userDistanceM, 0.0)
        d.onLocation(user.lat, user.lng) // later fixes no longer move it
        repeat(60) { d.tick() }
        assertEquals(40.0, d.groupX, 1e-9)
        assertEquals(0.0, d.groupZ, 1e-9)

        val fresh = ExperienceDirector(Rng(3))
        fresh.placeAhead(church, forwardX = 0.0, forwardZ = 1.0) // facing south
        assertTrue(fresh.started && fresh.testMode)
        assertEquals(40.0, fresh.groupZ, 1e-9)
    }

    @Test
    fun `the thermal ladder's firework cap holds`() {
        val d = ExperienceDirector(Rng(8))
        d.start(church, church.lat, church.lng, testMode = true)
        d.maxLiveFireworks = 4
        var peak = 0
        repeat(ticksFor(300.0)) {
            d.tick()
            peak = maxOf(peak, d.fireworks.liveCount)
        }
        assertEquals(4, peak)
    }

    @Test
    fun `fireworks stay dark beyond 800 m`() {
        val d = ExperienceDirector(Rng(8))
        val far = Geo.destination(church.lat, church.lng, 0.0, 1200.0)
        d.start(church, far.lat, far.lng, testMode = false)
        repeat(ticksFor(30.0)) { d.tick() }
        assertEquals(0, d.fireworks.liveCount)
        assertTrue(d.beaconBase > 0.6)
    }

    @Test
    fun `a nearby burst brightens the marquee only once its act has begun`() {
        val d = ExperienceDirector(Rng(4))
        val mid = Geo.destination(church.lat, church.lng, 0.0, 700.0) // fireworks on, marquee not
        d.start(church, mid.lat, mid.lng, testMode = false)
        repeat(ticksFor(60.0)) {
            d.tick()
            assertEquals(0.0, d.marqueeOpacity, 1e-9)
            assertEquals(0.0, d.statsOpacity, 1e-9)
        }
    }

    @Test
    fun `camera fades in from 1 to 3 s after the trigger`() {
        val d = ExperienceDirector(Rng(1))
        assertEquals(0.0, d.cameraFade(), 0.0)
        d.start(church, church.lat, church.lng, testMode = true)
        repeat(ticksFor(1.0)) { d.tick() }
        assertEquals(0.0, d.cameraFade(), 1e-9)
        repeat(ticksFor(1.0)) { d.tick() }
        assertEquals(0.5, d.cameraFade(), 1e-9)
        repeat(ticksFor(1.0)) { d.tick() }
        assertEquals(1.0, d.cameraFade(), 1e-9)
    }

    @Test
    fun `spin rates match the web build per tick`() {
        val d = ExperienceDirector(Rng(1))
        d.start(church, church.lat, church.lng, testMode = true)
        repeat(100) { d.tick() }
        assertEquals(-0.2, d.marqueeRotation, 1e-9)
    }

    @Test
    fun `ground ring breathes around 0_025`() {
        val d = ExperienceDirector(Rng(1))
        d.start(church, church.lat, church.lng, testMode = true)
        repeat(ticksFor(20.0)) {
            d.tick()
            assertTrue(d.groundOpacity() in 0.0099..0.0401)
        }
    }

    @Test
    fun `nothing happens before the trigger`() {
        val d = ExperienceDirector(Rng(1))
        repeat(ticksFor(10.0)) { d.tick() }
        assertEquals(0, d.fireworks.liveCount)
        assertEquals(0.0, hypot(d.beaconBase, d.marqueeBase), 0.0)
    }
}
