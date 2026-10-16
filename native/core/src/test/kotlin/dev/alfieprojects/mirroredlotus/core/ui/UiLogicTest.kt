package dev.alfieprojects.mirroredlotus.core.ui

import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy
import dev.alfieprojects.mirroredlotus.core.ui.CompassBanner.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {

    @Test
    fun `three taps inside the window trigger, and the count resets`() {
        val d = MultiTapDetector()
        assertEquals(MultiTapDetector.Result.TICK, d.onTap(0))
        assertEquals(MultiTapDetector.Result.TICK, d.onTap(500))
        assertEquals(MultiTapDetector.Result.TRIGGERED, d.onTap(1000))
        assertEquals(MultiTapDetector.Result.TICK, d.onTap(1100))
    }

    @Test
    fun `a slow tap restarts the count`() {
        val d = MultiTapDetector()
        d.onTap(0); d.onTap(500)
        assertEquals(MultiTapDetector.Result.TICK, d.onTap(1101)) // 601 ms gap
        assertEquals(MultiTapDetector.Result.TICK, d.onTap(1200))
        assertEquals(MultiTapDetector.Result.TRIGGERED, d.onTap(1300))
    }

    @Test
    fun `banner locks on a good absolute heading and then fades`() {
        val b = CompassBanner()
        b.start(0)
        assertEquals(State.CALIBRATING, b.update(10))
        b.onOrientation(100, absolute = false, good = true)
        assertEquals(State.CALIBRATING, b.update(200))
        b.onOrientation(300, absolute = true, good = true)
        assertEquals(State.LOCKED, b.update(310))
        assertEquals(State.LOCKED, b.update(2799))
        assertEquals(State.HIDDEN, b.update(2800))
    }

    @Test
    fun `banner settles on active after 8 s when heading never proved good`() {
        val b = CompassBanner()
        b.start(0)
        b.onOrientation(100, absolute = false, good = true)
        assertEquals(State.CALIBRATING, b.update(7999))
        assertEquals(State.ACTIVE, b.update(8000))
        assertEquals(State.HIDDEN, b.update(10_500))
    }

    @Test
    fun `banner asks for a figure-8 when the compass says it is unreliable`() {
        val b = CompassBanner()
        b.start(0)
        b.onOrientation(100, absolute = true, good = false)
        assertEquals(State.NEEDS_CALIBRATION, b.update(8000))
        assertEquals(State.NEEDS_CALIBRATION, b.update(12_999))
        assertEquals(State.HIDDEN, b.update(13_000))
    }

    @Test
    fun `banner just fades with no orientation at all`() {
        val b = CompassBanner()
        b.start(0)
        assertEquals(State.HIDDEN, b.update(8000))
    }

    @Test
    fun `heading quality prefers the numeric estimate, then the status`() {
        assertTrue(HeadingQuality.isGood(0, 0.2f))
        assertFalse(HeadingQuality.isGood(3, 0.5f))
        assertTrue(HeadingQuality.isGood(2, -1f))
        assertFalse(HeadingQuality.isGood(1, Float.NaN))
        assertTrue(HeadingQuality.isGood(-1, -1f))
    }

    @Test
    fun `distance hints print like the web build`() {
        assertEquals(DistanceHint.Metres(240), DistanceHint.of(244.9))
        assertEquals(DistanceHint.Metres(990), DistanceHint.of(999.99))
        assertEquals(DistanceHint.Kilometres("1"), DistanceHint.of(1000.0))
        assertEquals(DistanceHint.Kilometres("1.4"), DistanceHint.of(1440.0))
        assertEquals(DistanceHint.Kilometres("2"), DistanceHint.of(2099.0))
        assertEquals(DistanceHint.Kilometres("10.5"), DistanceHint.of(10_550.0))
    }

    @Test
    fun `alignment wraps the delta into plus or minus 180`() {
        // Camera faces north-west (315), monument due north-east (45): 270 apart -> -90.
        val a = Alignment.of(fwdX = -1.0, fwdZ = -1.0, groupX = 10.0, groupZ = -10.0)
        assertEquals(315.0, a.cameraDeg, 1e-9)
        assertEquals(45.0, a.monumentDeg!!, 1e-9)
        assertEquals(-90.0, a.deltaDeg!!, 1e-9)
        val pre = Alignment.of(0.0, -1.0, null, null)
        assertEquals(0.0, pre.cameraDeg, 1e-9)
        assertNull(pre.deltaDeg)
    }

    @Test
    fun `hud lines carry the checkpoint reference for the church`() {
        val lines = HudFormatter.lines(
            source = "fused", accuracyM = 4.4, lat = 14.664278, lng = 121.071173,
            distanceM = 612.4, bearingDeg = 180.04, siteId = "church",
            alignment = Alignment(10.0, 20.0, -10.0), perf = null,
        )
        assertEquals("[fused] ±4m", lines[0])
        assertEquals("14.6643 / 121.0712", lines[1])
        assertEquals("dist: 612m  brg: 180.0°", lines[2])
        assertEquals("site: church  ≈ 600m-N  Δ+12m", lines[3])
        assertEquals("stage: fireworks_only", lines[4])
        assertEquals("cam: 10.0°  AR: 20.0°  Δ: -10.0°", lines[5])
        assertEquals(6, lines.size)
    }

    @Test
    fun `hud adds the latency lines when perf is known`() {
        val lines = HudFormatter.lines(
            "sim", null, 0.0, 0.0, 50.0, 0.0, "origin", null,
            PerfSnapshot(59.8, 30.0, 61.2, 198.6, true, 48.0, "game+mag"),
        )
        assertEquals("[sim] ±?m", lines[0])
        assertEquals("site: origin  ≈ —", lines[3])
        assertEquals("fps: 59.8  cam: 30.0  sensor: 199Hz", lines[6])
        assertEquals("photon: 61ms  pose: cam-sync lag 48ms", lines[7])
        assertEquals("heading: game+mag", lines[8])
        assertEquals(9, lines.size)
    }

    @Test
    fun `hud shows the thermal state and the rung it chose`() {
        val hot = PerfSnapshot(
            34.5, 15.7, 289.0, 199.0, true, 279.0, "game+mag",
            ThermalSnapshot(ThermalPolicy.STATUS_SEVERE, 0.973, ThermalPolicy.LADDER[2]),
        )
        val lines = HudFormatter.lines("fused", 5.0, 0.0, 0.0, 50.0, 0.0, "origin", null, hot)
        assertEquals("thermal: severe  headroom 0.97  L2 camera 75% fw 6", lines[9])
        val old = hot.copy(thermal = ThermalSnapshot(ThermalPolicy.STATUS_UNKNOWN, Double.NaN, ThermalPolicy.LADDER[0]))
        assertEquals(
            "thermal: unknown  headroom —  L0 full 100% fw 15",
            HudFormatter.lines("fused", 5.0, 0.0, 0.0, 50.0, 0.0, "origin", null, old)[9],
        )
    }

    @Test
    fun `pose gate waits for a ready heading, then fires once`() {
        val g = PoseGate(timeoutMs = 2000)
        assertFalse(g.poll(0, headingReady = true)) // nothing requested
        g.request(100)
        assertTrue(g.pending)
        assertFalse(g.poll(200, headingReady = false))
        assertTrue(g.poll(300, headingReady = true))
        assertFalse(g.pending)
        assertFalse(g.poll(400, headingReady = true))
    }

    @Test
    fun `pose gate gives up waiting after its timeout`() {
        val g = PoseGate(timeoutMs = 2000)
        g.request(1000)
        g.request(2500) // a second request keeps the first deadline
        assertFalse(g.poll(2999, headingReady = false))
        assertTrue(g.poll(3000, headingReady = false))
    }

    @Test
    fun `hud adds the tracking line when tracking is known`() {
        val lines = HudFormatter.lines(
            "fused", 30.0, 0.0, 0.0, 12.0, 0.0, "church", null, null,
            TrackSnapshot(sigmaM = 4.4, steps = 37, rejectedFixes = 2, fusedDistanceM = 9.6),
        )
        assertEquals("track: fused 10m ±4m  by steps  steps 37  gps-rej 2", lines[6])
        val outdoors = HudFormatter.lines(
            "fused", 5.0, 0.0, 0.0, 120.0, 0.0, "church", null, null,
            TrackSnapshot(sigmaM = 3.0, steps = 12, rejectedFixes = 0, fusedDistanceM = 118.0, speedMps = 1.36),
        )
        assertEquals("track: fused 118m ±3m  by gps 1.4m/s  steps 12  gps-rej 0", outdoors[6])
    }
}
