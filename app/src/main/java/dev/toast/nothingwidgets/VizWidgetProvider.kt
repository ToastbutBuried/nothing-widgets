package dev.toast.nothingwidgets

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint

class VizWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { if (!VisualizerService.active) drawIdle(ctx) }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: android.os.Bundle) {
        if (!VisualizerService.active) drawIdle(ctx)   // running service picks up the new size within a second
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

        /** stopped look: dark grid with a faint play triangle = "tap to start" */
        fun drawIdle(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            for (id in mgr.getAppWidgetIds(ComponentName(ctx, VizWidgetProvider::class.java))) {
                val (w, h, _) = Renderers.sizePx(ctx, id, 110, 110, VisualizerService.CAP_PX)
                val (cols, rows) = Renderers.grid(Prefs.bands(ctx), w, h)
                val bmp = Renderers.viz(Surface(), p, w, h, FloatArray(cols), null, cols, rows, VisualizerService.vizColor(ctx), true, Prefs.vizStyle(ctx))
                Renderers.push(ctx, bmp, intArrayOf(id)) { it.setOnClickPendingIntent(R.id.img, toggleIntent(ctx)) }
            }
        }
    }
}
