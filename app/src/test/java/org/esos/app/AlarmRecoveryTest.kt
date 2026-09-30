package org.esos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmRecoveryTest {
    @Test fun restoredCountdownCanBeSentImmediately() {
        val messages = mutableListOf<String>()
        val coordinator = AlertCoordinator(object : AlertSender {
            override fun send(incidentId: Long, text: String) { messages.add(text) }
        }, object : IncidentLocation {
            override fun request(onFix: (GpsFix) -> Unit): LocationRequest = object : LocationRequest {
                override fun cancel() {}
            }
        }, { 100L })
        coordinator.start(AlertReason.MISSED_CHECK_IN, 0L)
        coordinator.tick()
        assertEquals(1, messages.size)
        assertEquals(AlertState.SENT, coordinator.state)
    }
}