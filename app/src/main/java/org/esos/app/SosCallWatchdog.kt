package org.esos.app

internal enum class OutgoingCallState { CONNECTING, DIALING, ACTIVE, HOLDING, DISCONNECTED }

internal class SosCallWatchdog {
    private var answered = false
    private var ended = false

    fun onStateChanged(state: OutgoingCallState) {
        when (state) {
            OutgoingCallState.ACTIVE, OutgoingCallState.HOLDING -> answered = true
            OutgoingCallState.DISCONNECTED -> ended = true
            OutgoingCallState.CONNECTING, OutgoingCallState.DIALING -> Unit
        }
    }

    fun shouldHangUpAtTimeout(): Boolean = !answered && !ended
}