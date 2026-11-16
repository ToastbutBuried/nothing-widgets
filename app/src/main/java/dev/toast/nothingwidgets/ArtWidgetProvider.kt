package dev.toast.nothingwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Album-art widget: cover (or lyrics without controls, after a swipe up). Swipes come from [GestureService]'s
 * overlay; without it, a tap still plays/pauses.
 */
class ArtWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = draw(ctx, ids)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: android.os.Bundle) {
        val s = MediaListenerService.instance
        if (s != null) s.sizeChanged(id) else draw(ctx, intArrayOf(id))
        GestureService.instance?.relocateSoon()
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACT_TAP) MediaListenerService.instance?.transport(LyricsWidgetProvider.ACT_PLAY)
    }

    companion object {
        const val ACT_TAP = "dev.toast.nothingwidgets.ART_TAP"

        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, ArtWidgetProvider::class.java))

        fun wire(ctx: Context, rv: RemoteViews, eq: Boolean = false) {
            rv.setViewVisibility(R.id.eq, if (eq) android.view.View.VISIBLE else android.view.View.GONE)
            rv.setOnClickPendingIntent(R.id.img, PendingIntent.getBroadcast(ctx, 0,
                Intent(ctx, ArtWidgetProvider::class.java).setAction(ACT_TAP), PendingIntent.FLAG_IMMUTABLE))
        }

        fun draw(ctx: Context, ids: IntArray) {
            MediaListenerService.ensureBound(ctx)
            val svc = MediaListenerService.instance
            if (svc != null) { svc.redraw(); return }
            for (id in ids) {
                val (w, h, d) = Renderers.sizePx(ctx, id, 160, 160)
                val bmp = Renderers.art(ctx, Surface(), w, h, d, null, "ALLOW ACCESS", "open the app", true, Prefs.lyrRed(ctx))
                Renderers.push(ctx, bmp, intArrayOf(id), R.layout.widget_art) { wire(ctx, it) }
            }
        }
    }
}
