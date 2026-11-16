package dev.toast.nothingwidgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class LyricsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = draw(ctx, ids)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: android.os.Bundle) = draw(ctx, intArrayOf(id))

    private fun draw(ctx: Context, ids: IntArray) {
        val svc = MediaListenerService.instance
        if (svc != null) { svc.redraw(); return }
        for (id in ids) {
            val (w, h) = Renderers.sizePx(ctx, id, 250, 180)
            Renderers.push(ctx, Renderers.lyrics(ctx, w, h, emptyList(), 0.0, "ALLOW ACCESS"), intArrayOf(id))
        }
    }
}
