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
import java.time.Instant
import java.time.ZoneId

object CheckInTimes {
    fun next(now: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var candidate = today.atTime(hour, minute).atZone(zone)
        if (candidate.toInstant().toEpochMilli() <= now) candidate = today.plusDays(1).atTime(hour, minute).atZone(zone)
        return candidate.toInstant().toEpochMilli()
    }
}

object CheckInScheduler {
    private const val PROMPT = "org.esos.app.PROMPT"
    private const val DEADLINE = "org.esos.app.DEADLINE"
    private const val CONFIRM = "org.esos.app.CONFIRM"
    private const val CHANNEL = "checkin"

    fun schedule(context: Context) {
        val settings = AppSettings(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pending(context, PROMPT))
        alarms.cancel(pending(context, DEADLINE))
        if (!settings.checkInEnabled) return
        if (settings.checkInDeadline > 0) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, settings.checkInDeadline, pending(context, DEADLINE))
        }
        val next = CheckInTimes.next(System.currentTimeMillis(), settings.checkInHour, settings.checkInMinute, ZoneId.systemDefault())
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(context, PROMPT))
    }

    fun confirm(context: Context) {
        val settings = AppSettings(context)
        if (settings.checkInDeadline > 0 && System.currentTimeMillis() >= settings.checkInDeadline) {
            handle(context, DEADLINE)
            return
        }
        settings.checkInDeadline = 0L
        context.getSystemService(NotificationManager::class.java).cancel(102)
        schedule(context)
    }

    fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, CheckInReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun handle(context: Context, action: String?) {
        val settings = AppSettings(context)
        if (action == CONFIRM) { confirm(context); return }
        if (!settings.checkInEnabled) return
        when (action) {
            PROMPT -> {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    settings.checkInDeadline = 0L
                    settings.smsStatus = "Check-in niet gevraagd: meldingen uit. Geen automatisch check-in-alarm vandaag."
                    schedule(context)
                    return
                }
                if (settings.checkInDeadline == 0L) {
                    settings.checkInDeadline = System.currentTimeMillis() + 2 * 60 * 60 * 1000L
                    val notifications = context.getSystemService(NotificationManager::class.java)
                    notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Dagelijkse check-in", NotificationManager.IMPORTANCE_HIGH))
                    notifications.notify(102, Notification.Builder(context, CHANNEL)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle("Alles goed?")
                            .setContentText("Bevestig uw dagelijkse check-in")
                            .setContentIntent(PendingIntent.getActivity(context, 11, Intent(context, MainActivity::class.java),
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                            .addAction(Notification.Action.Builder(null, "Alles goed", pending(context, CONFIRM)).build())
                                .build())
                }
                schedule(context)
            }
            DEADLINE -> {
                if (settings.checkInDeadline > 0 && System.currentTimeMillis() >= settings.checkInDeadline) {
                    settings.checkInDeadline = 0L
                    context.getSystemService(NotificationManager::class.java).cancel(102)
                    if (!AlertRuntime.start(context, AlertReason.MISSED_CHECK_IN)) {
                        settings.smsStatus = "Check-in-alarm kon niet starten: controleer SIM, contact of SMS-toestemming"
                    }
                    schedule(context)
                }
            }
        }
    }
}

class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { CheckInScheduler.handle(context, intent.action) }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
            intent.action == Intent.ACTION_TIME_CHANGED
        ) {
            CheckInScheduler.schedule(context)
            AlertRuntime.recover(context)
        }
    }
}