package org.esos.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SosCallWatchdogTest {
    @Test fun unansweredCallCanBeEndedAtTimeout() {
        val watchdog = SosCallWatchdog()
        watchdog.onStateChanged(OutgoingCallState.CONNECTING)
        watchdog.onStateChanged(OutgoingCallState.DIALING)

        assertTrue(watchdog.shouldHangUpAtTimeout())
    }

    @Test fun answeredCallIsNeverEndedByRingTimeout() {
        val watchdog = SosCallWatchdog()
        watchdog.onStateChanged(OutgoingCallState.DIALING)
        watchdog.onStateChanged(OutgoingCallState.ACTIVE)

        assertFalse(watchdog.shouldHangUpAtTimeout())
        watchdog.onStateChanged(OutgoingCallState.DISCONNECTED)
        assertFalse(watchdog.shouldHangUpAtTimeout())
    }

    @Test fun alreadyDisconnectedCallNeedsNoHangup() {
        val watchdog = SosCallWatchdog()
        watchdog.onStateChanged(OutgoingCallState.DISCONNECTED)

        assertFalse(watchdog.shouldHangUpAtTimeout())
    }
}