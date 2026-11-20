package app.localdrop

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Static RemoteViews: no timer, clipboard listener, or background network work. */
class ReceiveWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val intent = Intent(context, ReceiveActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val click = PendingIntent.getActivity(context, 73, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.receive_widget)
            views.setOnClickPendingIntent(R.id.receive_widget_button, click)
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        fun pin(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(
                ComponentName(context, ReceiveWidget::class.java), null, null)
        }
    }
}
