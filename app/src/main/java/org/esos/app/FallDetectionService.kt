package org.esos.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

object FallDetectionStatus {
    @Volatile var active = false
        private set
    @Volatile var detail = "Valdetectie staat uit"
        private set

    fun update(running: Boolean, reason: String) {
        active = running
        detail = reason
    }
}

class FallDetectionService : Service(), SensorEventListener {
    private var active = false
    private var stopReason = "Valdetectie staat uit"
    private var sensorManager: SensorManager? = null
    private lateinit var settings: AppSettings
    private val detector = FallDetector()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.action != START) return START_NOT_STICKY
        if (active) return START_STICKY
        settings = AppSettings(this)
        if (!settings.fallDetectionEnabled) return stopWithError("Valdetectie is uitgeschakeld")
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            return stopWithError("Geef bewegingsherkenning toestemming via Detectie")
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return stopWithError("Geef meldingentoegang om valdetectie op de achtergrond te gebruiken")
        }
        val manager = getSystemService(SensorManager::class.java)
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return stopWithError("Dit toestel heeft geen versnellingsmeter")
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Valdetectie", NotificationManager.IMPORTANCE_LOW)
        )
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } catch (_: RuntimeException) {
            return stopWithError("Valdetectie kan niet op de achtergrond starten")
        }
        sensorManager = manager
        active = true
        if (!manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)) {
            active = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            return stopWithError("Versnellingsmeter kan niet worden gestart")
        }
        stopReason = "Valdetectie staat uit"
        FallDetectionStatus.update(true, "Versnellingsmeter actief")
        return START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (detector.acceptSample(event.values[0], event.values[1], event.values[2],
                event.timestamp / 1_000_000L, settings.fallSensitivity)
        ) {
            if (AlertRuntime.state() != AlertState.COUNTDOWN && AlertRuntime.state() != AlertState.SENT) {
                val started = AlertRuntime.start(this, AlertReason.SOS)
                FallDetectionStatus.update(true, if (started)
                    "Val gedetecteerd; SOS-alarm gestart" else "Val gedetecteerd; SOS-alarm kon niet starten, controleer Acties")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        active = false
        sensorManager?.unregisterListener(this)
        sensorManager = null
        FallDetectionStatus.update(false, stopReason)
        super.onDestroy()
    }

    private fun stopWithError(reason: String): Int {
        active = false
        stopReason = reason
        FallDetectionStatus.update(false, reason)
        stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle("e-SOS valdetectie")
        .setContentText("Valdetectie is actief")
        .setOngoing(true)
        .build()

    companion object {
        private const val CHANNEL = "fall_detection"
        private const val NOTIFICATION_ID = 104
        private const val START = "org.esos.app.START_FALL_DETECTION"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context,
                Intent(context, FallDetectionService::class.java).setAction(START))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FallDetectionService::class.java))
        }

        fun startIfEnabled(context: Context) {
            val settings = AppSettings(context)
            if (settings.fallDetectionEnabled &&
                context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                runCatching { start(context) }
            }
        }
    }
}