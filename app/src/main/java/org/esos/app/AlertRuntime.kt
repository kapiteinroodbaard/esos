package org.esos.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.media.ToneGenerator

object AlertRuntime {
    const val CHANNEL = "alarm"
    private const val PREFS = "pending_alarm"
    private var coordinator: AlertCoordinator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null

    fun start(context: Context, reason: AlertReason): Boolean = startInternal(context, reason, false)

    fun startManualTest(context: Context, reason: AlertReason): Boolean = startInternal(context, reason, true)

    private fun startInternal(context: Context, reason: AlertReason, isTest: Boolean): Boolean {
        val app = context.applicationContext
        val settings = AppSettings(app)
        val sms = SmsAlertSender(app, settings)
        if (coordinator?.state == AlertState.COUNTDOWN || coordinator?.state == AlertState.SENT) return false
        if (settings.smsEnabled && !sms.ready()) return false
        val sender = senderFor(settings, sms, isTest)
        coordinator?.reset()
        val alarm = AlertCoordinator(sender, AndroidIncidentLocation(app, settings), System::currentTimeMillis) { settings.homeAddress }
        coordinator = alarm
        if (!alarm.start(reason)) return false
        if (!isTest) settings.sosModeActive = true
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("deadline", alarm.deadline).putString("reason", reason.name)
            .putBoolean("manualTest", isTest).commit()
        app.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, alarm.deadline, fallback(app))
        notificationManager(app).createNotificationChannel(
            NotificationChannel(CHANNEL, "SOS-alarm", NotificationManager.IMPORTANCE_HIGH)
        )
        tick(app, alarm)
        return true
    }

    fun cancel(context: Context): Boolean {
        val settings = AppSettings(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hasPendingAlarm = prefs.contains("deadline")
        val cancelledCountdown = coordinator?.cancel() == true
        if (!cancelledCountdown && !settings.sosModeActive && !hasPendingAlarm) return false
        if (settings.smsEnabled) {
            val isTest = prefs.getBoolean("manualTest", false)
            val incidentId = coordinator?.incidentId?.takeIf { it > 0L } ?: System.currentTimeMillis()
            try {
                SmsAlertSender(context, settings).send(incidentId, AlertMessageFormatter.cancellation(isTest))
            } catch (_: RuntimeException) {
                settings.smsStatus = "SOS geannuleerd, maar de annulerings-SMS is niet verzonden. Controleer contact, SIM en machtigingen."
            }
        }
        settings.sosModeActive = false
        clearPending(context)
        coordinator?.reset()
        tone?.release()
        tone = null
        notificationManager(context).cancel(101)
        return true
    }

    fun state(): AlertState = coordinator?.state ?: AlertState.IDLE
    fun remainingSeconds(): Int = coordinator?.takeIf { it.state == AlertState.COUNTDOWN }
        ?.let { ((it.deadline - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0).toInt() } ?: 0

    fun recover(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deadline = prefs.getLong("deadline", 0L)
        if (deadline <= 0) return
        if (System.currentTimeMillis() < deadline) {
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, deadline, fallback(context))
            return
        }
        val reason = runCatching { AlertReason.valueOf(prefs.getString("reason", "") ?: "") }.getOrNull()
        val legacySimulation = prefs.getBoolean("simulate", false)
        val isTest = prefs.getBoolean("manualTest", false)
        clearPending(context)
        if (reason == null || legacySimulation) return
        val active = coordinator
        if (active?.state == AlertState.COUNTDOWN) {
            active.tick()
            if (active.state == AlertState.ERROR) AppSettings(context).smsStatus = "SMS niet verzonden: controleer SIM en machtigingen"
        } else {
            val settings = AppSettings(context)
            val sms = SmsAlertSender(context, settings)
            if (settings.smsEnabled && !sms.ready()) {
                settings.smsStatus = "Alarm na herstel niet verzonden: controleer SIM en machtigingen"
                return
            }
            val recovered = AlertCoordinator(senderFor(settings, sms, isTest), AndroidIncidentLocation(context, settings), System::currentTimeMillis) {
                settings.homeAddress
            }
            coordinator = recovered
            recovered.start(reason, 0L)
            recovered.tick()
            if (recovered.state == AlertState.ERROR) settings.smsStatus = "SMS niet verzonden: controleer SIM en machtigingen"
            handler.postDelayed({ recovered.tick(); recovered.reset() }, 20_001L)
        }
        notificationManager(context).cancel(101)
    }

    private fun fallback(context: Context) = PendingIntent.getBroadcast(context, 12,
        Intent(context, AlarmDeadlineReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun senderFor(settings: AppSettings, sms: SmsAlertSender, isTest: Boolean): AlertSender = object : AlertSender {
        override fun send(incidentId: Long, text: String) {
            if (settings.smsEnabled) sms.send(incidentId, AlertMessageFormatter.format(isTest, text))
            else settings.smsStatus = "SMS uitgeschakeld; alarm niet per SMS verstuurd"
        }
    }

    private fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSystemService(AlarmManager::class.java).cancel(fallback(context))
    }

    private fun tick(context: Context, alarm: AlertCoordinator) {
        if (coordinator !== alarm || alarm.state != AlertState.COUNTDOWN) return
        if (System.currentTimeMillis() >= alarm.deadline) recover(context) else alarm.tick()
        if (alarm.state == AlertState.COUNTDOWN) {
            val seconds = remainingSeconds()
            notificationManager(context).notify(101, Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("SOS: nog $seconds seconden")
                .setContentText("Annuleer alleen als er geen hulp nodig is")
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Annuleer", PendingIntent.getBroadcast(
                    context, 10, Intent(context, CancelAlertReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )).build()).build())
            try {
                if (tone == null) tone = ToneGenerator(AudioManager.STREAM_ALARM, 80)
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            } catch (_: RuntimeException) { }
            handler.postDelayed({ tick(context, alarm) }, 250L)
        } else {
            tone?.release()
            tone = null
            notificationManager(context).cancel(101)
            handler.postDelayed({ alarm.tick(); alarm.reset() }, 20_001L)
        }
    }

    private fun notificationManager(context: Context) = context.getSystemService(NotificationManager::class.java)
}

class CancelAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { AlertRuntime.cancel(context) }
}

class AlarmDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { AlertRuntime.recover(context) }
}