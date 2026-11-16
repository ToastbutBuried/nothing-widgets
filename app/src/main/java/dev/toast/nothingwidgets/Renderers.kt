package dev.toast.nothingwidgets

import android.content.Context
import android.graphics.*
import android.widget.RemoteViews

object Renderers {
    const val RED = 0xFFFF2D2D.toInt()
    const val WHITE = 0xFFF2F2F2.toInt()
    const val DIM = 0xFF5A5A5A.toInt()

    private fun tf(c: Context, res: Int) = c.resources.getFont(res)

    fun lyrics(ctx: Context, w: Int, h: Int, lines: List<LrcLine>, pos: Double, status: String?): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val d = w / 380f * 1.6f
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        cv.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 28 * d, 28 * d, card)
        val reg = tf(ctx, R.font.doto_regular); val bold = tf(ctx, R.font.doto_bold)
        val pad = 18 * d
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // label + time
        p.typeface = reg; p.textSize = 11 * d; p.color = DIM; p.letterSpacing = 0.18f
        p.color = RED; cv.drawCircle(pad + 3 * d, pad + 5 * d, 3 * d, p)
        p.color = DIM; cv.drawText("LYRICS", pad + 12 * d, pad + 9 * d, p)
        val m = (pos / 60).toInt(); val s = (pos % 60).toInt()
        val tm = "$m:" + s.toString().padStart(2, '0')
        p.textAlign = Paint.Align.RIGHT; cv.drawText(tm, w - pad, pad + 9 * d, p); p.textAlign = Paint.Align.LEFT
        // lines
        val top = pad + 22 * d; val barH = 6 * d
        val bottom = h - pad - barH - 8 * d
        val midY = (top + bottom) / 2
        val i = LrcParser.index(lines, pos)
        p.letterSpacing = 0f
        if (lines.isEmpty()) {
            p.typeface = bold; p.textSize = 24 * d; p.color = WHITE
            cv.drawText(status ?: "NO LYRICS", pad, midY + 8 * d, p)
        } else {
            val gap = 8 * d
            fun ell(t: String, paint: Paint, maxW: Float): String {
                if (paint.measureText(t) <= maxW) return t
                var e = t
                while (e.isNotEmpty() && paint.measureText("$e…") > maxW) e = e.dropLast(1)
                return "$e…"
            }
            p.typeface = reg; p.textSize = 15 * d; p.color = DIM
            val sm = 15 * d + gap
            val maxW = w - 2 * pad
            for (off in intArrayOf(-2, -1, 1, 2)) {
                val k = i + off
                val txt = if (k in lines.indices) lines[k].s.ifEmpty { "♪" } else ""
                val y = midY + off * sm + (if (off < 0) -6 * d else 14 * d) + 5 * d
                cv.drawText(ell(txt, p, maxW), pad, y, p)
            }
            p.typeface = bold; p.textSize = 24 * d; p.color = WHITE
            val cur = if (i >= 0) lines[i].s.ifEmpty { "♪" } else "♪"
            val dotX = pad + 4 * d
            val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
            cv.drawCircle(dotX, midY, 4 * d, red)
            cv.drawText(ell(cur, p, maxW - 16 * d), pad + 16 * d, midY + 8 * d, p)
        }
        // dotted progress bar
        val end = if (lines.isEmpty()) 1.0 else lines.last().t + 4
        val frac = (pos / end).coerceIn(0.0, 1.0)
        val step = 8 * d; val n = ((w - 2 * pad) / step).toInt()
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val by = h - pad - barH / 2
        for (k in 0 until n) {
            dot.color = if (k < frac * n) Color.WHITE else 0xFF444444.toInt()
            cv.drawCircle(pad + step * (k + .5f), by, 1.8f * d, dot)
        }
        return bmp
    }

    fun viz(w: Int, h: Int, levels: FloatArray, red: Boolean, idle: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1B1B.toInt() }
        val r = minOf(w, h) * 0.12f
        cv.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, card)
        val cols = 8; val rows = 8
        val pad = minOf(w, h) * .1f
        val cw = (w - 2 * pad) / cols; val rh = (h - 2 * pad) / rows
        val rad = minOf(cw, rh) * .38f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (c in 0 until cols) {
            val n = if (idle) 0 else Math.round(levels[c] * rows)
            for (row in 0 until rows) {
                val on = row < n; val k = n - 1 - row
                val b = maxOf(.2f, 1 - k * .17f)
                p.color = if (on) {
                    val a = (b * 255).toInt()
                    if (red) Color.argb(a, 255, 45, 45) else Color.argb(a, 255, 255, 255)
                } else 0xFF262626.toInt()
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

    /** pixel size to render a widget at, from its options (dp -> px, capped for RemoteViews memory). */
    fun sizePx(ctx: Context, id: Int, defW: Int, defH: Int): Pair<Int, Int> {
        val o = android.appwidget.AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        val dm = ctx.resources.displayMetrics
        val wDp = o.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defW)
        val hDp = o.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, defH)
        var w = (wDp * dm.density).toInt().coerceAtLeast(200); var h = (hDp * dm.density).toInt().coerceAtLeast(200)
        val max = 900
        if (w > max || h > max) { val f = max / maxOf(w, h).toFloat(); w = (w * f).toInt(); h = (h * f).toInt() }
        return w to h
    }
}
