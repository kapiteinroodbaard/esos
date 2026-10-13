package org.esos.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object CheckInScheduler {
    private const val PROMPT = "org.esos.app.PROMPT"
    private const val DEADLINE = "org.esos.app.DEADLINE"
    private const val CONFIRM = "org.esos.app.CONFIRM"

    fun clear(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pending(context, PROMPT))
        alarms.cancel(pending(context, DEADLINE))
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().remove("deadline").apply()
        context.getSystemService(NotificationManager::class.java).cancel(102)
    }

    private fun pending(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, CheckInReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { CheckInScheduler.clear(context) }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
            intent.action == Intent.ACTION_TIME_CHANGED
        ) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED && AppSettings(context).speechDetectionEnabled) {
                ListeningService.showBootReminder(context)
            }
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                AlertRuntime.restoreActiveSosNotification(context)
            }
            SosCallScheduler.restore(context)
            CheckInScheduler.clear(context)
            InactivityMonitor.schedule(context)
            InactivityMonitorService.startIfEnabled(context)
            AlertRuntime.recover(context)
            FallDetectionService.startIfEnabled(context)
        }
    }
}