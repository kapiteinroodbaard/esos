package org.esos.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.os.OutcomeReceiver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class PhoneCallUiState(
    val number: String,
    val state: Int,
    val incoming: Boolean
)

internal object PhoneCallController {
    private var currentCall: Call? = null
    var uiState by mutableStateOf<PhoneCallUiState?>(null)
        private set

    fun update(call: Call) {
        currentCall = call
        val details = call.details ?: return
        val state = details.state
        uiState = PhoneCallUiState(
            number = details.handle?.schemeSpecificPart.orEmpty(),
            state = state,
            incoming = state == Call.STATE_RINGING
        )
    }

    fun remove(call: Call) {
        if (currentCall !== call) return
        currentCall = null
        uiState = null
    }

    fun answer() {
        currentCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun disconnect() {
        currentCall?.let { call ->
            if (call.details?.state == Call.STATE_RINGING) call.reject(false, null)
            else call.disconnect()
        }
    }
}

class SosInCallService : InCallService() {
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private val disconnectTimers = mutableMapOf<Call, Runnable>()
    private val callWatchdogs = mutableMapOf<Call, SosCallWatchdog>()
    private val instructionPlayedCalls = mutableSetOf<Call>()
    private var availableEndpoints: List<CallEndpoint> = emptyList()
    private var instructionCall: Call? = null
    private var instructionPlayer: MediaPlayer? = null

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        PhoneCallController.update(call)
        val watchdog = if (PhoneCallAction.isEmergencyCall(call)) SosCallWatchdog() else null
        if (watchdog != null) callWatchdogs[call] = watchdog
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                PhoneCallController.update(call)
                postCallNotification(call)
                outgoingCallState(state)?.let { watchdog?.onStateChanged(it) }
                scheduleUnansweredTimeout(call, state)
                if (PhoneCallAction.isEmergencyCall(call)) {
                    if (state == Call.STATE_ACTIVE) playInstructionsForAnsweredCall(call)
                    else applyPreferredAudioEndpoint(call)
                }
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback, handler)
        postCallNotification(call)
        call.details?.state?.let(::outgoingCallState)?.let { watchdog?.onStateChanged(it) }
        scheduleUnansweredTimeout(call, call.details?.state ?: Call.STATE_NEW)
        if (PhoneCallAction.isEmergencyCall(call) && call.details?.state == Call.STATE_ACTIVE) {
            playInstructionsForAnsweredCall(call)
        } else {
            applyPreferredAudioEndpoint(call)
        }
    }

    override fun onCallRemoved(call: Call) {
        callbacks.remove(call)?.let(call::unregisterCallback)
        disconnectTimers.remove(call)?.let(handler::removeCallbacks)
        callWatchdogs.remove(call)
        instructionPlayedCalls.remove(call)
        if (instructionCall === call) {
            instructionPlayer?.runCatching { release() }
            instructionPlayer = null
            instructionCall = null
        }
        PhoneCallController.remove(call)
        getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
        callbacks.keys.lastOrNull()?.let {
            PhoneCallController.update(it)
            postCallNotification(it)
        }
        super.onCallRemoved(call)
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        super.onAvailableCallEndpointsChanged(availableEndpoints)
        this.availableEndpoints = availableEndpoints
        callbacks.keys.filter(PhoneCallAction::isEmergencyCall)
            .filterNot { it === instructionCall }
            .forEach(::applyPreferredAudioEndpoint)
    }

    private fun scheduleUnansweredTimeout(call: Call, state: Int) {
        if (!PhoneCallAction.isEmergencyCall(call) ||
            (state != Call.STATE_CONNECTING && state != Call.STATE_DIALING) ||
            disconnectTimers.containsKey(call)
        ) return

        val startedAt = call.details?.intentExtras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)
            ?.getLong(PhoneCallAction.EXTRA_STARTED_AT, android.os.SystemClock.elapsedRealtime())
            ?: android.os.SystemClock.elapsedRealtime()
        val remainingMillis = (startedAt + RING_TIMEOUT_MILLIS - android.os.SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        val timeout = Runnable {
            disconnectTimers.remove(call)
            val currentState = call.details?.state
            if (callWatchdogs[call]?.shouldHangUpAtTimeout() == true &&
                (currentState == Call.STATE_CONNECTING || currentState == Call.STATE_DIALING)
            ) {
                call.disconnect()
            }
        }
        disconnectTimers[call] = timeout
        handler.postDelayed(timeout, remainingMillis)
    }

    private fun applyPreferredAudioEndpoint(call: Call) {
        if (!PhoneCallAction.isEmergencyCall(call)) return
        val endpoint = availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
            ?: availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_SPEAKER }
            ?: return
        requestCallEndpointChange(endpoint, mainExecutor, object : OutcomeReceiver<Void?, CallEndpointException> {
            override fun onResult(result: Void?) = Unit
            override fun onError(error: CallEndpointException) = Unit
        })
    }

    private fun playInstructionsForAnsweredCall(call: Call) {
        if (!PhoneCallAction.isEmergencyCall(call) || !instructionPlayedCalls.add(call)) return
        instructionCall = call
        val endpoint = availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
            ?: availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_SPEAKER }
        if (endpoint == null) {
            playInstructionAudio(call)
            return
        }
        requestCallEndpointChange(endpoint, mainExecutor, object : OutcomeReceiver<Void?, CallEndpointException> {
            override fun onResult(result: Void?) = playInstructionAudio(call)
            override fun onError(error: CallEndpointException) = playInstructionAudio(call)
        })
    }

    private fun playInstructionAudio(call: Call) {
        if (instructionCall !== call || call.details?.state != Call.STATE_ACTIVE) {
            finishInstructionAudio(call)
            return
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val player = runCatching {
            MediaPlayer.create(applicationContext, R.raw.telefoon_instructies, attributes, 0)
        }.getOrNull()
        if (player == null) {
            finishInstructionAudio(call)
            return
        }
        instructionPlayer = player
        player.setOnCompletionListener {
            if (instructionPlayer === it) {
                it.release()
                instructionPlayer = null
                finishInstructionAudio(call)
            }
        }
        player.setOnErrorListener { failed, _, _ ->
            if (instructionPlayer === failed) {
                failed.release()
                instructionPlayer = null
                finishInstructionAudio(call)
            }
            true
        }
        runCatching { player.start() }.onFailure {
            if (instructionPlayer === player) {
                player.release()
                instructionPlayer = null
                finishInstructionAudio(call)
            }
        }
    }

    private fun finishInstructionAudio(call: Call) {
        if (instructionCall !== call) return
        instructionCall = null
        if (call.details?.state == Call.STATE_ACTIVE) applyPreferredAudioEndpoint(call)
    }

    private fun postCallNotification(call: Call) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel(
            CALL_CHANNEL, "Telefoongesprekken", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        })
        val state = call.details?.state ?: return
        val number = call.details?.handle?.schemeSpecificPart.orEmpty().ifBlank { "Telefoongesprek" }
        val person = Person.Builder().setName(number).setImportant(true).build()
        val openCall = PendingIntent.getActivity(this, 210,
            Intent(this, PhoneCallActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CALL_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(openCall)
            .setOngoing(state != Call.STATE_DISCONNECTED)
            .setStyle(if (state == Call.STATE_RINGING) {
                Notification.CallStyle.forIncomingCall(person,
                    callAction(ACTION_DECLINE, 212), callAction(ACTION_ANSWER, 213))
            } else {
                Notification.CallStyle.forOngoingCall(person, callAction(ACTION_END, 214))
            })
        notificationManager.notify(CALL_NOTIFICATION_ID, notification.build())
    }

    private fun callAction(action: String, requestCode: Int) = PendingIntent.getBroadcast(this,
        requestCode, Intent(this, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun outgoingCallState(state: Int): OutgoingCallState? = when (state) {
        Call.STATE_CONNECTING -> OutgoingCallState.CONNECTING
        Call.STATE_DIALING -> OutgoingCallState.DIALING
        Call.STATE_ACTIVE -> OutgoingCallState.ACTIVE
        Call.STATE_HOLDING -> OutgoingCallState.HOLDING
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> OutgoingCallState.DISCONNECTED
        else -> null
    }

    companion object {
        const val CALL_CHANNEL = "phone_calls"
        const val CALL_NOTIFICATION_ID = 230
        const val ACTION_ANSWER = "org.esos.app.action.ANSWER_CALL"
        const val ACTION_DECLINE = "org.esos.app.action.DECLINE_CALL"
        const val ACTION_END = "org.esos.app.action.END_CALL"
        private const val RING_TIMEOUT_MILLIS = 10_000L
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SosInCallService.ACTION_ANSWER -> PhoneCallController.answer()
            SosInCallService.ACTION_DECLINE, SosInCallService.ACTION_END -> PhoneCallController.disconnect()
        }
    }
}