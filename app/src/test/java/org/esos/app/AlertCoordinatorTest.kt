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

    @Test fun triggerNowSendsDuringCountdownOnlyOnce() {
        assertTrue(coordinator.start(AlertReason.FALL))
        time += 5_000L
        coordinator.triggerNow()
        assertEquals(AlertState.SENT, coordinator.state)
        assertEquals(listOf("SOS: ik heb mogelijk hulp nodig. Reden: mogelijke val gedetecteerd."), messages)
        coordinator.triggerNow()
        assertEquals(1, messages.size)
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

    @Test fun sendsWithoutGpsAtDeadlineAndLeavesLateFixToLocationService() {
        assertTrue(coordinator.start(AlertReason.SOS))
        assertFalse(coordinator.start(AlertReason.SOS))
        time += 60_000
        coordinator.tick()
        assertEquals(1, messages.size)
        assertFalse(messages[0].contains("Locatie"))
        assertFalse(coordinator.locationIncluded)
        callback!!(GpsFix(51.0, 4.0, 20f, time))
        assertEquals(1, messages.size)
    }

    @Test fun locationSupplementShowsTimeAndMapLink() {
        val fix = GpsFix(51.0, 4.123456, 12.7f, java.time.Instant.parse("2026-11-21T13:32:10Z").toEpochMilli())
        assertEquals("Aanvulling op alarm: locatie om 14:32: https://www.openstreetmap.org/?mlat=51.00000&mlon=4.12346&zoom=18",
            locationSupplement(fix, java.time.ZoneId.of("Europe/Amsterdam")))
    }

    @Test fun alertMessageNamesTheTrigger() {
        assertEquals("SOS: ik heb mogelijk hulp nodig. Reden: SOS-knop ingedrukt.", alertMessage(AlertReason.SOS))
        assertEquals("SOS: ik heb mogelijk hulp nodig. Reden: telefoon 24 uur niet ontgrendeld.",
            alertMessage(AlertReason.INACTIVITY, 24))
        assertTrue(alertMessage(AlertReason.SPEECH_DETECTED).endsWith("Reden: hulproep herkend."))
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
        assertEquals(1, messages.size)
        assertFalse(messages.single().contains("Locatie"))
        assertFalse(coordinator.locationIncluded)
    }

    @Test fun inaccurateFixIsIgnoredButFreshAccurateFixIsIncluded() {
        coordinator.start(AlertReason.SOS, 10_000L)
        callback!!(GpsFix(51.0, 4.0, 200f, time))
        callback!!(GpsFix(52.0, 5.0, 20f, time))
        time += 10_000
        coordinator.tick()
        assertEquals(1, messages.size)
        assertTrue(messages.single().contains(". Locatie om "))
        assertTrue(messages.single().endsWith("openstreetmap.org/?mlat=52.00000&mlon=5.00000&zoom=18"))
        assertTrue(coordinator.locationIncluded)
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
        assertTrue(messages.single().contains("dagelijkse bevestiging niet ontvangen"))
    }
}