package org.esos.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

class SmsAlertSender(private val context: Context, private val settings: AppSettings) : AlertSender {
    fun ready(): Boolean {
        if (settings.contacts.isEmpty() || settings.contacts.any { !validNumber(it.number) } || settings.simId < 0 ||
            context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
        ) return false
        val subscriptions = context.getSystemService(SubscriptionManager::class.java)
        return try {
            subscriptions.activeSubscriptionInfoList?.any { it.subscriptionId == settings.simId } == true
        } catch (_: SecurityException) { false }
    }

    override fun send(incidentId: Long, text: String) {
        if (!settings.smsEnabled) return
        if (!ready()) throw IllegalStateException("SMS niet beschikbaar; controleer contact, SIM en machtigingen")
        val manager = context.getSystemService(SmsManager::class.java)
            .createForSubscriptionId(settings.simId)
        val parts = manager.divideMessage(text)
        settings.contacts.forEach { contact ->
            manager.sendMultipartTextMessage(contact.number, null, parts, null, null)
        }
    }

    companion object {
        fun validNumber(number: String): Boolean = number.matches(Regex("\\+[1-9][0-9]{7,14}"))
    }
}