package org.esos.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlin.concurrent.thread

enum class ListeningState { STOPPED, STARTING, LISTENING, PAUSED, ERROR }

object ListeningStatus {
    @Volatile var state = ListeningState.STOPPED
        private set
    @Volatile var detail = "Luisteren staat uit"
        private set

    fun update(newState: ListeningState, reason: String) {
        state = newState
        detail = reason
    }
}

class ListeningService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    @Volatile private var inCall = false
    @Volatile private var silenced = false
    private var callMonitor: CallMonitor? = null

    private inner class CallMonitor : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            inCall = state != TelephonyManager.CALL_STATE_IDLE
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            AppSettings(this).speechDetectionEnabled = false
            getSystemService(NotificationManager::class.java).cancel(BOOT_NOTIFICATION_ID)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START || active) return START_NOT_STICKY
        val settings = AppSettings(this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ||
            (settings.smsEnabled && !SmsAlertSender(this, settings).ready())
        ) {
            ListeningStatus.update(ListeningState.ERROR, "Microfoon, meldingen, contact, SIM of SMS ontbreekt")
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Spraakmodellen laden"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (error: RuntimeException) {
            ListeningStatus.update(ListeningState.ERROR, "Luisterservice niet toegestaan: ${error.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        settings.speechDetectionEnabled = true
        getSystemService(NotificationManager::class.java).cancel(BOOT_NOTIFICATION_ID)
        active = true
        ListeningStatus.update(ListeningState.STARTING, "Offline modellen laden")
        worker = thread(name = "sos-voice", isDaemon = true) { listen() }
        return START_NOT_STICKY
    }

    private fun listen() {
        try {
            val dutch = ModelAssets.unpack(this, "vosk-model-small-nl-0.22") { active }
            if (!active) return
            VoskDetector(dutch, AppSettings(this).wakePhrases).use { detector ->
                val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(minimum > 0) { "16 kHz microfoon niet beschikbaar" }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    error("Microfoontoegang ingetrokken")
                }
                val audio = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(minimum, 6_400)).build()
                recorder = audio
                val audioManager = getSystemService(AudioManager::class.java)
                val telephony = getSystemService(TelephonyManager::class.java)
                val monitor = CallMonitor()
                callMonitor = monitor
                telephony.registerTelephonyCallback(mainExecutor, monitor)
                val recordingCallback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                        silenced = configs.any { it.clientAudioSessionId == audio.audioSessionId && it.isClientSilenced }
                    }
                }
                audio.registerAudioRecordingCallback(mainExecutor, recordingCallback)
                try {
                    require(audio.state == AudioRecord.STATE_INITIALIZED) { "Microfoon kan niet starten" }
                    val samples = ShortArray(3_200)
                    var emptyReads = 0
                    var paused = false
                    var checks = 0
                    while (active) {
                        if (++checks >= 50) {
                            checks = 0
                            val settings = AppSettings(this)
                            if (settings.smsEnabled && !SmsAlertSender(this, settings).ready()) error("SMS of SIM niet beschikbaar")
                            if (batteryLow()) error("Batterij bijna leeg")
                        }
                        if (inCall) silenced = false
                        val unavailable = inCall || silenced || audioManager.isMicrophoneMute
                        if (unavailable) {
                            if (!paused) {
                                detector.reset()
                                paused = true
                            }
                            if (inCall && audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop()
                            val reason = if (inCall) "Niet beschermd tijdens telefoongesprek" else "Niet beschermd: microfoon stilgezet"
                            if (ListeningStatus.detail != reason) {
                                ListeningStatus.update(ListeningState.PAUSED, reason)
                                getSystemService(NotificationManager::class.java)
                                    .notify(NOTIFICATION_ID, notification(reason))
                            }
                            if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                            } else Thread.sleep(200)
                            continue
                        }
                        if (paused) {
                            paused = false
                        }
                        if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            audio.startRecording()
                            require(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microfoon neemt niet op" }
                        }
                        if (ListeningStatus.state != ListeningState.LISTENING) {
                            ListeningStatus.update(ListeningState.LISTENING, "Luistert in het Nederlands")
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, notification("Luistert naar Nederlandse hulpfrases"))
                        }
                        val count = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        if (!active) break
                        if (inCall || silenced || audioManager.isMicrophoneMute) continue
                        if (count < 0) error("Microfoonopname onderbroken ($count)")
                        if (count == 0) {
                            if (++emptyReads > 25) error("Microfoon levert geen geluid")
                            continue
                        }
                        emptyReads = 0
                        if (detector.acceptPcm16(samples, count)) {
                            mainHandler.post {
                                if (active && AlertRuntime.state() != AlertState.COUNTDOWN &&
                                    AlertRuntime.state() != AlertState.SENT &&
                                    !AlertRuntime.start(this, AlertReason.SPEECH_DETECTED)
                                ) {
                                    ListeningStatus.update(ListeningState.ERROR, "Alarm kan niet starten: controleer SMS en SIM")
                                    stopSelf()
                                }
                            }
                        }
                    }
                } finally {
                    audio.unregisterAudioRecordingCallback(recordingCallback)
                    telephony.unregisterTelephonyCallback(monitor)
                    callMonitor = null
                    audio.release()
                    recorder = null
                }
            }
        } catch (error: Exception) {
            if (active) {
                ListeningStatus.update(ListeningState.ERROR, "Spraakherkenning gestopt: ${error.javaClass.simpleName}")
                mainHandler.post { stopSelf() }
            }
        } catch (error: LinkageError) {
            if (active) {
                ListeningStatus.update(ListeningState.ERROR, "Spraakmodel niet beschikbaar: ${error.javaClass.simpleName}")
                mainHandler.post { stopSelf() }
            }
        }
    }

    private fun batteryLow(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return level >= 0 && scale > 0 && level * 100 / scale < 15
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Spraakdetectie", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("e-SOS spraakdetectie")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 30, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .addAction(Notification.Action.Builder(null, "Stop", PendingIntent.getService(this, 31,
            Intent(this, ListeningService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()).build()

    override fun onDestroy() {
        active = false
        recorder?.let { runCatching { it.stop() } }
        worker?.interrupt()
        if (ListeningStatus.state != ListeningState.ERROR) ListeningStatus.update(ListeningState.STOPPED, "Luisteren staat uit")
        super.onDestroy()
    }

    companion object {
        const val START = "org.esos.app.START_LISTENING"
        const val STOP = "org.esos.app.STOP_LISTENING"
        private const val CHANNEL = "listening"
        private const val NOTIFICATION_ID = 103
        private const val BOOT_NOTIFICATION_ID = 105
        private const val SAMPLE_RATE = 16_000

        fun showBootReminder(context: Context) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val notifications = context.getSystemService(NotificationManager::class.java)
            if (!notifications.areNotificationsEnabled()) return
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "Spraakdetectie", NotificationManager.IMPORTANCE_LOW))
            val startIntent = PendingIntent.getForegroundService(context, 32,
                Intent(context, ListeningService::class.java).setAction(START),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val openAppIntent = PendingIntent.getActivity(context, 33, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notifications.notify(BOOT_NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Spraakdetectie staat uit")
                .setContentText("Tik op Starten om de spraakdetectie te hervatten.")
                .setContentIntent(openAppIntent)
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, "Starten", startIntent).build())
                .build())
        }
    }
}