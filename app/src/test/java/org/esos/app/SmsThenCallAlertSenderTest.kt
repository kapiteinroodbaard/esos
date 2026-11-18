package org.esos.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsThenCallAlertSenderTest {
    @Test fun announcesSuccessfulSmsBeforePreparingCallOnlyOnce() {
        val events = mutableListOf<String>()
        val sender = SmsThenCallAlertSender(
            smsEnabled = { true }, callEnabled = { true },
            sendSms = { _, _ -> events.add("sms-all-contacts") },
            startCall = { events.add("prepare-call") },
            onAlertStarted = { events.add("sos-started") },
            onSmsSent = { events.add("sms-announcement") }
        )

        sender.send(7L, "SOS")
        sender.send(7L, "GPS")

        assertEquals(listOf("sos-started", "sms-all-contacts", "sms-announcement",
            "prepare-call", "sms-all-contacts"), events)
    }

    @Test fun doesNotAnnounceFailedOrDisabledSms() {
        val events = mutableListOf<String>()
        listOf(true, false).forEach { enabled ->
            val sender = SmsThenCallAlertSender(
                smsEnabled = { enabled }, callEnabled = { true },
                sendSms = { _, _ -> error("SMS failed") },
                startCall = { events.add("call") },
                onSmsSent = { events.add("sms-announcement") }
            )
            runCatching { sender.send(7L, "SOS") }
        }

        assertEquals(listOf("call", "call"), events)
    }

    @Test fun sendsSmsBeforeCallingAndStartsOnlyOneCall() {
        val events = mutableListOf<String>()
        val sender = SmsThenCallAlertSender(
            smsEnabled = { true },
            callEnabled = { true },
            sendSms = { _, text -> events.add("sms:$text") },
            startCall = { incidentId -> events.add("call:$incidentId") }
        )

        sender.send(7L, "SOS")
        sender.send(7L, "GPS-aanvulling")

        assertEquals(listOf("sms:SOS", "call:7", "sms:GPS-aanvulling"), events)
    }

    @Test fun callsOnceWithoutSms() {
        val events = mutableListOf<String>()
        val smsDisabledSender = SmsThenCallAlertSender(
            smsEnabled = { false }, callEnabled = { true },
            sendSms = { _, _ -> events.add("sms") }, startCall = { events.add("call") }
        )

        smsDisabledSender.send(8L, "SOS")
        smsDisabledSender.send(8L, "GPS-aanvulling")

        assertEquals(listOf("call"), events)
    }

    @Test fun callsEvenWhenSmsFailsAndReportsTheFailure() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("SMS failed")
        val sender = SmsThenCallAlertSender(
            smsEnabled = { true }, callEnabled = { true },
            sendSms = { _, _ -> events.add("sms"); throw failure },
            startCall = { events.add("call") }
        )

        val result = runCatching { sender.send(10L, "SOS") }

        assertEquals(listOf("sms", "call"), events)
        assertEquals(failure, result.exceptionOrNull())
    }

    @Test fun sendsOnlySmsWhenCallingIsDisabled() {
        val events = mutableListOf<String>()
        val sender = SmsThenCallAlertSender(
            smsEnabled = { true }, callEnabled = { false },
            sendSms = { _, _ -> events.add("sms") }, startCall = { events.add("call") }
        )

        sender.send(11L, "SOS")

        assertEquals(listOf("sms"), events)
    }

    @Test fun smsIsSentBeforeCallFailureIsReported() {
        val events = mutableListOf<String>()
        val sender = SmsThenCallAlertSender(
            smsEnabled = { true }, callEnabled = { true },
            sendSms = { _, _ -> events.add("sms") },
            startCall = { events.add("call"); throw IllegalStateException("call failed") }
        )

        runCatching { sender.send(10L, "SOS") }

        assertEquals(listOf("sms", "call"), events)
    }
}