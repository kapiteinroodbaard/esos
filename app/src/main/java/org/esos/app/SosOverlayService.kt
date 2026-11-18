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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.IBinder
import android.provider.Settings
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat

internal data class SosBorderCorners(val topLeft: Float, val topRight: Float,
    val bottomRight: Float, val bottomLeft: Float)

// De rand volgt de afgeronde schermhoeken; de binnenrand krijgt een kleinere straal zodat de dikte overal gelijk blijft.
internal fun sosInnerCorners(outer: SosBorderCorners, thickness: Float): SosBorderCorners =
    SosBorderCorners((outer.topLeft - thickness).coerceAtLeast(0f), (outer.topRight - thickness).coerceAtLeast(0f),
        (outer.bottomRight - thickness).coerceAtLeast(0f), (outer.bottomLeft - thickness).coerceAtLeast(0f))

private class SosBorderView(context: Context, private val thickness: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        path.reset()
        if (w <= 0 || h <= 0) return
        val outer = screenCorners()
        val inner = sosInnerCorners(outer, thickness)
        path.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radii(outer), Path.Direction.CW)
        path.addRoundRect(RectF(thickness, thickness, w - thickness, h - thickness), radii(inner), Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    private fun screenCorners(): SosBorderCorners {
        // context.display werkt niet met de applicatiecontext; vraag het standaardscherm op via DisplayManager.
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        fun radius(position: Int) = display?.getRoundedCorner(position)?.radius?.toFloat() ?: 0f
        return SosBorderCorners(radius(RoundedCorner.POSITION_TOP_LEFT), radius(RoundedCorner.POSITION_TOP_RIGHT),
            radius(RoundedCorner.POSITION_BOTTOM_RIGHT), radius(RoundedCorner.POSITION_BOTTOM_LEFT))
    }

    private fun radii(corners: SosBorderCorners) = floatArrayOf(
        corners.topLeft, corners.topLeft, corners.topRight, corners.topRight,
        corners.bottomRight, corners.bottomRight, corners.bottomLeft, corners.bottomLeft)
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
        val thickness = 8 * context.resources.displayMetrics.density
        // Eén schermvullend venster, ook onder statusbalk, navigatiebalk en camera-uitsparing.
        val view = SosBorderView(context, thickness).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(bounds.width(), bounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
            // Maximaal 0.8, anders blokkeert Android aanrakingen door het overlayvenster heen.
            alpha = 0.8f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            setFitInsetsIgnoringVisibility(true)
        }
        manager.addView(view, params)
        windows.add(view)
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