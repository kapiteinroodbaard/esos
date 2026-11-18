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

object AlertRuntime {
    const val CHANNEL = "alarm"
    private const val ACTIVE_SOS_CHANNEL = "active_sos"
    private const val PREFS = "pending_alarm"
    private const val ACTIVE_SOS_NOTIFICATION_ID = 106
    private const val PROGRESS = "sos_progress"
    private var coordinator: AlertCoordinator? = null
    private val handler = Handler(Looper.getMainLooper())

    fun start(context: Context, reason: AlertReason): Boolean =
        startInternal(context, reason, reason.countdownMillis)

    fun startManual(context: Context, reason: AlertReason): Boolean = startInternal(context, reason, 0L)

    private fun startInternal(context: Context, reason: AlertReason, countdownMillis: Long): Boolean {
        val app = context.applicationContext
        val settings = AppSettings(app)
        val sms = SmsAlertSender(app, settings)
        if (coordinator?.state == AlertState.COUNTDOWN || coordinator?.state == AlertState.SENT) return false
        if (settings.smsEnabled && !sms.ready() &&
            !(settings.callEnabled && PhoneCallAction.canStart(app, settings))) return false
        if (settings.callEnabled && !settings.smsEnabled && !PhoneCallAction.canStart(app, settings)) return false
        val sender = senderFor(app, settings, sms)
        coordinator?.reset()
        val alarm = AlertCoordinator(sender, AndroidIncidentLocation(app, settings), System::currentTimeMillis)
        coordinator = alarm
        if (!alarm.start(reason, countdownMillis)) return false
        CountdownBeeper.stop()
        SosSignals.announce(app)
        if (countdownMillis > 0L) {
            countdownAnnouncement(reason)?.let { AlertAudioPlayer.play(app, it) }
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("deadline", alarm.deadline).putString("reason", reason.name)
            .commit()
        app.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, alarm.deadline, fallback(app))
        notificationManager(app).createNotificationChannel(
            NotificationChannel(CHANNEL, "SOS-alarm", NotificationManager.IMPORTANCE_HIGH)
        )
        tick(app, alarm)
        if (countdownMillis > 0L) runCatching { app.startActivity(CountdownActivity.intent(app)) }
        return true
    }

    /** Slaat de rest van de countdown over: de gebruiker vraagt zelf om hulp. */
    fun sendNow(context: Context): Boolean {
        val app = context.applicationContext
        val alarm = coordinator?.takeIf { it.state == AlertState.COUNTDOWN } ?: return false
        clearPending(app)
        CountdownBeeper.stop()
        alarm.triggerNow()
        notificationManager(app).cancel(101)
        handler.postDelayed({ alarm.tick(); alarm.reset() }, 20_001L)
        return true
    }

    fun cancel(context: Context): Boolean {
        val settings = AppSettings(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hasPendingAlarm = prefs.contains("deadline")
        if (SosSignals.running && !settings.sosModeActive) SosSignals.stop(context)
        CountdownBeeper.stop()
        val cancelledCountdown = coordinator?.cancel() == true
        val sosWasActive = settings.sosModeActive
        if (!cancelledCountdown && !sosWasActive && !hasPendingAlarm) return false
        if (sosWasActive) AlertAudioPlayer.play(context, R.raw.noodprocedure_geannuleerd)
        if (sosWasActive && settings.smsEnabled) {
            val incidentId = coordinator?.incidentId?.takeIf { it > 0L } ?: System.currentTimeMillis()
            runCatching {
                SmsAlertSender(context, settings).send(incidentId, AlertMessageFormatter.CANCELLATION)
            }
        }
        settings.sosModeActive = false
        SosCallScheduler.cancel(context)
        context.getSharedPreferences(PROGRESS, Context.MODE_PRIVATE).edit().clear().apply()
        SosOverlay.stop(context)
        SosSignals.stop(context)
        clearPending(context)
        coordinator?.reset()
        notificationManager(context).cancel(101)
        notificationManager(context).cancel(ACTIVE_SOS_NOTIFICATION_ID)
        return true
    }

    private fun countdownAnnouncement(reason: AlertReason): Int? = when (reason) {
        AlertReason.SPEECH_DETECTED -> R.raw.hulproep_gedetecteerd
        AlertReason.INACTIVITY -> R.raw.inactiviteitswaarschuwing
        AlertReason.FALL -> R.raw.val_detectie
        AlertReason.SOS, AlertReason.MISSED_CHECK_IN -> null
    }

    /** Wat er met de SMS is gebeurd tijdens de huidige SOS, in gewone taal. */
    fun smsStatus(context: Context): String =
        context.getSharedPreferences(PROGRESS, Context.MODE_PRIVATE).getString("sms", "").orEmpty()

    private fun saveSmsStatus(context: Context, status: String) {
        context.getSharedPreferences(PROGRESS, Context.MODE_PRIVATE).edit().putString("sms", status).apply()
    }

    fun state(): AlertState = coordinator?.state ?: AlertState.IDLE
    fun remainingSeconds(): Int = coordinator?.takeIf { it.state == AlertState.COUNTDOWN }
        ?.let { ((it.deadline - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0).toInt() } ?: 0

    fun restoreActiveSosNotification(context: Context) {
        if (AppSettings(context).sosModeActive) {
            showActiveSosNotification(context)
            SosOverlay.start(context)
            SosSignals.start(context, restoring = true)
        }
    }

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
        clearPending(context)
        if (reason == null || legacySimulation) return
        val active = coordinator
        if (active?.state == AlertState.COUNTDOWN) {
            active.tick()
        } else {
            val settings = AppSettings(context)
            val sms = SmsAlertSender(context, settings)
            if (settings.smsEnabled && !sms.ready() &&
                !(settings.callEnabled && PhoneCallAction.canStart(context, settings))) return
            if (settings.callEnabled && !settings.smsEnabled && !PhoneCallAction.canStart(context, settings)) return
            val recovered = AlertCoordinator(senderFor(context, settings, sms), AndroidIncidentLocation(context, settings), System::currentTimeMillis)
            coordinator = recovered
            recovered.start(reason, 0L)
            recovered.tick()
            handler.postDelayed({ recovered.tick(); recovered.reset() }, 20_001L)
        }
        notificationManager(context).cancel(101)
    }

    private fun enterSosPhase(context: Context) {
        context.getSharedPreferences(PROGRESS, Context.MODE_PRIVATE).edit().clear().commit()
        AppSettings(context).sosModeActive = true
        showActiveSosNotification(context)
        SosOverlay.start(context)
        AlertAudioPlayer.play(context, R.raw.noodprocedure_gestart)
        SosSignals.start(context)
    }

    private fun showActiveSosNotification(context: Context) {
        val notifications = notificationManager(context)
        notifications.createNotificationChannel(
            NotificationChannel(ACTIVE_SOS_CHANNEL, "SOS actief", NotificationManager.IMPORTANCE_HIGH)
        )
        val openApp = PendingIntent.getActivity(context, 13, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(ACTIVE_SOS_NOTIFICATION_ID, Notification.Builder(context, ACTIVE_SOS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SOS actief")
            .setContentText("SOS-modus is actief. Tik om e-SOS te openen.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build())
    }

    private fun fallback(context: Context) = PendingIntent.getBroadcast(context, 12,
        Intent(context, AlarmDeadlineReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun senderFor(context: Context, settings: AppSettings, sms: SmsAlertSender) =
        SmsThenCallAlertSender(
            smsEnabled = { settings.smsEnabled },
            callEnabled = { settings.callEnabled },
            sendSms = { incidentId, text ->
                try {
                    sms.send(incidentId, text)
                } catch (e: RuntimeException) {
                    if (smsStatus(context).isEmpty()) saveSmsStatus(context, "SMS versturen is mislukt.")
                    throw e
                }
            },
            startCall = { incidentId ->
                if (!PhoneCallAction.canStart(context, settings)) {
                    throw IllegalStateException("Belactie niet gereed; controleer belmachtiging en noodcontact")
                }
                val contacts = settings.contacts
                AlertAudioPlayer.enqueue(context, R.raw.bellen_contact) {
                    if (settings.sosModeActive && settings.callEnabled &&
                        PhoneCallAction.canStart(context, settings)) {
                        runCatching {
                            SosCallScheduler.start(context, contacts, incidentId)
                        }.onFailure {
                            android.util.Log.e("AlertRuntime", "Unable to start SOS call", it)
                        }
                    }
                }
            },
            onAlertStarted = { enterSosPhase(context) },
            onSmsSent = {
                saveSmsStatus(context, "SMS verstuurd naar ${dutchList(settings.contacts.map { it.name })}.")
                AlertAudioPlayer.enqueue(context, R.raw.sms_verstuurd)
            }
        )

    private fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSystemService(AlarmManager::class.java).cancel(fallback(context))
    }

    private fun tick(context: Context, alarm: AlertCoordinator) {
        if (coordinator !== alarm || alarm.state != AlertState.COUNTDOWN) return
        if (System.currentTimeMillis() >= alarm.deadline) recover(context) else alarm.tick()
        if (alarm.state == AlertState.COUNTDOWN) {
            val seconds = remainingSeconds()
            CountdownBeeper.onTick(context, seconds)
            notificationManager(context).notify(101, Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("SOS: nog $seconds seconden")
                .setContentText("Annuleer alleen als er geen hulp nodig is")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(PendingIntent.getActivity(context, 14, CountdownActivity.intent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE), true)
                .addAction(Notification.Action.Builder(null, "Annuleer", PendingIntent.getBroadcast(
                    context, 10, Intent(context, CancelAlertReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )).build()).build())
            handler.postDelayed({ tick(context, alarm) }, 250L)
        } else {
            CountdownBeeper.stop()
            notificationManager(context).cancel(101)
            handler.postDelayed({ alarm.tick(); alarm.reset() }, 20_001L)
        }
    }

    private fun notificationManager(context: Context) = context.getSystemService(NotificationManager::class.java)
}

/** "Jan", "Jan en Marie", "Jan, Piet en Marie". */
internal fun dutchList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> items.dropLast(1).joinToString(", ") + " en " + items.last()
}

class CancelAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { AlertRuntime.cancel(context) }
}

class AlarmDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { AlertRuntime.recover(context) }
}