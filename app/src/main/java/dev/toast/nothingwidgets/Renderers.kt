package dev.toast.nothingwidgets

import android.content.Context
import android.graphics.*
import android.widget.RemoteViews

object Renderers {
    const val RED = 0xFFFF2D2D.toInt()
    const val WHITE = 0xFFF2F2F2.toInt()
    const val DIM = 0xFF5A5A5A.toInt()
    /** top dot of a column is brightest; steep trail below (measured from the reference widget) */
    private val BAR_FADE = floatArrayOf(1f, .76f, .46f, .24f, .14f)

    private fun tf(c: Context, res: Int) = c.resources.getFont(res)

    private fun wrap(p: Paint, text: String, maxW: Float): List<String> {
        val out = ArrayList<String>(); var cur = ""
        for (word in text.split(" ")) {
            val t = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(t) <= maxW || cur.isEmpty()) cur = t else { out.add(cur); cur = word }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    private fun ell(t: String, p: Paint, maxW: Float): String {
        if (p.measureText(t) <= maxW) return t
        var e = t
        while (e.isNotEmpty() && p.measureText("$e…") > maxW) e = e.dropLast(1)
        return "$e…"
    }

    /** w,h in px; d = px per dp. Everything is laid out in dp so a bigger widget gets more lines, not just bigger text. */
    fun lyrics(ctx: Context, w: Int, h: Int, d: Float, lines: List<LrcLine>, pos: Double, status: String?, prev: Int = -2, t: Float = 1f): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        cv.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 28 * d, 28 * d, card)
        val reg = tf(ctx, R.font.doto_regular); val bold = tf(ctx, R.font.doto_bold)
        val pad = 16 * d
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // header
        p.typeface = reg; p.textSize = 10 * d; p.letterSpacing = 0.15f
        val hy = pad + 8 * d
        p.color = RED; cv.drawCircle(pad + 3 * d, hy - 3.5f * d, 3 * d, p)
        p.color = DIM; cv.drawText("LYRICS", pad + 12 * d, hy, p)
        val m = (pos / 60).toInt(); val sec = (pos % 60).toInt()
        p.textAlign = Paint.Align.RIGHT; cv.drawText("$m:" + sec.toString().padStart(2, '0'), w - pad, hy, p); p.textAlign = Paint.Align.LEFT
        p.letterSpacing = 0f
        // dotted progress bar
        val end = if (lines.isEmpty()) 1.0 else lines.last().t + 4
        val frac = (pos / end).coerceIn(0.0, 1.0)
        val step = 7 * d; val n = ((w - 2 * pad) / step).toInt()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val by = h - pad - 2 * d
        for (k in 0 until n) {
            dot.color = if (k < frac * n) Color.WHITE else 0xFF444444.toInt()
            cv.drawCircle(pad + step * (k + .5f), by, 1.5f * d, dot)
        }
        // lyrics area
        val top = hy + 10 * d; val bottom = by - 12 * d
        val area = bottom - top; val maxW = w - 2 * pad
        val idx = LrcParser.index(lines, pos)
        if (lines.isEmpty()) {
            p.typeface = bold; p.textSize = 20 * d; p.color = WHITE
            cv.drawText(ell(status ?: "NO LYRICS", p, maxW), pad, (top + bottom) / 2 + 7 * d, p)
            return bmp
        }
        fun block(i: Int, dy: Float, alpha: Int) {
            val sc = cv.saveLayerAlpha(0f, top - 6 * d, w.toFloat(), bottom + 6 * d, alpha)
            cv.clipRect(0f, top - 6 * d, w.toFloat(), bottom + 6 * d)
            cv.translate(0f, dy)
            val cur = if (i >= 0) lines[i].s.ifEmpty { "♪" } else "♪"
            // largest current-line size that wraps into at most maxLines lines within ~60% of the area
            p.typeface = bold
            val maxLines = if (area > 150 * d) 4 else if (area > 90 * d) 3 else 2
            var size = minOf(30f, maxOf(14f, area / d * 0.16f))
            var wrapped: List<String>
            while (true) {
                p.textSize = size * d
                wrapped = wrap(p, cur, maxW - 14 * d)
                if ((wrapped.size <= maxLines && wrapped.size * size * 1.2f * d <= area * .7f) || size <= 11f) break
                size -= 1f
            }
            val lh = size * 1.2f * d
            val curH = wrapped.size * lh
            val y0 = top + (area - curH) / 2
            p.color = WHITE
            wrapped.forEachIndexed { k, t -> cv.drawText(if (k == wrapped.lastIndex || true) ell(t, p, maxW - 14 * d) else t, pad + 14 * d, y0 + lh * k + size * d, p) }
            val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
            cv.drawCircle(pad + 4 * d, y0 + size * d * 0.6f, 3.5f * d, red)
            // context lines, as many as fit
            val csize = maxOf(10f, size * 0.55f)
            p.typeface = reg; p.textSize = csize * d; p.color = DIM
            val cl = csize * 1.7f * d
            val fit = ((area - curH) / 2 / cl).toInt().coerceIn(0, 2)
            for (off in 1..fit) {
                val before = i - off; val after = i + off
                if (before in lines.indices) cv.drawText(ell(lines[before].s.ifEmpty { "♪" }, p, maxW), pad, y0 - cl * (off - 1) - csize * .5f * d, p)
                if (after in lines.indices) cv.drawText(ell(lines[after].s.ifEmpty { "♪" }, p, maxW), pad, y0 + curH + cl * (off - 1) + csize * 1.1f * d, p)
            }
            cv.restoreToCount(sc)
        }
        if (t < 1f && prev != idx && prev >= -1) {
            val e = 1f - (1f - t) * (1f - t) * (1f - t) // ease-out cubic
            val sh = 16 * d
            block(prev, -e * sh, ((1f - e) * 255).toInt())
            block(idx, (1f - e) * sh, (e * 255).toInt())
        } else block(idx, 0f, 255)
        return bmp
    }

    fun viz(w: Int, h: Int, levels: FloatArray, red: Boolean, idle: Boolean, bars: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (bars) 0xFF19191A.toInt() else 0xFF1B1B1B.toInt() }
        val r = minOf(w, h) * 0.12f
        cv.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, card)
        val cols = 8; val rows = 8
        val pad = minOf(w, h) * .1f
        val cw = (w - 2 * pad) / cols; val rh = (h - 2 * pad) / rows
        val rad = minOf(cw, rh) * .38f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (c in 0 until cols) {
            val lvl = if (idle) 0f else levels[c].coerceIn(0f, 1f)
            val pos = lvl * rows                       // continuous height in dots
            for (row in 0 until rows) {
                val dist = pos - (row + 1)             // >=0: fully inside the bar, in (-1,0): partial top dot
                val b = if (!bars) {
                    val n = Math.round(pos); val k = n - 1 - row
                    if (row < n) maxOf(.2f, 1 - k * .17f) else 0f
                } else if (dist >= 0f) {
                    val i0 = dist.toInt().coerceAtMost(BAR_FADE.size - 1); val i1 = minOf(i0 + 1, BAR_FADE.size - 1)
                    BAR_FADE[i0] + (BAR_FADE[i1] - BAR_FADE[i0]) * (dist - i0).coerceIn(0f, 1f)
                } else if (dist > -1f) (1 + dist) * BAR_FADE[0] else 0f
                p.color = if (b > 0.02f) {
                    val a = (b * 255).toInt()
                    if (red) Color.argb(a, 255, 45, 45) else Color.argb(a, 255, 255, 255)
                } else if (bars) 0xFF232325.toInt() else 0xFF262626.toInt()
                cv.drawCircle(pad + cw * (c + .5f), h - pad - rh * (row + .5f), rad, p)
            }
        }
        return bmp
    }

    fun push(ctx: Context, bmp: Bitmap, ids: IntArray, click: android.app.PendingIntent? = null) {
        val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
        for (id in ids) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_image)
            rv.setImageViewBitmap(R.id.img, bmp)
            if (click != null) rv.setOnClickPendingIntent(R.id.img, click)
            mgr.updateAppWidget(id, rv)
        }
    }

    /** Widget size in px plus px-per-dp, from its options. Capped so the bitmap stays small enough for RemoteViews. */
    fun sizePx(ctx: Context, id: Int, defW: Int, defH: Int): Triple<Int, Int, Float> {
        val o = android.appwidget.AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        val land = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val wDp = o.getInt(if (land) android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defW).coerceAtLeast(60)
        val hDp = o.getInt(if (land) android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, defH).coerceAtLeast(60)
        var d = ctx.resources.displayMetrics.density
        val cap = 1000f
        val big = maxOf(wDp * d, hDp * d)
        if (big > cap) d *= cap / big
        return Triple((wDp * d).toInt(), (hDp * d).toInt(), d)
    }
}
