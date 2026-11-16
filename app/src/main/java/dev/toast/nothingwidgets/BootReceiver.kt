package dev.toast.nothingwidgets

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** After a reboot or app update: draw both widgets again and ask Android to rebind the media listener. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val mgr = AppWidgetManager.getInstance(ctx)
        LyricsWidgetProvider.draw(ctx, mgr.getAppWidgetIds(ComponentName(ctx, LyricsWidgetProvider::class.java)))
        VizWidgetProvider.drawIdle(ctx)
    }
}
