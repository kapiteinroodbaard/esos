package org.esos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CheckInTimesTest {
    @Test fun nextCheckInTracksDaylightSavingTime() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val beforeDst = LocalDateTime.of(2026, 3, 28, 11, 0).atZone(zone).toInstant().toEpochMilli()
        val next = CheckInTimes.next(beforeDst, 10, 0, zone)
        assertEquals(LocalDateTime.of(2026, 3, 29, 10, 0),
            java.time.Instant.ofEpochMilli(next).atZone(zone).toLocalDateTime())
        assertTrue(next - beforeDst < 24 * 60 * 60 * 1000L)
    }
}