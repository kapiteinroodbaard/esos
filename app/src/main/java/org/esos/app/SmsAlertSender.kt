package org.esos.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
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

        /** Zet een nummer zoals '06 12345678' om naar '+31612345678', op basis van het land van de SIM. */
        fun toInternational(context: Context, raw: String): String {
            val digits = withInternationalPrefix(PhoneNumberUtils.normalizeNumber(raw))
            if (digits.startsWith("+")) return digits
            val telephony = context.getSystemService(TelephonyManager::class.java)
            val country = listOf(telephony?.simCountryIso, telephony?.networkCountryIso, "nl")
                .firstOrNull { !it.isNullOrBlank() }!!.uppercase()
            return PhoneNumberUtils.formatNumberToE164(digits, country) ?: digits
        }

        internal fun withInternationalPrefix(digits: String): String =
            if (digits.startsWith("00")) "+" + digits.drop(2) else digits
    }
}