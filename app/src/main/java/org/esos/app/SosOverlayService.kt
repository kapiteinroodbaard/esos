package org.esos.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat

internal data class SosBorderEdge(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun sosBorderEdges(width: Int, height: Int, thickness: Int): List<SosBorderEdge> {
    if (width <= 0 || height <= 0 || thickness <= 0) return emptyList()
    val border = thickness.coerceAtMost(minOf(width, height) / 2)
    if (border <= 0) return emptyList()
    return listOf(
        SosBorderEdge(0, 0, width, border),
        SosBorderEdge(0, height - border, width, border),
        SosBorderEdge(0, border, border, height - 2 * border),
        SosBorderEdge(width - border, border, border, height - 2 * border)
    ).filter { it.width > 0 && it.height > 0 }
}

object SosOverlay {
    private val windows = mutableListOf<View>()
    val visible: Boolean get() = windows.isNotEmpty()

    fun start(context: Context) {
        if (!AppSettings(context).sosModeActive || !Settings.canDrawOverlays(context)) return
        runCatching {
            show(context.applicationContext)
            ContextCompat.startForegroundService(context, Intent(context, SosOverlayService::class.java))
        }.onFailure {
            remove(context)
            android.util.Log.e("SosOverlay", "Unable to show SOS border", it)
        }
    }

    internal fun show(context: Context) {
        if (visible) return
        val manager = context.getSystemService(WindowManager::class.java)
        val bounds = manager.maximumWindowMetrics.bounds
        val thickness = (8 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        sosBorderEdges(bounds.width(), bounds.height(), thickness).forEach { edge ->
            val view = View(context).apply {
                setBackgroundColor(Color.RED)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val params = WindowManager.LayoutParams(edge.width, edge.height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = edge.left
                y = edge.top
                alpha = 0.8f
            }
            manager.addView(view, params)
            windows.add(view)
        }
    }

    internal fun remove(context: Context) {
        val manager = context.getSystemService(WindowManager::class.java)
        windows.forEach { view -> runCatching { manager.removeViewImmediate(view) } }
        windows.clear()
    }

    fun stop(context: Context) {
        remove(context)
        context.stopService(Intent(context, SosOverlayService::class.java))
    }
}

class SosOverlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sos_overlay", "SOS-schermrand",
            NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 233, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getBroadcast(this, 234, Intent(this, CancelAlertReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(233, Notification.Builder(this, "sos_overlay")
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("SOS-modus actief")
            .setContentText("Rode schermrand actief").setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Annuleer SOS", cancel).build()).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!AppSettings(this).sosModeActive || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { SosOverlay.show(this) }.onFailure { stopSelf() }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        SosOverlay.remove(this)
        if (AppSettings(this).sosModeActive && Settings.canDrawOverlays(this)) {
            runCatching { SosOverlay.show(this) }.onFailure { stopSelf() }
        } else stopSelf()
    }

    override fun onDestroy() {
        SosOverlay.remove(this)
        super.onDestroy()
    }
}