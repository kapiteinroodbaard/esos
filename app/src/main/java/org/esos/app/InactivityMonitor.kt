package org.esos.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object InactivityTimes {
    const val DEFAULT_SLEEP_START_MINUTE = 23 * 60
    const val DEFAULT_SLEEP_END_MINUTE = 8 * 60
    private const val HOUR_MILLIS = 60 * 60 * 1000L

    fun deadline(
        lockedSince: Long,
        durationHours: Int,
        sleepStartMinute: Int = DEFAULT_SLEEP_START_MINUTE,
        sleepEndMinute: Int = DEFAULT_SLEEP_END_MINUTE,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Long {
        var remaining = durationHours.coerceIn(1, 72) * HOUR_MILLIS
        val startMinute = sleepStartMinute.coerceIn(0, 1439)
        val endMinute = sleepEndMinute.coerceIn(0, 1439)
        if (startMinute == endMinute) return lockedSince + remaining

        val sleepStart = LocalTime.of(startMinute / 60, startMinute % 60)
        val sleepEnd = LocalTime.of(endMinute / 60, endMinute % 60)
        var cursor = Instant.ofEpochMilli(lockedSince)
        while (true) {
            val currentDate = cursor.atZone(zoneId).toLocalDate()
            val (windowStart, windowEnd) = (-1L..1L)
                .map { offset ->
                    val startDate: LocalDate = currentDate.plusDays(offset)
                    val endDate = if (endMinute <= startMinute) startDate.plusDays(1) else startDate
                    ZonedDateTime.of(startDate, sleepStart, zoneId).toInstant() to
                        ZonedDateTime.of(endDate, sleepEnd, zoneId).toInstant()
                }
                .filter { (_, end) -> end.isAfter(cursor) }
                .minBy { (start, _) -> start }

            if (!cursor.isBefore(windowStart) && cursor.isBefore(windowEnd)) {
                cursor = windowEnd
            } else {
                val awakeBeforeSleep = Duration.between(cursor, windowStart).toMillis()
                if (remaining <= awakeBeforeSleep) return cursor.plusMillis(remaining).toEpochMilli()
                remaining -= awakeBeforeSleep
                cursor = windowEnd
            }
        }
    }
}

object InactivityMonitor {
    private const val DEADLINE = "org.esos.app.INACTIVITY_DEADLINE"

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        val settings = AppSettings(app)
        settings.inactivityEnabled = enabled
        settings.inactivityLockedSince = 0L
        settings.inactivityTriggered = false
        cancel(app)
        if (enabled) InactivityMonitorService.startIfEnabled(app)
        else app.stopService(Intent(app, InactivityMonitorService::class.java))
    }

    fun onMonitorStarted(context: Context) {
        val settings = AppSettings(context)
        if (!settings.inactivityEnabled) return
        val isLocked = !context.getSystemService(PowerManager::class.java).isInteractive ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (isLocked && settings.inactivityLockedSince == 0L && !settings.inactivityTriggered) {
            settings.inactivityLockedSince = System.currentTimeMillis()
        } else if (!isLocked) {
            onUserPresent(context)
        }
        schedule(context)
    }

    fun onScreenOff(context: Context) {
        val settings = AppSettings(context)
        if (!settings.inactivityEnabled || settings.inactivityTriggered) return
        if (settings.inactivityLockedSince == 0L) settings.inactivityLockedSince = System.currentTimeMillis()
        schedule(context)
    }

    fun onUserPresent(context: Context) {
        val settings = AppSettings(context)
        settings.inactivityLockedSince = 0L
        settings.inactivityTriggered = false
        cancel(context)
    }

    fun schedule(context: Context) {
        val app = context.applicationContext
        val settings = AppSettings(app)
        val alarm = app.getSystemService(AlarmManager::class.java)
        val pending = pending(app)
        alarm.cancel(pending)
        if (!settings.inactivityEnabled || settings.inactivityTriggered || settings.inactivityLockedSince <= 0L) return
        val deadline = InactivityTimes.deadline(settings.inactivityLockedSince, settings.inactivityHours,
            settings.inactivitySleepStartMinute, settings.inactivitySleepEndMinute)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pending)
    }

    fun handleDeadline(context: Context) {
        val settings = AppSettings(context)
        if (!settings.inactivityEnabled || settings.inactivityTriggered || settings.inactivityLockedSince <= 0L) return
        val deadline = InactivityTimes.deadline(settings.inactivityLockedSince, settings.inactivityHours,
            settings.inactivitySleepStartMinute, settings.inactivitySleepEndMinute)
        if (System.currentTimeMillis() < deadline) {
            schedule(context)
            return
        }
        settings.inactivityTriggered = true
        cancel(context)
        AlertRuntime.start(context, AlertReason.INACTIVITY)
    }

    private fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context))
    }

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 41, Intent(context, InactivityAlarmReceiver::class.java).setAction(DEADLINE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class InactivityAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "org.esos.app.INACTIVITY_DEADLINE") InactivityMonitor.handleDeadline(context)
    }
}

class InactivityMonitorService : Service() {
    private var receiverRegistered = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> InactivityMonitor.onScreenOff(context)
                Intent.ACTION_USER_PRESENT -> InactivityMonitor.onUserPresent(context)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AppSettings(this).inactivityEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Inactiviteitsbewaking", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("e-SOS-inactiviteitsbewaking")
            .setContentText("Ontgrendeling bevestigt dat je beschikbaar bent")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 42, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            InactivityMonitor.onMonitorStarted(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(screenStateReceiver)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "inactivity"
        private const val NOTIFICATION_ID = 104

        fun startIfEnabled(context: Context) {
            if (AppSettings(context).inactivityEnabled) {
                ContextCompat.startForegroundService(context,
                    Intent(context, InactivityMonitorService::class.java))
            }
        }
    }
}