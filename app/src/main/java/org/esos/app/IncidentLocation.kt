package org.esos.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.LocationRequest as AndroidLocationRequest
import android.os.CancellationSignal

class AndroidIncidentLocation(private val context: Context, private val settings: AppSettings) : IncidentLocation {
    override fun request(onFix: (GpsFix) -> Unit): LocationRequest {
        val noop = object : LocationRequest { override fun cancel() {} }
        if (!settings.smsEnabled || !settings.shareLocation ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return noop
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return noop
        val signal = CancellationSignal()
        try {
            manager.getCurrentLocation(
                LocationManager.GPS_PROVIDER,
                AndroidLocationRequest.Builder(0L)
                    .setQuality(AndroidLocationRequest.QUALITY_HIGH_ACCURACY)
                    .setDurationMillis(30_000L).build(),
                signal, context.mainExecutor
            ) { result ->
                if (result != null && result.hasAccuracy()) {
                    onFix(GpsFix(result.latitude, result.longitude, result.accuracy, result.time))
                }
            }
        } catch (_: SecurityException) {
            return noop
        } catch (_: IllegalArgumentException) {
            return noop
        }
        return object : LocationRequest { override fun cancel() { signal.cancel() } }
    }
}