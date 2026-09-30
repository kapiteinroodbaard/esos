package org.esos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class InactivityTimesTest {
    @Test fun deadlineUsesConfiguredDuration() {
        assertEquals(86_400_000L, InactivityTimes.deadline(0L, 24))
    }

    @Test fun durationIsLimitedToOneThroughSeventyTwoHours() {
        assertEquals(3_600_000L, InactivityTimes.deadline(0L, 0))
        assertEquals(259_200_000L, InactivityTimes.deadline(0L, 100))
    }
}