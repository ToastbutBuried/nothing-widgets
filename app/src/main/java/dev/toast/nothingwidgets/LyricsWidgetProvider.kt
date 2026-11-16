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
        if (a == ACT_SEEK) MediaListenerService.instance?.seekToLine(intent.getLongExtra(EXTRA_MS, -1), intent.getIntExtra(EXTRA_TRACK, 0))
    }

    companion object {
        const val ACT_PREV = "dev.toast.nothingwidgets.LYR_PREV"
        const val ACT_PLAY = "dev.toast.nothingwidgets.LYR_PLAY"
        const val ACT_NEXT = "dev.toast.nothingwidgets.LYR_NEXT"
        const val ACT_SEEK = "dev.toast.nothingwidgets.LYR_SEEK"
        private const val EXTRA_MS = "ms"
        private const val EXTRA_TRACK = "track"
        private val SEEK_IDS = intArrayOf(R.id.seek_0, R.id.seek_1, R.id.seek_2, R.id.seek_3, R.id.seek_4)

        /** One PendingIntent per (track, line start); kept so a redraw every frame doesn't re-create them. */
        private val seekPis = object : LinkedHashMap<Long, PendingIntent>(32, .75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, PendingIntent>?) = size > 48
        }
        private fun seekPi(ctx: Context, ms: Long, track: Int): PendingIntent = seekPis.getOrPut((track.toLong() shl 32) or (ms and 0xFFFFFFFFL)) {
            val i = Intent(ctx, LyricsWidgetProvider::class.java).setAction(ACT_SEEK)
                .setData(android.net.Uri.parse("nwseek://$track/$ms"))   // distinct data = distinct PendingIntent
                .putExtra(EXTRA_MS, ms).putExtra(EXTRA_TRACK, track)
            PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE)
        }

        private fun bc(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, LyricsWidgetProvider::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

        /** Tap targets: whole widget opens the app; bottom-right zones are prev / play-pause / next. */
        fun wire(ctx: Context, rv: RemoteViews, controls: Boolean, hits: List<LineHit> = emptyList(),
                 lines: List<LrcLine> = emptyList(), d: Float = 1f, track: Int = 0) {
            rv.setOnClickPendingIntent(R.id.img, PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            rv.setViewVisibility(R.id.controls, if (controls) View.VISIBLE else View.GONE)
            rv.setOnClickPendingIntent(R.id.btn_prev, bc(ctx, ACT_PREV, 1))
            rv.setOnClickPendingIntent(R.id.btn_play, bc(ctx, ACT_PLAY, 2))
            rv.setOnClickPendingIntent(R.id.btn_next, bc(ctx, ACT_NEXT, 3))
            // zones are placed with setViewLayoutMargin/Height, which RemoteViews only has from Android 12
            val canSeek = android.os.Build.VERSION.SDK_INT >= 31
            for ((k, vid) in SEEK_IDS.withIndex()) {
                val h = hits.getOrNull(k)
                val line = h?.let { lines.getOrNull(it.line) }
                if (!canSeek || h == null || line == null) { rv.setViewVisibility(vid, View.GONE); continue }
                rv.setViewVisibility(vid, View.VISIBLE)
                rv.setViewLayoutMargin(vid, RemoteViews.MARGIN_TOP, h.top / d, android.util.TypedValue.COMPLEX_UNIT_DIP)
                rv.setViewLayoutHeight(vid, (h.bottom - h.top) / d, android.util.TypedValue.COMPLEX_UNIT_DIP)
                rv.setOnClickPendingIntent(vid, seekPi(ctx, (line.t * 1000).toLong(), track))
            }
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
