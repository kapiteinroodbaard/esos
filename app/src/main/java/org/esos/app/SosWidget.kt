package org.esos.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

/** SOS-knop op het startscherm. Een tik opent het aftelscherm; na 10 s gaat de SOS vanzelf. */
open class SosWidget(private val layout: Int) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        manager.updateAppWidget(appWidgetIds, SosWidgets.views(context, layout))
    }
}

class SosWidgetSmall : SosWidget(R.layout.widget_sos_small)
class SosWidgetMedium : SosWidget(R.layout.widget_sos_medium)
class SosWidgetLarge : SosWidget(R.layout.widget_sos_large)

object SosWidgets {
    const val COUNTDOWN_MILLIS = 10_000L

    private val widgets = listOf(
        SosWidgetSmall::class.java to R.layout.widget_sos_small,
        SosWidgetMedium::class.java to R.layout.widget_sos_medium,
        SosWidgetLarge::class.java to R.layout.widget_sos_large
    )

    /** Werkt alle geplaatste widgets bij, bijvoorbeeld na een wijziging van instellingen of de SOS-status. */
    fun update(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app) ?: return
        widgets.forEach { (provider, layout) ->
            val ids = manager.getAppWidgetIds(ComponentName(app, provider))
            if (ids.isNotEmpty()) runCatching { manager.updateAppWidget(ids, views(app, layout)) }
        }
    }

    fun views(context: Context, layout: Int): RemoteViews {
        val settings = AppSettings(context)
        val views = RemoteViews(context.packageName, layout)
        views.setOnClickPendingIntent(R.id.sos_button, PendingIntent.getActivity(context, 15,
            CountdownActivity.widgetIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (layout == R.layout.widget_sos_small) return views

        val ready = (settings.smsEnabled && SmsAlertSender(context, settings).ready()) ||
            (settings.callEnabled && PhoneCallAction.canStart(context, settings))
        val (status, ok) = when {
            settings.sosModeActive -> "Hulp wordt gewaarschuwd" to false
            ready -> "✓ Beschermd" to true
            else -> "✕ Let op: open e-SOS" to false
        }
        views.setTextViewText(R.id.sos_status, status)
        views.setTextColor(R.id.sos_status, if (ok) 0xFF1B6E2E.toInt() else 0xFF9D2727.toInt())
        if (layout == R.layout.widget_sos_large) {
            val names = settings.contacts.map { it.name }
            views.setTextViewText(R.id.sos_contacts,
                if (names.isEmpty()) "Nog geen noodcontacten" else "Waarschuwt ${dutchList(names)}")
        }
        return views
    }
}
