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

    @Test fun testModeMarksMessagesWithoutChangingAlarmMessages() {
        val message = "SOS: ik heb mogelijk hulp nodig."
        assertEquals(message, AlertMessageFormatter.format(false, message))
        assertEquals("e-SOS SOS-TEST (geen echte noodsituatie): $message",
            AlertMessageFormatter.format(true, message))
    }

    @Test fun cancellationMessageIdentifiesTestMode() {
        assertEquals("SOS geannuleerd.", AlertMessageFormatter.cancellation(false))
        assertEquals("e-SOS SOS-TEST (geen echte noodsituatie): SOS geannuleerd.",
            AlertMessageFormatter.cancellation(true))
    }

    @Test fun appTriggersUseOneMinuteCountdown() {
        val messages = mutableListOf<String>()
        val sender = object : AlertSender {
            override fun send(incidentId: Long, text: String) { messages.add(text) }
        }
        val location = object : IncidentLocation {
            override fun request(onFix: (GpsFix) -> Unit) = object : LocationRequest {
                override fun cancel() {}
            }
        }
        listOf(AlertReason.SOS, AlertReason.SPEECH_DETECTED, AlertReason.MISSED_CHECK_IN,
            AlertReason.INACTIVITY, AlertReason.FALL).forEach { reason ->
            val startedAt = time
            val alarm = AlertCoordinator(sender, location, { time })
            assertTrue(alarm.start(reason))
            assertEquals(startedAt + 60_000L, alarm.deadline)
            time = startedAt + 10_000L
            alarm.tick()
            assertEquals(AlertState.COUNTDOWN, alarm.state)
            assertTrue(messages.isEmpty())
            time = startedAt + 59_999L
            alarm.tick()
            assertEquals(AlertState.COUNTDOWN, alarm.state)
            assertTrue(messages.isEmpty())
            time = startedAt + 60_000L
            alarm.tick()
            assertEquals(AlertState.SENT, alarm.state)
            assertEquals(1, messages.size)
            messages.clear()
        }
    }

    @Test fun manualSosCanStartWithoutCountdown() {
        val manualAlarm = AlertCoordinator(object : AlertSender {
            override fun send(incidentId: Long, text: String) { messages.add(text) }
        }, location, { time })
        assertTrue(manualAlarm.start(AlertReason.SOS, 0L))
        assertEquals(time, manualAlarm.deadline)
        manualAlarm.tick()
        assertEquals(AlertState.SENT, manualAlarm.state)
        assertEquals(1, messages.size)
    }

    @Test fun sendsWithoutGpsAtDeadlineAndOnlyOneTimelySupplement() {
        assertTrue(coordinator.start(AlertReason.SOS))
        assertFalse(coordinator.start(AlertReason.SOS))
        time += 60_000
        coordinator.tick()
        assertEquals(1, messages.size)
        assertFalse(messages[0].contains("GPS:"))
        callback!!(GpsFix(51.0, 4.0, 20f, time))
        callback!!(GpsFix(52.0, 5.0, 20f, time))
        assertEquals(2, messages.size)
        assertTrue(messages[1].contains("geo:51.0,4.0"))
        assertFalse(messages[1].contains("maps.google.com"))
    }

    @Test fun cancelledAlarmNeverSends() {
        coordinator.start(AlertReason.MISSED_CHECK_IN)
        assertTrue(coordinator.cancel())
        time += 10_000
        callback!!(GpsFix(51.0, 4.0, 20f, time))
        coordinator.tick()
        assertTrue(messages.isEmpty())
        assertEquals(AlertState.CANCELLED, coordinator.state)
    }

    @Test fun staleFixCannotDelayOrSupplement() {
        coordinator.start(AlertReason.SOS, 10_000L)
        callback!!(GpsFix(51.0, 4.0, 20f, 1))
        time += 10_000
        coordinator.tick()
        time += 20_001
        callback!!(GpsFix(51.0, 4.0, 20f, time))
        assertEquals(1, messages.size)
    }

    @Test fun inaccurateFixIsIgnoredButFreshAccurateFixIsIncluded() {
        coordinator.start(AlertReason.SOS, 10_000L)
        callback!!(GpsFix(51.0, 4.0, 200f, time))
        callback!!(GpsFix(52.0, 5.0, 20f, time))
        time += 10_000
        coordinator.tick()
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("52.0,5.0"))
        assertTrue(messages.single().contains("geo:52.0,5.0"))
        assertFalse(messages.single().contains("maps.google.com"))
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
        time += 60_000
        fallback.tick()
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains("geen dagelijkse bevestiging"))
    }
}