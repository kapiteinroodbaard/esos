package org.esos.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
enum class AlertReason(val countdownMillis: Long) {
    SOS(60_000L),
    SPEECH_DETECTED(60_000L),
    MISSED_CHECK_IN(60_000L),
    INACTIVITY(60_000L),
    FALL(60_000L)
}

object AlertMessageFormatter {
    const val CANCELLATION = "SOS geannuleerd."
}

class AlertCoordinator(
    private val sender: AlertSender,
    private val location: IncidentLocation,
    private val now: () -> Long
) {
    var state = AlertState.IDLE
        private set
    var incidentId = 0L
        private set
    var deadline = 0L
        private set
    /** Of het alarmbericht een GPS-locatie bevatte; zo niet, dan zoekt `SosLocationService` verder. */
    var locationIncluded = false
        private set
    private var fix: GpsFix? = null
    private var locationRequest: LocationRequest? = null
    private var message = alertMessage(AlertReason.SOS)

    fun start(reason: AlertReason, countdownMillis: Long = reason.countdownMillis,
              message: String = alertMessage(reason)): Boolean {
        if (state == AlertState.COUNTDOWN || state == AlertState.SENT) return false
        incidentId++
        this.message = message
        state = AlertState.COUNTDOWN
        deadline = now() + countdownMillis
        fix = null
        locationIncluded = false
        val current = incidentId
        try {
            locationRequest = location.request { candidate ->
                if (incidentId == current && state == AlertState.COUNTDOWN && candidate.usable(now())) fix = candidate
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

    /** Beëindigt de countdown meteen en verstuurt het alarm nu. */
    fun triggerNow() {
        if (state != AlertState.COUNTDOWN) return
        deadline = now()
        tick()
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
            locationRequest?.cancel()
            val gps = fix?.takeIf { it.usable(time) }?.let { " ${locationText(it).replaceFirstChar(Char::uppercase)}" } ?: ""
            locationIncluded = gps.isNotEmpty()
            try {
                sender.send(incidentId, message + gps)
            } catch (_: RuntimeException) {
                state = AlertState.ERROR
            }
        }
    }
}

/** Eerste SMS-zin: dat er hulp nodig is en wat het alarm liet afgaan. */
fun alertMessage(reason: AlertReason, inactivityHours: Int? = null): String {
    val cause = when (reason) {
        AlertReason.SOS -> "SOS-knop ingedrukt"
        AlertReason.SPEECH_DETECTED -> "hulproep herkend"
        AlertReason.MISSED_CHECK_IN -> "dagelijkse bevestiging niet ontvangen"
        AlertReason.INACTIVITY -> inactivityHours?.let { "telefoon $it uur niet ontgrendeld" }
            ?: "telefoon lange tijd niet ontgrendeld"
        AlertReason.FALL -> "mogelijke val gedetecteerd"
    }
    return "SOS: ik heb mogelijk hulp nodig. Reden: $cause."
}

/** "locatie om 14:32: <kaartlink>"; een gewone https-link is in elke sms-app aan te tikken. */
internal fun locationText(fix: GpsFix, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(fix.timestampMillis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    val lat = String.format(Locale.ROOT, "%.5f", fix.latitude)
    val lon = String.format(Locale.ROOT, "%.5f", fix.longitude)
    return "locatie om $time: https://www.openstreetmap.org/?mlat=$lat&mlon=$lon&zoom=18"
}

internal fun locationSupplement(fix: GpsFix, zone: ZoneId = ZoneId.systemDefault()): String =
    "Aanvulling op alarm: ${locationText(fix, zone)}"