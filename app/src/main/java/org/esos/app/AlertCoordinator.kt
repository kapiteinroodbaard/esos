package org.esos.app

data class GpsFix(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val timestampMillis: Long) {
    fun usable(now: Long) = accuracyMeters in 0f..100f && timestampMillis in (now - 30_000)..now
}

interface LocationRequest {
    fun cancel()
}

interface IncidentLocation {
    fun request(onFix: (GpsFix) -> Unit): LocationRequest
}

interface AlertSender {
    fun send(incidentId: Long, text: String)
}

enum class AlertState { IDLE, COUNTDOWN, SENT, CANCELLED, ERROR }
enum class AlertReason { SOS, MISSED_CHECK_IN, INACTIVITY }

object AlertMessageFormatter {
    fun format(isTest: Boolean, text: String): String =
        if (isTest) "e-SOS SOS-TEST (geen echte noodsituatie): $text" else text

    fun cancellation(isTest: Boolean): String = format(isTest, "SOS geannuleerd.")
}

class AlertCoordinator(
    private val sender: AlertSender,
    private val location: IncidentLocation,
    private val now: () -> Long,
    private val address: () -> String = { "" }
) {
    var state = AlertState.IDLE
        private set
    var incidentId = 0L
        private set
    var deadline = 0L
        private set
    private var sentAt = 0L
    private var fix: GpsFix? = null
    private var locationRequest: LocationRequest? = null
    private var supplementSent = false
    private var reason = AlertReason.SOS

    fun start(reason: AlertReason, countdownMillis: Long = 10_000L): Boolean {
        if (state == AlertState.COUNTDOWN || state == AlertState.SENT) return false
        incidentId++
        this.reason = reason
        state = AlertState.COUNTDOWN
        deadline = now() + countdownMillis
        fix = null
        sentAt = 0L
        supplementSent = false
        val current = incidentId
        try {
            locationRequest = location.request { candidate ->
                if (incidentId == current && (state == AlertState.COUNTDOWN || state == AlertState.SENT)) {
                    val time = now()
                    if (candidate.usable(time)) {
                        if (state == AlertState.COUNTDOWN) fix = candidate
                        else if (!supplementSent && time - sentAt in 0..20_000) {
                            supplementSent = true
                            try {
                                sender.send(current, "Aanvulling op alarm: ${formatFix(candidate)}")
                            } catch (_: RuntimeException) {
                                state = AlertState.ERROR
                            }
                            locationRequest?.cancel()
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            locationRequest = null
        } catch (_: RuntimeException) {
            locationRequest = null
        }
        return true
    }

    fun cancel(): Boolean {
        if (state != AlertState.COUNTDOWN) return false
        state = AlertState.CANCELLED
        locationRequest?.cancel()
        return true
    }

    fun reset() {
        if (state == AlertState.COUNTDOWN) return
        locationRequest?.cancel()
        locationRequest = null
        state = AlertState.IDLE
    }

    fun tick() {
        val time = now()
        if (state == AlertState.COUNTDOWN && time >= deadline) {
            state = AlertState.SENT
            sentAt = time
            val base = when (reason) {
                AlertReason.SOS -> "SOS: ik heb mogelijk hulp nodig."
                AlertReason.MISSED_CHECK_IN -> "SOS: geen dagelijkse bevestiging ontvangen."
                AlertReason.INACTIVITY -> "SOS: telefoon niet ontgrendeld binnen de ingestelde tijd."
            }
            val home = address().trim().takeIf { it.isNotEmpty() }?.let { " Huisadres: $it." } ?: ""
            val gps = fix?.takeIf { it.usable(time) }?.let { " ${formatFix(it)}" } ?: ""
            try {
                sender.send(incidentId, base + home + gps)
            } catch (_: RuntimeException) {
                state = AlertState.ERROR
                locationRequest?.cancel()
            }
            if (gps.isNotEmpty()) {
                supplementSent = true
                locationRequest?.cancel()
            }
        }
        if (state == AlertState.SENT && time - sentAt > 20_000) locationRequest?.cancel()
    }

    private fun formatFix(fix: GpsFix): String =
        "GPS: ${fix.latitude},${fix.longitude} (nauwkeurigheid ${fix.accuracyMeters.toInt()} m, tijd ${fix.timestampMillis}); https://maps.google.com/?q=${fix.latitude},${fix.longitude}"
}