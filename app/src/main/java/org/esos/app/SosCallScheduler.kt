package org.esos.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

object SosCallScheduler {
    private const val PREFS = "sos_call_round"
    private const val INDEX = "index"
    private const val INCIDENT = "incident"
    private const val DEADLINE = "deadline"
    private const val CHANNEL = "sos_call_errors"

    fun status(context: Context): String = prefs(context).getString("status", "").orEmpty()

    fun start(context: Context, contacts: List<FamilyContact>, incidentId: Long) {
        if (contacts.isEmpty()) return
        cancel(context)
        val entries = JSONArray()
        contacts.forEach { contact ->
            entries.put(JSONObject().put("name", contact.name).put("number", contact.number))
        }
        prefs(context).edit().putString("contacts", entries.toString())
            .putLong(INCIDENT, incidentId).putInt(INDEX, 0).putLong(DEADLINE, 0L).commit()
        attempt(context, incidentId, 0)
    }

    fun attempt(context: Context, incidentId: Long, expectedIndex: Int) {
        val prefs = prefs(context)
        if (prefs.getLong(INCIDENT, -1L) != incidentId || prefs.getInt(INDEX, -1) != expectedIndex) return
        val settings = AppSettings(context)
        if (!settings.sosModeActive || !settings.callEnabled ||
            context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            cancel(context)
            return
        }
        if (prefs.getLong(DEADLINE, 0L) > System.currentTimeMillis()) {
            restore(context)
            return
        }
        val contacts = runCatching {
            val entries = JSONArray(prefs.getString("contacts", "[]"))
            (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                FamilyContact(entry.getString("name"), entry.getString("number"))
            }
        }.getOrDefault(emptyList())
        val contact = contacts.getOrNull(expectedIndex) ?: run {
            cancel(context)
            return
        }
        val next = TimedCallRound.nextIndex(expectedIndex, contacts.size)
        val deadline = TimedCallRound.nextDeadline(System.currentTimeMillis())
        prefs.edit().putInt(INDEX, next ?: -1).putLong(DEADLINE, if (next == null) 0L else deadline)
            .putString("status", if (next == null) "Laatste contact wordt gebeld; belronde afgerond."
                else "Contact ${expectedIndex + 1}/${contacts.size}; volgende belpoging over 15 minuten.")
            .commit()
        if (next != null) schedule(context, incidentId, next, deadline)
        runCatching {
            check(SmsAlertSender.validNumber(contact.number)) { "Ongeldig telefoonnummer" }
            PhoneCallAction.startEmergencyCall(context, contact, incidentId)
        }.onFailure {
            val message = "Belpoging naar ${contact.name} mislukt." +
                if (next == null) " Belronde afgerond." else " Volgend contact wordt later geprobeerd."
            prefs.edit().putString("status", message).apply()
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Belactie mislukt",
                NotificationManager.IMPORTANCE_HIGH))
            manager.notify(232, Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("SOS-belactie mislukt")
                .setContentText(message).build())
        }
    }

    fun restore(context: Context) {
        val prefs = prefs(context)
        val index = prefs.getInt(INDEX, -1)
        if (index < 0) return
        val settings = AppSettings(context)
        if (!settings.sosModeActive || !settings.callEnabled) {
            cancel(context)
            return
        }
        schedule(context, prefs.getLong(INCIDENT, 0L), index,
            prefs.getLong(DEADLINE, 0L).coerceAtLeast(System.currentTimeMillis()))
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, 0L, -1))
        prefs(context).edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancel(232)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun schedule(context: Context, incidentId: Long, index: Int, deadline: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = pending(context, incidentId, index)
        if (alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pending)
                return
            } catch (_: SecurityException) {
                Unit
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pending)
    }

    private fun pending(context: Context, incidentId: Long, index: Int) = PendingIntent.getBroadcast(
        context, 230, Intent(context, SosCallReceiver::class.java)
            .putExtra(INCIDENT, incidentId).putExtra(INDEX, index),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    internal fun receive(context: Context, intent: Intent) {
        attempt(context, intent.getLongExtra(INCIDENT, -1L), intent.getIntExtra(INDEX, -1))
    }
}

class SosCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            SosCallScheduler.restore(context)
        } else {
            SosCallScheduler.receive(context, intent)
        }
    }
}