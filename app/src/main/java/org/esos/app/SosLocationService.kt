package org.esos.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Is het alarm zonder GPS-locatie verstuurd, dan blijft deze dienst zolang de SOS actief is naar een
 * bruikbare locatie zoeken, ook als GPS pas later aangaat. De eerste bruikbare locatie wordt eenmalig
 * nagestuurd; daarna stopt de dienst.
 */
class SosLocationService : Service(), LocationListener {
    private var listening = false
    private var receiverRegistered = false
    // Een aanvraag van toen locatie uit stond, wordt na het aanzetten niet vanzelf actief: opnieuw aanvragen.
    private val locationSwitch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (listening) {
                getSystemService(LocationManager::class.java).removeUpdates(this@SosLocationService)
                listening = false
            }
            listen()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AppSettings(this).sosModeActive || !pending(this) || !allowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, AlertRuntime.ACTIVE_SOS_NOTIFICATION_ID,
                AlertRuntime.activeSosNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            if (!receiverRegistered) {
                registerReceiver(locationSwitch, IntentFilter().apply {
                    addAction(LocationManager.MODE_CHANGED_ACTION)
                    addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                }, Context.RECEIVER_NOT_EXPORTED)
                receiverRegistered = true
            }
            listen()
        } catch (e: RuntimeException) {
            android.util.Log.e("SosLocationService", "Kan niet naar locatie zoeken", e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // onStartCommand controleert de locatietoestemmingen via allowed()
    private fun listen() {
        if (listening || !allowed(this)) return
        getSystemService(LocationManager::class.java).requestLocationUpdates(
            LocationManager.GPS_PROVIDER, UPDATE_MILLIS, 0f, this, mainLooper)
        listening = true
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasAccuracy()) return
        val fix = GpsFix(location.latitude, location.longitude, location.accuracy, location.time)
        if (!fix.usable(System.currentTimeMillis()) || !pending(this)) return
        val settings = AppSettings(this)
        if (!settings.sosModeActive) {
            stopSelf()
            return
        }
        val prefs = getSharedPreferences(AlertRuntime.PROGRESS, Context.MODE_PRIVATE)
        val sent = runCatching {
            SmsAlertSender(this, settings).send(prefs.getLong(INCIDENT, System.currentTimeMillis()),
                locationSupplement(fix))
        }.isSuccess
        prefs.edit().remove(INCIDENT)
            .putString(AlertRuntime.LOCATION_STATUS,
                if (sent) "Locatie nagestuurd." else "Locatie nasturen is mislukt.")
            .apply()
        stopSelf()
    }

    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(locationSwitch)
        if (listening) getSystemService(LocationManager::class.java).removeUpdates(this)
        // Tijdens SOS blijft de SOS-melding staan; na annuleren verdwijnt hij met de dienst.
        ServiceCompat.stopForeground(this, if (AppSettings(this).sosModeActive)
            ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val INCIDENT = "location_incident"
        private const val UPDATE_MILLIS = 10_000L

        /** Start het zoeken als het alarm zonder locatie verstuurd is en locatie delen aan staat. */
        fun startSearching(context: Context, incidentId: Long) {
            val settings = AppSettings(context)
            if (!settings.smsEnabled || !settings.shareLocation || !allowed(context)) return
            context.getSharedPreferences(AlertRuntime.PROGRESS, Context.MODE_PRIVATE).edit()
                .putLong(INCIDENT, incidentId)
                .putString(AlertRuntime.LOCATION_STATUS, "Locatie wordt nog gezocht.")
                .commit()
            resume(context)
        }

        /** Hervat het zoeken na een herstart van de app of de telefoon. */
        fun resume(context: Context) {
            if (!pending(context) || !AppSettings(context).sosModeActive) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, SosLocationService::class.java)) }
                .onFailure { android.util.Log.e("SosLocationService", "Kan zoeken niet starten", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SosLocationService::class.java))
        }

        private fun pending(context: Context) =
            context.getSharedPreferences(AlertRuntime.PROGRESS, Context.MODE_PRIVATE).contains(INCIDENT)

        private fun allowed(context: Context) =
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
