package dev.alfieprojects.mirroredlotus.core.story

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The four pylon numbers, computed exactly as `createARGroup` does in the web
 * build (including its calendar semantics, which were argued out in
 * docs/bugs-and-polish.md DL-001):
 *
 * - **days / hours** are floor(elapsed ms / unit) between two instants, so they
 *   tick at the anchor's time of day (10:00 for the wedding).
 * - **years** counts completed anniversaries by calendar date only - a partial
 *   year is not a year, and the anchor's time of day is ignored.
 * - **months** counts calendar months on the day-of-month boundary, derived
 *   independently of the already-decremented years (the old bug under-counted
 *   by 12 whenever the current month preceded the anchor month).
 */
data class AnniversaryStats(val years: Int, val months: Int, val hours: Long, val days: Long) {

    /** Pylon captions in the web build's order: compass 0, 90, 180, 270 degrees. */
    fun pylonTexts(): List<String> = listOf(
        "$years Years",
        "$months Months",
        "$hours Hours",
        "$days Days",
    )

    companion object {
        fun compute(anchor: LocalDateTime, now: LocalDateTime, zone: ZoneId): AnniversaryStats {
            val elapsedMs = Duration.between(anchor.atZone(zone), now.atZone(zone)).toMillis()
            val days = Math.floorDiv(elapsedMs, 86_400_000L)
            val hours = Math.floorDiv(elapsedMs, 3_600_000L)
            return AnniversaryStats(
                years = completedYears(anchor, now),
                months = completedMonths(anchor, now),
                hours = hours,
                days = days,
            )
        }

        fun completedYears(anchor: LocalDateTime, now: LocalDateTime): Int {
            var years = now.year - anchor.year
            if (now.monthValue < anchor.monthValue ||
                (now.monthValue == anchor.monthValue && now.dayOfMonth < anchor.dayOfMonth)
            ) years--
            return years
        }

        fun completedMonths(anchor: LocalDateTime, now: LocalDateTime): Int {
            var months = (now.year - anchor.year) * 12 + (now.monthValue - anchor.monthValue)
            if (now.dayOfMonth < anchor.dayOfMonth) months--
            return months
        }

        /** The personal message on the inner cylinder. */
        fun messageLine(anchor: LocalDateTime, now: LocalDateTime): String =
            "${completedYears(anchor, now)} years. Still you. Always you."
    }
}
