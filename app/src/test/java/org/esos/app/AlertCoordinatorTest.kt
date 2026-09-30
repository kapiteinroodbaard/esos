package org.esos.app

import org.junit.Assert.*
import org.junit.Test

class AlertCoordinatorTest {
    private var time = 100_000L
    private val messages = mutableListOf<String>()
    private var callback: ((GpsFix) -> Unit)? = null
    private val location = object : IncidentLocation {
        override fun request(onFix: (GpsFix) -> Unit): LocationRequest {
            callback = onFix
            return object : LocationRequest { override fun cancel() {} }
        }
    }
    private val coordinator = AlertCoordinator(object : AlertSender {
        override fun send(incidentId: Long, text: String) { messages.add(text) }
    }, location, { time })

    @Test fun sendsWithoutGpsAtDeadlineAndOnlyOneTimelySupplement() {
        assertTrue(coordinator.start(AlertReason.SOS))
        assertFalse(coordinator.start(AlertReason.SOS))
        time += 10_000
        coordinator.tick()
        assertEquals(1, messages.size)
        assertFalse(messages[0].contains("GPS:"))
        callback!!(GpsFix(51.0, 4.0, 20f, time))
        callback!!(GpsFix(52.0, 5.0, 20f, time))
        assertEquals(2, messages.size)
    }

    @Test fun cancelledAlarmNeverSends() {
        coordinator.start(AlertReason.MISSED_CHECK_IN)
        assertTrue(coordinator.cancel())
        time += 10_000
        callback!!(GpsFix(51.0, 4.0, 20f, time))
        coordinator.tick()
        assertTrue(messages.isEmpty())
    }

    @Test fun staleFixCannotDelayOrSupplement() {
        coordinator.start(AlertReason.SOS)
        callback!!(GpsFix(51.0, 4.0, 20f, 1))
        time += 10_000
        coordinator.tick()
        time += 20_001
        callback!!(GpsFix(51.0, 4.0, 20f, time))
        assertEquals(1, messages.size)
    }

    @Test fun inaccurateFixIsIgnoredButFreshAccurateFixIsIncluded() {
        coordinator.start(AlertReason.SOS)
        callback!!(GpsFix(51.0, 4.0, 200f, time))
        callback!!(GpsFix(52.0, 5.0, 20f, time))
        time += 10_000
        coordinator.tick()
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("52.0,5.0"))
        callback!!(GpsFix(53.0, 6.0, 20f, time))
        assertEquals(1, messages.size)
    }

    @Test fun rejectedLocationRequestCannotBlockSms() {
        val fallback = AlertCoordinator(object : AlertSender {
            override fun send(incidentId: Long, text: String) { messages.add(text) }
        }, object : IncidentLocation {
            override fun request(onFix: (GpsFix) -> Unit): LocationRequest {
                throw SecurityException("no permission")
            }
        }, { time })
        assertTrue(fallback.start(AlertReason.MISSED_CHECK_IN))
        time += 10_000
        fallback.tick()
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("geen dagelijkse bevestiging"))
    }
}