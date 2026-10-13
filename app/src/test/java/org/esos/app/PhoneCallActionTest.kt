package org.esos.app

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCallActionTest {
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