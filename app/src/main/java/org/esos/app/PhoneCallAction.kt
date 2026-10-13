package org.esos.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import android.os.SystemClock
import android.telephony.PhoneNumberUtils
import androidx.core.content.ContextCompat

object PhoneCallAction {
    const val EXTRA_SOS_ALERT = "org.esos.app.extra.SOS_ALERT"
    const val EXTRA_INCIDENT_ID = "org.esos.app.extra.INCIDENT_ID"
    const val EXTRA_STARTED_AT = "org.esos.app.extra.STARTED_AT"

    fun canStart(context: Context, settings: AppSettings): Boolean {
        val contact = settings.contacts.firstOrNull() ?: return false
        return SmsAlertSender.validNumber(contact.number) &&
            context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    }

    fun startEmergencyCall(context: Context, contact: FamilyContact, incidentId: Long) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED) { "CALL_PHONE permission is required to place calls" }
        val outgoingExtras = Bundle().apply {
            putBoolean(EXTRA_SOS_ALERT, true)
            putLong(EXTRA_INCIDENT_ID, incidentId)
            putLong(EXTRA_STARTED_AT, SystemClock.elapsedRealtime())
        }
        val callExtras = Bundle().apply {
            val deviceTypes = context.getSystemService(AudioManager::class.java)
                .availableCommunicationDevices.map { it.type }
            putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, shouldStartOnSpeaker(deviceTypes))
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoingExtras)
        }
        context.getSystemService(TelecomManager::class.java).placeCall(
            Uri.fromParts("tel", contact.number, null), callExtras
        )
    }

    fun startDialerCall(context: Context, number: String) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED) { "CALL_PHONE permission is required to place calls" }
        val networkNumber = PhoneNumberUtils.extractNetworkPortion(number)
            ?: throw IllegalArgumentException("Phone number is empty")
        context.getSystemService(TelecomManager::class.java).placeCall(
            Uri.fromParts("tel", networkNumber, null), Bundle()
        )
    }

    fun validDialNumber(number: String): Boolean {
        val networkNumber = PhoneNumberUtils.extractNetworkPortion(number).orEmpty()
        return networkNumber.length in 3..40 && networkNumber.any(Char::isDigit)
    }

    internal fun shouldStartOnSpeaker(deviceTypes: List<Int>): Boolean = deviceTypes.none {
        it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it == AudioDeviceInfo.TYPE_BLE_SPEAKER || it == AudioDeviceInfo.TYPE_HEARING_AID
    }

    fun isEmergencyCall(call: Call): Boolean = call.details?.intentExtras
        ?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)
        ?.getBoolean(EXTRA_SOS_ALERT, false) == true
}