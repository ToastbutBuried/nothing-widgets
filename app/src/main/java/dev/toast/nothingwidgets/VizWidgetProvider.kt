package dev.toast.nothingwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest

class VizWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = idle(ctx, ids)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: android.os.Bundle) {
        if (!VisualizerService.active) idle(ctx, intArrayOf(id))
    }

    private fun idle(ctx: Context, ids: IntArray) {
        for (id in ids) {
            val (w, h) = Renderers.sizePx(ctx, id, 180, 180)
            Renderers.push(ctx, Renderers.viz(w, h, FloatArray(8), Prefs.red(ctx), true), intArrayOf(id), toggleIntent(ctx))
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action != ACTION_TOGGLE) return
        if (VisualizerService.active) {
            ctx.startService(Intent(ctx, VisualizerService::class.java).setAction(VisualizerService.ACTION_STOP))
        } else if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            ctx.startForegroundService(Intent(ctx, VisualizerService::class.java))
        } else {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        const val ACTION_TOGGLE = "dev.toast.nothingwidgets.TOGGLE_VIZ"
        fun toggleIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, VizWidgetProvider::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE)
    }
}
