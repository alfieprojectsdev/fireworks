package dev.alfieprojects.mirroredlotus.core.record

import dev.alfieprojects.mirroredlotus.core.site.Sites
import dev.alfieprojects.mirroredlotus.core.story.ActEvent
import dev.alfieprojects.mirroredlotus.core.ui.Alignment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecorderTest {

    @Test
    fun `an empty session exports nothing`() {
        assertNull(SessionRecorder(Sites.ALL).exportJson("2026-09-25T00:00:00Z", "test"))
    }

    @Test
    fun `export is valid JSON with the web build's keys`() {
        val r = SessionRecorder(Sites.ALL)
        r.recordGps(1000, 14.65, 121.06, 5.4, "fused", 1234.56, 45.678, Alignment(10.0, 20.0, -10.0), "church")
        r.recordGps(4000, 14.651, 121.061, null, "fused", 1200.0, 44.0, null, "church")
        r.recordOrientation(1000, 0.1, 0.2, 0.3, 0.9)
        r.recordOrientation(2000, 0.5, 0.5, 0.5, 0.5) // inside the 5 s gate: dropped
        r.recordAct(3000, ActEvent.BEACON_ON, 1790.4)
        r.recordPerf(3000, 59.94, 30.0, 55.55, 200.0, 40.0, thermalStatus = 3, headroom = 0.9734, qualityLevel = 2)
        val json = JSONObject(r.exportJson("2026-09-25T00:00:00Z", "0.1.0 (1)")!!)

        for (key in listOf("exportedAt", "sites", "summary", "actEvents", "gpsEvents", "oriSamples")) {
            assertTrue("missing $key", json.has(key))
        }
        assertEquals("android-native", json.getJSONObject("app").getString("platform"))
        assertEquals(2, json.getJSONArray("sites").length())
        assertEquals("2008-05-15T10:00", json.getJSONArray("sites").getJSONObject(0).getString("anchor"))

        val g0 = json.getJSONArray("gpsEvents").getJSONObject(0)
        assertEquals(1235, g0.getInt("distM"))
        assertEquals(45.7, g0.getDouble("bearingDeg"), 1e-9)
        assertEquals(5.0, g0.getDouble("acc"), 1e-9)
        assertEquals(-10.0, g0.getJSONObject("align").getDouble("delta"), 1e-9)
        assertEquals("?", json.getJSONArray("gpsEvents").getJSONObject(1).getString("acc"))

        val summary = json.getJSONObject("summary")
        assertEquals(3, summary.getInt("durationSeconds"))
        assertEquals(2, summary.getInt("gpsCount"))
        assertEquals(5.0, summary.getDouble("avgAccuracyM"), 1e-9)
        assertEquals(10.0, summary.getDouble("maxAlignmentDeltaDeg"), 1e-9)

        val act = json.getJSONArray("actEvents").getJSONObject(0)
        assertEquals("beacon_on", act.getString("name"))
        assertEquals(-10, act.getInt("deltaM"))
        assertEquals(1, json.getJSONArray("oriSamples").length())
        assertEquals(1, json.getJSONArray("perfSamples").length())
        val perf = json.getJSONArray("perfSamples").getJSONObject(0)
        assertEquals(3, perf.getInt("thermal"))
        assertEquals(0.973, perf.getDouble("headroom"), 1e-9)
        assertEquals(2, perf.getInt("quality"))
    }

    @Test
    fun `gps log is capped, oldest first out`() {
        val r = SessionRecorder(Sites.ALL, maxGps = 3)
        for (i in 0 until 5) r.recordGps(i * 1000L, 0.0, 0.0, 1.0, "x", i.toDouble(), 0.0, null, null)
        assertEquals(3, r.gpsCount)
        val events = JSONObject(r.exportJson("t", "v")!!).getJSONArray("gpsEvents")
        assertEquals(2, events.getJSONObject(0).getInt("distM"))
    }

    @Test
    fun `json writer escapes and drops non-finite numbers`() {
        val w = JsonWriter()
        w.beginObject().field("s", "a\"b\\c\n\u0001").field("nan", Double.NaN).field("n", 3.0).field("f", 1.5).endObject()
        val o = JSONObject(w.toString())
        assertEquals("a\"b\\c\n\u0001", o.getString("s"))
        assertTrue(o.isNull("nan"))
        assertEquals(3, o.getInt("n"))
        assertEquals(1.5, o.getDouble("f"), 0.0)
    }
}
