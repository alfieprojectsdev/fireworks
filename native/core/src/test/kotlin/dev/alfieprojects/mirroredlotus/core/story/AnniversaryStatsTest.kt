package dev.alfieprojects.mirroredlotus.core.story

import dev.alfieprojects.mirroredlotus.core.site.Sites
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AnniversaryStatsTest {

    private val manila = ZoneId.of("Asia/Manila")
    private val wedding = Sites.CHURCH.anchor

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) = LocalDateTime.of(y, m, d, h, min)

    @Test
    fun `on the anniversary itself the year is complete`() {
        val s = AnniversaryStats.compute(wedding, at(2026, 5, 15), manila)
        assertEquals(18, s.years)
        assertEquals(216, s.months)
    }

    @Test
    fun `the day before the anniversary is still the previous year`() {
        val s = AnniversaryStats.compute(wedding, at(2026, 5, 14), manila)
        assertEquals(17, s.years)
        assertEquals(215, s.months)
    }

    @Test
    fun `years ignore the anchor's time of day, as the web build did`() {
        // 09:59 on the anniversary, before the 10:00 ceremony: still counts.
        assertEquals(18, AnniversaryStats.compute(wedding, at(2026, 5, 15, 9, 59), manila).years)
    }

    @Test
    fun `months do not double-count the year decrement (DL-001)`() {
        // March precedes May: the old formula lost 12 months here.
        val s = AnniversaryStats.compute(wedding, at(2026, 3, 10), manila)
        assertEquals(17, s.years)
        assertEquals(213, s.months) // 17 years 8 months, since 10 < 15
        assertEquals(214, AnniversaryStats.compute(wedding, at(2026, 3, 15), manila).months)
    }

    @Test
    fun `hours and days tick at the anchor's time of day`() {
        val justBefore = AnniversaryStats.compute(wedding, LocalDateTime.of(2008, 5, 16, 9, 59), manila)
        assertEquals(0L, justBefore.days)
        assertEquals(23L, justBefore.hours)
        val onTheDot = AnniversaryStats.compute(wedding, LocalDateTime.of(2008, 5, 16, 10, 0), manila)
        assertEquals(1L, onTheDot.days)
        assertEquals(24L, onTheDot.hours)
    }

    @Test
    fun `monthsary anchor counts from October 2005`() {
        val s = AnniversaryStats.compute(Sites.ORIGIN.anchor, at(2026, 9, 25), manila)
        assertEquals(20, s.years)
        assertEquals(20 * 12 + 11, s.months) // Oct 2005 -> Sep 2026 = 251, 25 >= 15
        assertEquals(
            listOf(
                AnniversaryStats.PylonStat("20", "YEARS"),
                AnniversaryStats.PylonStat("251", "MONTHS"),
                AnniversaryStats.PylonStat("${s.hours}", "HOURS"),
                AnniversaryStats.PylonStat("${s.days}", "DAYS"),
            ),
            s.pylonStats(),
        )
    }

    @Test
    fun `personal message uses completed years`() {
        assertEquals("18 years. Still you. Always you.", AnniversaryStats.messageLine(wedding, at(2026, 9, 25)))
    }
}
