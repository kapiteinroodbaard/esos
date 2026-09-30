package org.esos.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
        if (!settings.smsEnabled) {
            settings.smsStatus = "SMS uitgeschakeld; alarm niet per SMS verstuurd"
            return
        }
        if (!ready()) throw IllegalStateException("SMS niet beschikbaar; controleer contact, SIM en machtigingen")
        val manager = context.getSystemService(SmsManager::class.java)
            .createForSubscriptionId(settings.simId)
        val parts = manager.divideMessage(text)
        settings.contacts.forEachIndexed { contactIndex, contact ->
            val sent = ArrayList<PendingIntent>(parts.size)
            val delivered = ArrayList<PendingIntent>(parts.size)
            for (index in parts.indices) {
                sent.add(resultIntent("SENT", incidentId, contactIndex, index, contact.name))
                delivered.add(resultIntent("DELIVERED", incidentId, contactIndex, index, contact.name))
            }
            manager.sendMultipartTextMessage(contact.number, null, parts, sent, delivered)
        }
        settings.smsStatus = "SMS voor ${settings.contacts.size} contact(en) aangeboden (${parts.size} deel/delen per contact)"
    }

    private fun resultIntent(action: String, incidentId: Long, contactIndex: Int, part: Int, name: String): PendingIntent {
        val intent = Intent(context, SmsResultReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("esos://sms/${System.nanoTime()}/$incidentId/$contactIndex/$part/$action")
            putExtra("part", part + 1)
            putExtra("name", name)
        }
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        fun validNumber(number: String): Boolean = number.matches(Regex("\\+[1-9][0-9]{7,14}"))
    }
}

class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = AppSettings(context)
        val part = intent.getIntExtra("part", 1)
        val name = intent.getStringExtra("name") ?: "Contact"
        settings.smsStatus = when (intent.action) {
            "SENT" -> if (resultCode == Activity.RESULT_OK) "SMS voor $name deel $part verzonden (ontvangst onbekend)"
                else "SMS voor $name deel $part mislukt (code $resultCode)"
            "DELIVERED" -> if (resultCode == Activity.RESULT_OK) "SMS voor $name deel $part afgeleverd (niet gelezen)"
                else "Afleverstatus $name deel $part onbekend (code $resultCode)"
            else -> return
        }
    }
}