package org.esos.app

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCallActionTest {
    @Test fun overlayUsesOnlyFourThinNonOverlappingEdges() {
        val edges = sosBorderEdges(1080, 2400, 8)
        assertEquals(4, edges.size)
        assertEquals(SosBorderEdge(0, 0, 1080, 8), edges[0])
        assertEquals(SosBorderEdge(0, 2392, 1080, 8), edges[1])
        assertEquals(SosBorderEdge(0, 8, 8, 2384), edges[2])
        assertEquals(SosBorderEdge(1072, 8, 8, 2384), edges[3])
        assertTrue(edges.none { 540 in it.left until it.left + it.width &&
            1200 in it.top until it.top + it.height })
        assertTrue(sosBorderEdges(0, 2400, 8).isEmpty())
    }

    @Test fun timedRoundAdvancesOnceThroughTheEntireList() {
        assertEquals(1, TimedCallRound.nextIndex(0, 3))
        assertEquals(2, TimedCallRound.nextIndex(1, 3))
        assertNull(TimedCallRound.nextIndex(2, 3))
        assertNull(TimedCallRound.nextIndex(0, 1))
        assertNull(TimedCallRound.nextIndex(0, 0))
        assertNull(TimedCallRound.nextIndex(-1, 3))
    }

    @Test fun nextAttemptIsScheduledFifteenMinutesAfterCurrentAttempt() {
        assertEquals(901_234L, TimedCallRound.nextDeadline(1_234L))
        assertEquals(900_000L, TimedCallRound.INTERVAL_MILLIS)
    }

    @Test fun contactCanMoveUpAndDownWithoutChangingItsDetails() {
        val contacts = listOf(FamilyContact("Eerste", "+31612345678"),
            FamilyContact("Tweede", "+31612345679"), FamilyContact("Derde", "+31612345680"))
        assertEquals(listOf(contacts[2], contacts[0], contacts[1]), moveEmergencyContact(contacts, 2, 0))
        assertEquals(listOf(contacts[1], contacts[2], contacts[0]), moveEmergencyContact(contacts, 0, 2))
        assertEquals(contacts, moveEmergencyContact(contacts, 0, -1))
        assertEquals(contacts, moveEmergencyContact(contacts, 2, 3))
    }

    @Test fun requestsSpeakerWhenNoBluetoothCallDeviceIsAvailable() {
        assertTrue(PhoneCallAction.shouldStartOnSpeaker(emptyList()))
        assertTrue(PhoneCallAction.shouldStartOnSpeaker(listOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )))
    }

    @Test fun bluetoothCallDevicesTakePriorityOverSpeaker() {
        listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID).forEach { type ->
            assertFalse(PhoneCallAction.shouldStartOnSpeaker(listOf(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, type
            )))
        }
    }
}