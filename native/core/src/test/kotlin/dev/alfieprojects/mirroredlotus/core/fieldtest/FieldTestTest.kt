package dev.alfieprojects.mirroredlotus.core.fieldtest

import dev.alfieprojects.mirroredlotus.core.fieldtest.FieldTestRunner.Event
import dev.alfieprojects.mirroredlotus.core.perf.ThermalPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class FieldTestTest {

    // ---- poses -------------------------------------------------------------

    /** Lying face-up, top edge north: device axes = ENU. */
    private val faceUp = doubleArrayOf(1.0, 0.0, 0.0, 0.0)

    /** Portrait, upright, camera level toward [headingDeg] (clockwise from north). */
    private fun upright(headingDeg: Double = 0.0): DoubleArray {
        // Yaw about up (counter-clockwise is westward, hence the minus), then +90° about device X.
        val yaw = Math.toRadians(-headingDeg) / 2
        val pitch = PI / 4
        val zw = cos(yaw); val zz = sin(yaw)
        val xw = cos(pitch); val xx = sin(pitch)
        // (zw + zz k)(xw + xx i) = zw xw + zw xx i + zz xw k + zz xx (k i = j)
        return doubleArrayOf(zw * xw, zw * xx, zz * xx, zz * xw)
    }

    @Test
    fun `pose tests tell face-up from upright, and read the heading`() {
        assertTrue(FieldPose.faceUp(faceUp))
        assertFalse(FieldPose.upright(faceUp))
        assertEquals(0.0, FieldPose.headingDeg(faceUp)!!, 1e-6) // top edge north
        for (h in listOf(0.0, 90.0, 225.0)) {
            val q = upright(h)
            assertTrue(FieldPose.upright(q))
            assertFalse(FieldPose.faceUp(q))
            assertEquals(h, FieldPose.headingDeg(q)!!, 1e-6)
        }
        // Tilted 20° off face-up is no longer face-up (limit 15°).
        val tilted = doubleArrayOf(cos(Math.toRadians(10.0)), sin(Math.toRadians(10.0)), 0.0, 0.0)
        assertFalse(FieldPose.faceUp(tilted))
    }

    // ---- driving a runner ------------------------------------------------------

    private var t = 0L
    private var steps = 0L

    private fun sample(
        q: DoubleArray?,
        delta: Double? = null,
        fps: Double = 60.0,
        camFps: Double = 30.0,
        photon: Double = 60.0,
        thermal: Int = ThermalPolicy.STATUS_NONE,
        compass: Double? = null,
    ) = FieldSample(
        t, q?.copyOf(), steps, deltaDeg = delta, renderFps = fps, cameraFps = camFps, photonMs = photon,
        thermalStatus = thermal, compassHeadingDeg = compass,
    )

    /** Feeds samples every [stepMs] until an event arrives or [maxMs] passes. */
    private fun feed(
        r: FieldTestRunner,
        maxMs: Long,
        stepMs: Long = 100,
        make: () -> FieldSample,
    ): List<Event> {
        val end = t + maxMs
        while (t < end) {
            t += stepMs
            val events = r.onSample(make())
            if (events.isNotEmpty()) return events
        }
        return emptyList()
    }

    private fun started(events: List<Event>): Event.PhaseStarted = events.single() as Event.PhaseStarted

    @Test
    fun `T1 places on a held face-up pose, and scores the held upright delta`() {
        val r = FieldTestRunner(FieldTests.faceUpPlacement())
        assertEquals("faceup-1", started(r.start(sample(null))).phase.id)
        val deltas = listOf(5.0, -8.0, 21.0)
        for ((i, d) in deltas.withIndex()) {
            val lift = started(feed(r, 5_000) { sample(faceUp) })
            assertEquals("lift-${i + 1}", lift.phase.id)
            assertEquals(listOf(PhaseAction.PlaceAhead), lift.phase.actions)
            val next = feed(r, 5_000) { sample(upright(30.0 * i), delta = d) }
            assertTrue(next.isNotEmpty())
        }
        assertTrue(r.finished)
        assertEquals(listOf(true, true, false), r.results.map { it.pass })
        assertEquals("-8.0°", r.results[1].value)
    }

    @Test
    fun `held means held for the whole hold time`() {
        val r = FieldTestRunner(FieldTests.faceUpPlacement())
        r.start(sample(null))
        // Still for a second, a nudge, then still again: the hold restarts.
        repeat(10) { t += 100; r.onSample(sample(faceUp)) }
        t += 100
        assertTrue(r.onSample(sample(doubleArrayOf(cos(0.05), sin(0.05), 0.0, 0.0))).isEmpty())
        var waited = 0L
        var events: List<Event> = emptyList()
        while (events.isEmpty()) {
            t += 100; waited += 100
            events = r.onSample(sample(faceUp))
        }
        assertTrue("restarted the 1.5 s hold, waited $waited ms", waited >= 1_500)
    }

    @Test
    fun `a phone that never settles upright times out and fails the round`() {
        val r = FieldTestRunner(FieldTests.faceUpPlacement())
        r.start(sample(null))
        feed(r, 5_000) { sample(faceUp) }
        // Panning at 0.5 rad/s: upright, but never still.
        var heading = 0.0
        val events = feed(r, 31_000) {
            heading += Math.toDegrees(0.05)
            sample(upright(heading), delta = 2.0)
        }
        assertEquals("faceup-2", started(events).phase.id)
        repeat(2) {
            feed(r, 5_000) { sample(faceUp) }
            feed(r, 5_000) { sample(upright(), delta = 1.0) }
        }
        assertTrue(r.finished)
        assertEquals(CheckResult("round 1 Δ", "never held upright", "|Δ| < 15°", false), r.results[0])
        assertEquals(listOf(false, true, true), r.results.map { it.pass })
    }

    @Test
    fun `T2 counts each part's steps, warns before it ends, and flags phantom walking`() {
        val r = FieldTestRunner(FieldTests.standStill())
        r.start(sample(upright()))
        assertTrue("intro waits for a tap", feed(r, 10_000) { sample(upright()) }.isEmpty())
        assertEquals("steady", started(r.onTap(t)).phase.id)
        val phantom = mapOf("steady" to 0, "pan" to 5, "handle" to 2)
        for (part in listOf("steady", "pan", "handle")) {
            val partStart = t
            var credited = 0
            val warning = feed(r, 60_000) {
                if (credited < phantom.getValue(part) && t % 5_000 == 0L) { steps++; credited++ }
                sample(upright())
            }
            assertEquals(listOf(Event.Warning), warning)
            assertEquals("warned 3 s early", 42_000L, t - partStart)
            val next = feed(r, 5_000) { sample(upright()) }
            assertTrue(next.isNotEmpty())
        }
        assertTrue(r.finished)
        val verdicts = r.results.filter { it.name.endsWith("steps") }.map { it.name to it.pass }
        assertEquals(listOf("steady steps" to true, "pan steps" to false, "handle steps" to true), verdicts)
        assertEquals("no fix", r.results.first { it.name == "steady drift" }.value)
    }

    @Test
    fun `T3 counts the steps between Start and Done`() {
        val r = FieldTestRunner(FieldTests.stepCount())
        r.start(sample(upright()))
        r.onTap(t)
        repeat(21) { t += 500; steps++; r.onSample(sample(upright())) }
        r.onTap(t) // done, held
        repeat(8) { t += 500; steps++; r.onSample(sample(upright())) } // walking back: not counted
        r.onTap(t)
        repeat(15) { t += 500; steps++; r.onSample(sample(upright())) }
        r.onTap(t)
        assertTrue(r.finished)
        assertEquals(listOf("21 counted" to true, "15 counted" to false), r.results.map { it.value to it.pass })
    }

    @Test
    fun `T4 is scored blind against whichever trial was synced`() {
        fun run(syncFirst: Boolean, pick: Int): CheckResult {
            val r = FieldTestRunner(FieldTests.swim(syncFirst))
            r.start(sample(upright()))
            val syncs = mutableListOf<Boolean>()
            while (r.phase?.advance !is Advance.Choice) {
                val events = feed(r, 40_000) { sample(upright()) }
                events.filterIsInstance<Event.PhaseStarted>().forEach { e ->
                    e.phase.actions.filterIsInstance<PhaseAction.SyncCamera>().forEach { syncs += it.on }
                }
            }
            assertEquals(listOf(syncFirst, !syncFirst), syncs)
            val done = r.onChoice(pick, t).single() as Event.Finished
            return done.results.single()
        }
        assertEquals(true, run(syncFirst = true, pick = 0).pass)
        assertEquals(false, run(syncFirst = false, pick = 0).pass)
        assertEquals(true, run(syncFirst = false, pick = 1).pass)
        assertNull(run(syncFirst = true, pick = 2).pass)
        assertEquals("B (synced: A)", run(syncFirst = true, pick = 1).value)
    }

    @Test
    fun `T5 logs the time to each thermal status and the photon share`() {
        val r = FieldTestRunner(FieldTests.thermalSoak())
        r.start(sample(upright()))
        val soak = started(r.onTap(t))
        assertEquals(listOf(PhaseAction.StartShow), soak.phase.actions)
        val soakStart = t
        val events = feed(r, 21 * 60_000L, stepMs = 1_000) {
            val m = (t - soakStart) / 60_000.0
            val status = when {
                m >= 12 -> ThermalPolicy.STATUS_SEVERE
                m >= 5 -> ThermalPolicy.STATUS_MODERATE
                else -> ThermalPolicy.STATUS_LIGHT
            }
            sample(upright(), fps = if (m >= 12) 34.0 else 59.0, photon = if (m >= 12) 290.0 else 60.0, thermal = status)
        }
        assertTrue(events.any { it is Event.Warning } || r.finished)
        if (!r.finished) feed(r, 10_000, stepMs = 1_000) { sample(upright()) }
        val byName = r.results.associateBy { it.name }
        assertEquals("none, headroom —", byName.getValue("start").value)
        assertEquals("5:00 (59 fps, 60 ms)", byName.getValue("to moderate").value)
        assertEquals("12:00 (34 fps, 290 ms)", byName.getValue("to severe").value)
        val share = byName.getValue("photon ≤ 100 ms")
        assertEquals("60% of the time", share.value)
        assertEquals(false, share.pass)
        assertEquals("34 fps, 290 ms", byName.getValue("worst").value)
    }

    @Test
    fun `T6 measures heading spread across north without wrapping to 358 degrees`() {
        val r = FieldTestRunner(FieldTests.compass())
        r.start(sample(upright(359.0)))
        feed(r, 5_000) { sample(upright(359.0), compass = 358.0) }
        var i = 0
        while (!r.finished) {
            feed(r, 61_000) { // returns at the 3 s warning, then at the end
                i++
                // Drifting between 359° and 1°.
                val h = 359.0 + 2.0 * (i % 20) / 20.0
                sample(upright(h % 360.0), compass = if (i % 2 == 0) 357.0 else 3.0)
            }
        }
        val spread = r.results.first { it.name == "heading spread" }.value.removeSuffix("°").toDouble()
        assertEquals(1.9, spread, 0.05)
        assertEquals("6.0°", r.results.first { it.name == "raw compass spread" }.value)
    }

    @Test
    fun `the card says what to do, how long is left, and what to press`() {
        val r = FieldTestRunner(FieldTests.standStill())
        r.start(sample(upright()))
        var card = r.card(t)
        assertEquals("T2  Stand still", card.title)
        assertEquals("step 1 of 4", card.progress)
        assertEquals(listOf("Start"), card.buttons)
        r.onTap(t)
        repeat(123) { t += 100; r.onSample(sample(upright())) }
        card = r.card(t)
        assertEquals("0:33", card.timer) // 45 s - 12.3 s, rounded up
        assertEquals("steps 0  drift — (no fix)", card.metric)
        assertTrue(card.buttons.isEmpty())

        val t1 = FieldTestRunner(FieldTests.faceUpPlacement())
        t1.start(sample(null))
        assertEquals("waiting: face-up and still", t1.card(t).timer)
        repeat(6) { t += 100; t1.onSample(sample(faceUp)) }
        assertEquals("holding 0.4 / 1.5 s", t1.card(t).timer) // the first sample has no rate yet

        r.abort(t)
        assertEquals(listOf("Close"), r.card(t).buttons)
        assertEquals("done", r.card(t).progress)
        assertEquals(listOf("FAIL  run: aborted during steady"), r.resultLines())
    }

    @Test
    fun `every script evaluates cleanly, even on an empty run`() {
        for (seed in listOf(0L, 1L)) {
            for (script in FieldTests.all(seed)) {
                val ids = script.phases.map { it.id }
                assertEquals("${script.id} phase ids unique", ids.size, ids.toSet().size)
                val stats = script.phases.mapIndexed { i, p -> PhaseStats(p.id, i, 0, 0, null) }
                val results = script.evaluate(FieldRun(stats, emptyMap()))
                assertTrue("${script.id} reports something", results.isNotEmpty())
            }
        }
        assertEquals("t2", FieldTests.byId("T2", 0)?.id)
        assertNull(FieldTests.byId("t9", 0))
    }

    @Test
    fun `every log line is one JSON object with a type and a run-relative time`() {
        val log = FieldTestLog(t0Ms = 10_000)
        val script = FieldTests.swim(syncFirst = true)
        val track = FieldTestLog.Track(14.65, 121.05, 3.2, 12, 1, null)
        val lines = listOf(
            log.header(script, "2026-09-25T02:00:00Z", "0.1.0 (1)", "field", "samsung SM-A075F", mapOf("seed" to "1")),
            log.phase(10_100, 1, script.phases[1]),
            log.ori(10_200, doubleArrayOf(0.70710678, 0.70710678, 0.0, 0.0), 12.3456, Double.NaN, null, 1.5),
            log.step(10_300, 0.6, -0.8, 7, track),
            log.fix(10_400, 14.6517261, 121.0547280, 12.0, "fused", 1.2, 45.0, null, "FUSED", track),
            log.perf(10_500, 59.8, 29.4, 62.0, 40.0, 199.0, 3, Double.NaN, 2),
            log.input(10_600, "choice", "A \"quoted\""),
            log.note(10_700, "pause"),
            log.result(10_800, listOf(CheckResult("stuck better", "A (synced: A)", "the synced trial", true)), aborted = false),
        )
        val types = mutableListOf<String>()
        for (line in lines) {
            assertTrue(line.endsWith("\n"))
            assertEquals("one line: $line", 1, line.count { it == '\n' })
            val o = JSONObject(line)
            types += o.getString("type")
            assertTrue(o.has("t"))
        }
        assertEquals(listOf("run", "phase", "ori", "step", "fix", "perf", "input", "note", "result"), types)
        assertEquals(0, JSONObject(lines[0]).getInt("t"))
        assertEquals(100, JSONObject(lines[1]).getInt("t"))
        assertEquals("placeAhead", JSONObject(lines[1]).getJSONArray("actions").getString(0))
        assertEquals("syncCamera:on", JSONObject(lines[1]).getJSONArray("actions").getString(1))
        assertTrue(JSONObject(lines[2]).isNull("rate"))
        assertEquals(12.35, JSONObject(lines[2]).getDouble("heading"), 1e-9)
        assertEquals(3.2, JSONObject(lines[3]).getJSONObject("track").getDouble("sigma"), 1e-9)
        assertTrue(JSONObject(lines[5]).isNull("headroom"))
        assertEquals("A \"quoted\"", JSONObject(lines[6]).getString("value"))
        assertEquals("PASS", JSONObject(lines[8]).getJSONArray("checks").getJSONObject(0).getString("verdict"))
        assertEquals(sqrt(0.5), JSONObject(lines[2]).getJSONArray("q").getDouble(0), 1e-5)
    }
}
