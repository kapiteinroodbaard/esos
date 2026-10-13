package org.esos.app

internal class SmsThenCallAlertSender(
    private val smsEnabled: () -> Boolean,
    private val callEnabled: () -> Boolean,
    private val isTest: Boolean,
    private val sendSms: (Long, String) -> Unit,
    private val startCall: (Long) -> Unit,
    private val onAlertStarted: () -> Unit = {},
    private val onSmsSent: () -> Unit = {}
) : AlertSender {
    private var callStarted = false
    private var alertStarted = false
    private var smsAnnounced = false

    override fun send(incidentId: Long, text: String) {
        if (!alertStarted) {
            alertStarted = true
            onAlertStarted()
        }
        try {
            if (smsEnabled()) {
                sendSms(incidentId, text)
                if (!smsAnnounced && !isTest) {
                    smsAnnounced = true
                    onSmsSent()
                }
            }
        } finally {
            if (!isTest && callEnabled() && !callStarted) {
                callStarted = true
                startCall(incidentId)
            }
        }
    }
}