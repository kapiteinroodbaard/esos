package org.esos.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class InactivityTimesTest {
    private val utc = ZoneId.of("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun deadlineUsesConfiguredDuration() {
        val start = at(2025, 1, 1, 8)
        assertEquals(at(2025, 1, 2, 17), InactivityTimes.deadline(start, 24, zoneId = utc))
    }

    @Test fun durationIsLimitedToOneThroughSeventyTwoHours() {
        assertEquals(3_600_000L, InactivityTimes.deadline(0L, 0, 0, 0, utc))
        assertEquals(259_200_000L, InactivityTimes.deadline(0L, 100, 0, 0, utc))
    }

    @Test fun defaultSleepWindowIsExcludedAcrossMidnight() {
        assertEquals(at(2025, 1, 2, 9), InactivityTimes.deadline(at(2025, 1, 1, 22), 2, zoneId = utc))
        assertEquals(at(2025, 1, 1, 9), InactivityTimes.deadline(at(2025, 1, 1, 1), 1, zoneId = utc))
    }

    @Test fun customSleepWindowIsExcluded() {
        assertEquals(at(2025, 1, 2, 7),
            InactivityTimes.deadline(at(2025, 1, 1, 21), 2, 22 * 60, 6 * 60, utc))
    }
}