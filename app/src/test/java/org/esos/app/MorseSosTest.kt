package org.esos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorseSosTest {
    @Test fun sosHasThreeShortThreeLongThreeShortSignals() {
        val on = MorseSos.segments(100).filter { it.on }.map { it.millis }
        assertEquals(listOf(100L, 100, 100, 300, 300, 300, 100, 100, 100), on)
    }

    @Test fun letterAndWordGapsFollowMorseTiming() {
        val segments = MorseSos.segments(100)
        assertEquals(300L, segments[5].millis)
        assertTrue(!segments[5].on)
        assertEquals(700L, segments.last().millis)
        assertTrue(!segments.last().on)
    }

    @Test fun vibrationTimingsStartOffAndAlternate() {
        val timings = MorseSos.vibrationTimings(100)
        assertEquals(0L, timings[0])
        assertEquals(100L, timings[1])
        assertEquals(segmentsTotal(100), timings.sum())
        assertEquals(1, timings.size % 2)
    }

    private fun segmentsTotal(unit: Long) = MorseSos.segments(unit).sumOf { it.millis }
}
