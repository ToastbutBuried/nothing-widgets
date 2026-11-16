package dev.toast.nothingwidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

class LyricsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = draw(ctx, ids)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, o: android.os.Bundle) {
        val s = MediaListenerService.instance
        if (s != null) s.sizeChanged(id) else draw(ctx, intArrayOf(id))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val a = intent.action ?: return
        if (a == ACT_PREV || a == ACT_PLAY || a == ACT_NEXT) MediaListenerService.instance?.transport(a)
    }

    companion object {
        const val ACT_PREV = "dev.toast.nothingwidgets.LYR_PREV"
        const val ACT_PLAY = "dev.toast.nothingwidgets.LYR_PLAY"
        const val ACT_NEXT = "dev.toast.nothingwidgets.LYR_NEXT"

        private fun bc(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, LyricsWidgetProvider::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

        /** Tap targets: whole widget opens the app; bottom-right zones are prev / play-pause / next. */
        fun wire(ctx: Context, rv: RemoteViews, controls: Boolean) {
            rv.setOnClickPendingIntent(R.id.img, PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            rv.setViewVisibility(R.id.controls, if (controls) View.VISIBLE else View.GONE)
            rv.setOnClickPendingIntent(R.id.btn_prev, bc(ctx, ACT_PREV, 1))
            rv.setOnClickPendingIntent(R.id.btn_play, bc(ctx, ACT_PLAY, 2))
            rv.setOnClickPendingIntent(R.id.btn_next, bc(ctx, ACT_NEXT, 3))
        }

        fun draw(ctx: Context, ids: IntArray) {
            MediaListenerService.ensureBound(ctx)
            val svc = MediaListenerService.instance
            if (svc != null) { svc.redraw(); return }
            val st = LyricsStyle.from(ctx, false)
            for (id in ids) {
                val (w, h, d) = Renderers.sizePx(ctx, id, 250, 180)
                val bmp = Renderers.lyrics(ctx, Surface(), w, h, d, emptyList(), 0.0, "ALLOW ACCESS", st = st)
                Renderers.push(ctx, bmp, intArrayOf(id), R.layout.widget_lyrics) { wire(ctx, it, st.controls) }
            }
        }
    }
}
