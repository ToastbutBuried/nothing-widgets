package dev.toast.nothingwidgets

import android.content.Context
import android.graphics.*
import android.widget.RemoteViews

/** A reusable bitmap + canvas. One per widget/size and per thread, so frames don't allocate. */
class Surface {
    var bmp: Bitmap? = null; private set
    val cv = Canvas()
    fun begin(w: Int, h: Int, clear: Boolean = true): Canvas {
        val b = bmp
        if (b == null || b.width != w || b.height != h) { bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); cv.setBitmap(bmp) }
        else if (clear) b.eraseColor(Color.TRANSPARENT)
        return cv
    }

    // visualizer cache key (shape the bitmap was last painted for)
    internal var vizKey = ""
    /** per-column signature of the last frame, so unchanged columns are not redrawn */
    internal var colSig = IntArray(0)
    /** false when the last viz() call produced exactly the previous frame (caller can skip pushing it) */
    var changed = true; internal set
}

data class LyricsStyle(val size: Int = 1, val red: Boolean = true, val context: Boolean = true, val controls: Boolean = true,
                       val playing: Boolean = false, val seek: Boolean = true, val radius: Float = 28f, val accent: Int = Renderers.RED) {
    companion object {
        fun from(c: Context, playing: Boolean): LyricsStyle {
            val red = Prefs.lyrRed(c)
            return LyricsStyle(Prefs.lyrSize(c), red, Prefs.lyrContext(c), Prefs.lyrControls(c), playing, Prefs.lyrSeek(c),
                accent = if (red) Accent.current(c) else Renderers.WHITE)
        }
    }
}

/** Where a lyric line was drawn (px, top to bottom), so the widget can put a tap-to-seek zone over it. */
class LineHit(val line: Int, var top: Float, var bottom: Float)

object Renderers {
    const val RED = 0xFFFF2D2D.toInt()
    const val WHITE = 0xFFF2F2F2.toInt()
    const val DIM = 0xFF5A5A5A.toInt()
    private const val UNSUNG = 0xFF6E6E6E.toInt()
    private const val SUB = 0xFFB4B4B4.toInt()
    /** top dot of a column is brightest; steep trail below (measured from the reference widget) */
    private val BAR_FADE = floatArrayOf(1f, .76f, .46f, .24f, .14f)

    // control zones on the lyrics widget (dp) - must match res/layout/widget_lyrics.xml
    const val CTL_ZONE_DP = 34f
    const val CTL_STRIP_DP = 40f
    const val CTL_END_DP = 8f

    private var reg: Typeface? = null
    private var bold: Typeface? = null
    private fun fonts(c: Context) { if (reg == null) { reg = c.resources.getFont(R.font.doto_regular); bold = c.resources.getFont(R.font.doto_bold) } }

    // lyrics paints: only touched on the main thread
    private val lp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lfill = Paint(Paint.ANTI_ALIAS_FLAG)

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
        val n = p.breakText(t, true, maxW - p.measureText("…"), null)
        return t.substring(0, n.coerceAtLeast(0)) + "…"
    }

    // 5x5 dot glyphs for the widget controls
    internal val G_PREV = arrayOf("x...x", "x..xx", "x.xxx", "x..xx", "x...x")
    internal val G_NEXT = arrayOf("x...x", "xx..x", "xxx.x", "xx..x", "x...x")
    internal val G_PLAY = arrayOf("x....", "xxx..", "xxxxx", "xxx..", "x....")
    internal val G_PAUSE = arrayOf("xx.xx", "xx.xx", "xx.xx", "xx.xx", "xx.xx")
    internal fun glyph(cv: Canvas, g: Array<String>, cx: Float, cy: Float, d: Float, p: Paint) {
        val s = 3.2f * d
        for (r in 0 until 5) for (c in 0 until 5) if (g[r][c] == 'x')
            cv.drawCircle(cx + (c - 2) * s, cy + (r - 2) * s, 1.15f * d, p)
    }

    /**
     * w,h in px; d = px per dp. Laid out in dp, so a bigger widget gets more lines, not just bigger text.
     * prev/t drive the line-change slide (t 0..1), [pos] already includes the per-song latency.
     */
    fun lyrics(ctx: Context, surf: Surface, w: Int, h: Int, d: Float, lines: List<LrcLine>, pos: Double, status: String?,
               prev: Int = -2, t: Float = 1f, st: LyricsStyle = LyricsStyle(), hits: MutableList<LineHit>? = null,
               subs: List<String>? = null): Bitmap {
        hits?.clear()
        fonts(ctx)
        val cv = surf.begin(w, h)
        val accent = st.accent
        val p = lp
        p.reset(); p.isAntiAlias = true
        p.color = Color.BLACK
        cv.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), st.radius * d, st.radius * d, p)
        val pad = 16 * d
        // header
        p.typeface = reg; p.textSize = 10 * d; p.letterSpacing = 0.15f
        val hy = pad + 8 * d
        p.color = accent; cv.drawCircle(pad + 3 * d, hy - 3.5f * d, 3 * d, p)
        p.color = DIM; cv.drawText(if (status == "PLAIN") "LYRICS · PLAIN" else "LYRICS", pad + 12 * d, hy, p)
        val m = (pos.coerceAtLeast(0.0) / 60).toInt(); val sec = (pos.coerceAtLeast(0.0) % 60).toInt()
        p.textAlign = Paint.Align.RIGHT; cv.drawText("$m:" + sec.toString().padStart(2, '0'), w - pad, hy, p); p.textAlign = Paint.Align.LEFT
        p.letterSpacing = 0f
        // bottom row: dotted progress bar, plus controls on the right
        val ctlW = if (st.controls) (CTL_ZONE_DP * 3 + CTL_END_DP - 4) * d else 0f
        val by = h - pad - 2 * d
        val end = if (lines.isEmpty()) 1.0 else lines.last().t + 4
        val frac = (pos / end).coerceIn(0.0, 1.0)
        val step = 7 * d; val n = ((w - 2 * pad - ctlW) / step).toInt().coerceAtLeast(0)
        for (k in 0 until n) {
            p.color = if (k < frac * n) WHITE else 0xFF444444.toInt()
            cv.drawCircle(pad + step * (k + .5f), by, 1.5f * d, p)
        }
        if (st.controls) {
            p.color = 0xFFB0B0B0.toInt()
            val cy = h - CTL_STRIP_DP / 2 * d
            val x0 = w - (CTL_END_DP + CTL_ZONE_DP * 3) * d
            glyph(cv, G_PREV, x0 + CTL_ZONE_DP * .5f * d, cy, d, p)
            glyph(cv, if (st.playing) G_PAUSE else G_PLAY, x0 + CTL_ZONE_DP * 1.5f * d, cy, d, p)
            glyph(cv, G_NEXT, x0 + CTL_ZONE_DP * 2.5f * d, cy, d, p)
        }
        // lyrics area
        val top = hy + 10 * d; val bottom = minOf(by, h - CTL_STRIP_DP * d) - 10 * d
        val area = bottom - top; val maxW = w - 2 * pad
        val idx = LrcParser.index(lines, pos)
        if (lines.isEmpty()) {
            p.typeface = bold; p.textSize = 20 * d; p.color = WHITE
            cv.drawText(ell(status ?: "NO LYRICS", p, maxW), pad, (top + bottom) / 2 + 7 * d, p)
            return surf.bmp!!
        }
        val scale = floatArrayOf(.8f, 1f, 1.22f)[st.size.coerceIn(0, 2)]
        fun block(i: Int, dy: Float, alpha: Int, live: Boolean) {
            val sc = cv.saveLayerAlpha(0f, top - 6 * d, w.toFloat(), bottom + 6 * d, alpha)
            cv.clipRect(0f, top - 6 * d, w.toFloat(), bottom + 6 * d)
            cv.translate(0f, dy)
            val line = lines.getOrNull(i)
            val cur = line?.s?.ifEmpty { "♪" } ?: "♪"
            p.typeface = bold
            val maxLines = if (area > 150 * d) 4 else if (area > 90 * d) 3 else 2
            var size = minOf(30f * scale, maxOf(14f * scale, area / d * 0.2f * scale))
            var wrapped: List<String>
            val textW = maxW - 14 * d
            // translation / romanization under the line: smaller, grey, one row each (max 2)
            val sub = subs?.getOrNull(i)?.takeIf { it.isNotBlank() }?.split("\n")?.take(2) ?: emptyList()
            fun subSize(sz: Float) = maxOf(10f, sz * 0.5f)
            fun subH(sz: Float) = if (sub.isEmpty()) 0f else 4 * d + sub.size * subSize(sz) * 1.35f * d
            while (true) {
                p.textSize = size * d
                wrapped = wrap(p, cur, textW)
                if ((wrapped.size <= maxLines && wrapped.size * size * 1.2f * d + subH(size) <= area * .72f) || size <= 11f) break
                size -= 1f
            }
            p.textSize = size * d
            val lh = size * 1.2f * d
            val curH = wrapped.size * lh + subH(size)
            val y0 = top + (area - curH) / 2
            // karaoke: chars sung so far (whole line when the line has no word timings)
            val karaoke = live && line != null && line.words.isNotEmpty()
            val sung = if (karaoke) LrcParser.sungChars(line!!, lines.getOrNull(i + 1), pos) else Float.MAX_VALUE
            var charStart = 0
            wrapped.forEachIndexed { k, seg ->
                val txt = ell(seg, p, textW)
                val x = pad + 14 * d; val y = y0 + lh * k + size * d
                if (!karaoke) { p.color = WHITE; cv.drawText(txt, x, y, p) }
                else {
                    p.color = UNSUNG; cv.drawText(txt, x, y, p)
                    val inSeg = (sung - charStart).coerceIn(0f, txt.length.toFloat())
                    if (inSeg > 0f) {
                        val whole = inSeg.toInt()
                        var fillW = p.measureText(txt, 0, whole)
                        if (whole < txt.length) fillW += p.measureText(txt, whole, whole + 1) * (inSeg - whole)
                        cv.save(); cv.clipRect(x, y - size * d * 1.2f, x + fillW, y + size * d * .4f)
                        lfill.set(p); lfill.color = WHITE
                        cv.drawText(txt, x, y, lfill); cv.restore()
                    }
                }
                charStart += seg.length + 1
            }
            if (sub.isNotEmpty()) {
                val ss = subSize(size)
                p.typeface = reg; p.textSize = ss * d; p.color = SUB
                var sy = y0 + wrapped.size * lh + 4 * d
                for (l in sub) { sy += ss * 1.35f * d; cv.drawText(ell(l, p, textW), pad + 14 * d, sy - ss * .3f * d, p) }
                p.typeface = bold; p.textSize = size * d
            }
            p.color = accent
            cv.drawCircle(pad + 4 * d, y0 + size * d * 0.6f, 3.5f * d, p)
            val rec = if (live) hits else null
            rec?.add(LineHit(i, y0 - 2 * d, y0 + curH + 2 * d))
            if (st.context) {
                val csize = maxOf(10f, size * 0.55f)
                p.typeface = reg; p.textSize = csize * d; p.color = DIM
                val cl = csize * 1.7f * d
                val fit = ((area - curH) / 2 / cl).toInt().coerceIn(0, 2)
                for (off in 1..fit) {
                    val before = i - off; val after = i + off
                    val yb = y0 - cl * (off - 1) - csize * .5f * d
                    val ya = y0 + curH + cl * (off - 1) + csize * 1.1f * d
                    if (before in lines.indices) {
                        cv.drawText(ell(lines[before].s.ifEmpty { "♪" }, p, maxW), pad, yb, p)
                        rec?.add(LineHit(before, yb - csize * 1.2f * d, yb + csize * .5f * d))
                    }
                    if (after in lines.indices) {
                        cv.drawText(ell(lines[after].s.ifEmpty { "♪" }, p, maxW), pad, ya, p)
                        rec?.add(LineHit(after, ya - csize * 1.2f * d, ya + csize * .5f * d))
                    }
                }
            }
            cv.restoreToCount(sc)
        }
        if (t < 1f && prev != idx && prev >= -1) {
            val e = 1f - (1f - t) * (1f - t) * (1f - t) // ease-out cubic
            val sh = 16 * d
            block(prev, -e * sh, ((1f - e) * 255).toInt(), false)
            block(idx, (1f - e) * sh, (e * 255).toInt(), true)
        } else block(idx, 0f, 255, true)
        if (hits != null && hits.isNotEmpty()) {
            // close the gaps so the zones tile the lyrics area: easier to hit than the bare text rows
            hits.sortBy { it.top }
            for (k in 1 until hits.size) { val m = (hits[k - 1].bottom + hits[k].top) / 2; hits[k - 1].bottom = m; hits[k].top = m }
            hits.first().top = top - 6 * d; hits.last().bottom = bottom + 6 * d
        }
        return surf.bmp!!
    }

    private val ap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private const val PROG_PAD_DP = 14f
    /** dots in the cover's progress line: one per ~6 dp; the widget only redraws when a dot fills */
    fun progressDots(w: Int, d: Float) = ((w - 2 * PROG_PAD_DP * d) / (6 * d)).toInt().coerceAtLeast(8)
    private val artSrc = Rect()
    private val artDst = RectF()
    private val artClip = Path()

    /**
     * Artwork widget, art side: the cover centre-cropped to a rounded card. Paused = dimmed with a dot ▶.
     * No cover: title/artist in Doto on black. The playing "eq" is not drawn here: it's an animated view in
     * widget_art.xml the launcher runs. Main thread only.
     */
    fun art(ctx: Context, surf: Surface, w: Int, h: Int, d: Float, art: Bitmap?, title: String, artist: String, playing: Boolean, red: Boolean,
            radiusDp: Float = Prefs.artRadius(ctx).toFloat(), progress: Float = -1f, accent: Int = RED): Bitmap {
        fonts(ctx)
        val cv = surf.begin(w, h)
        val p = ap
        p.reset(); p.isAntiAlias = true; p.isFilterBitmap = true
        val r = radiusDp * d
        artClip.rewind(); artClip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, Path.Direction.CW)
        cv.save(); cv.clipPath(artClip)
        cv.drawColor(Color.BLACK)
        if (art != null && !art.isRecycled) {
            // centre-crop
            val s = maxOf(w / art.width.toFloat(), h / art.height.toFloat())
            val sw = (w / s).toInt().coerceAtMost(art.width); val sh = (h / s).toInt().coerceAtMost(art.height)
            artSrc.set((art.width - sw) / 2, (art.height - sh) / 2, (art.width + sw) / 2, (art.height + sh) / 2)
            artDst.set(0f, 0f, w.toFloat(), h.toFloat())
            cv.drawBitmap(art, artSrc, artDst, p)
        } else {
            val pad = 16 * d
            p.color = if (red) accent else WHITE; cv.drawCircle(pad + 3 * d, pad + 4.5f * d, 3 * d, p)
            p.typeface = bold; p.color = WHITE; p.textSize = 22 * d
            val tl = wrap(p, title.ifEmpty { "NO MUSIC" }, w - 2 * pad).take(3)
            var y = h - pad - (if (artist.isNotEmpty()) 18 * d else 0f) - (tl.size - 1) * 24 * d
            for (l in tl) { cv.drawText(ell(l, p, w - 2 * pad), pad, y, p); y += 24 * d }
            if (artist.isNotEmpty()) {
                p.typeface = reg; p.textSize = 12 * d; p.color = DIM
                cv.drawText(ell(artist, p, w - 2 * pad), pad, h - pad, p)
            }
        }
        if (progress >= 0f && title.isNotEmpty()) {
            // dotted progress line on a soft bottom scrim, under the eq (which sits 17 dp up)
            val sh = 26 * d
            p.shader = LinearGradient(0f, h - sh, 0f, h.toFloat(), 0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
            cv.drawRect(0f, h - sh, w.toFloat(), h.toFloat(), p); p.shader = null
            val n = progressDots(w, d)
            val step = (w - 2 * PROG_PAD_DP * d) / n; val y = h - 8 * d
            val done = (progress.coerceIn(0f, 1f) * n).toInt()
            for (k in 0 until n) {
                p.color = when { k < done -> WHITE; k == done -> accent; else -> 0x59FFFFFF }
                cv.drawCircle(PROG_PAD_DP * d + step * (k + .5f), y, (if (k == done) 1.8f else 1.3f) * d, p)
            }
        }
        if (title.isNotEmpty() && !playing) {
            cv.drawColor(0x8C000000.toInt())
            p.color = WHITE
            glyph(cv, G_PLAY, w / 2f + 2 * d, h / 2f, d * 2.2f, p)
        }
        cv.restore()
        return surf.bmp!!
    }

    /**
     * Grid of rows x cols round dots. levels/caps 0..1 per column. Paint passed in so each thread has its own.
     * Styles (Prefs.VIZ_*): dots, bars (with peak caps), mirror (bars growing both ways from the middle), wave (one lit
     * dot per column with a soft trail), ring (spokes around a circle, see [ring]).
     * Fast path: the bitmap is reused and only columns whose dots changed since the last frame are repainted
     * (measured: plain anti-aliased circles beat bitmap-sprite blits on this phone).
     */
    fun viz(surf: Surface, p: Paint, w: Int, h: Int, levels: FloatArray, caps: FloatArray?, cols: Int, rows: Int,
            lit: Int, idle: Boolean, style: Int): Bitmap {
        if (style == Prefs.VIZ_RING && !idle) return ring(surf, p, w, h, levels, cols, lit)
        val bars = style != Prefs.VIZ_DOTS
        val pad = minOf(w, h) * .1f
        val cell = minOf((w - 2 * pad) / cols, (h - 2 * pad) / rows)
        val ox = (w - cell * cols) / 2; val oy = (h - cell * rows) / 2
        val rad = cell * .38f
        val cardColor = if (bars) 0xFF19191A.toInt() else 0xFF1B1B1B.toInt()
        val offColor = if (bars) 0xFF232325.toInt() else 0xFF262626.toInt()
        val key = "$w,$h,$cols,$rows,$style"
        val fresh = surf.vizKey != key || surf.bmp == null || surf.bmp!!.width != w || surf.bmp!!.height != h
        val cv = surf.begin(w, h, clear = fresh)
        p.reset(); p.isAntiAlias = true
        if (fresh) {
            p.color = cardColor
            val r = minOf(w, h) * 0.12f
            cv.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, p)
            surf.colSig = IntArray(cols) { Int.MIN_VALUE }; surf.vizKey = key
        }
        val capColor = if (lit != WHITE && lit != Color.WHITE) WHITE else RED
        val vals = IntArray(rows)              // per row: 0 = off, else alpha | (1 shl 9 when cap colour)
        var any = false
        for (c in 0 until cols) {
            val lvl = if (idle) 0f else levels.getOrElse(c) { 0f }.coerceIn(0f, 1f)
            val pos = lvl * rows
            val capRow = if (!idle && caps != null && style == Prefs.VIZ_BARS) (caps.getOrElse(c) { 0f } * rows).toInt().coerceAtMost(rows - 1) else -1
            var sig = 17
            for (row in 0 until rows) {
                val v = when {
                    row == capRow && row >= pos.toInt() && capRow > 0 -> 255 or (1 shl 9)
                    idle -> if (playHint(c, row, cols, rows)) 82 else 0
                    else -> {
                        val b = when (style) {
                            Prefs.VIZ_DOTS -> { val n = Math.round(pos); val k = n - 1 - row; if (row < n) maxOf(.2f, 1 - k * .17f) else 0f }
                            Prefs.VIZ_MIRROR -> {
                                // distance of this row from the middle, level spreads both ways
                                val half = rows / 2f; val dc = Math.abs(row + .5f - half)
                                barFade(lvl * half - dc - .5f)
                            }
                            Prefs.VIZ_WAVE -> {
                                val at = lvl * (rows - 1); val dd = Math.abs(row - at)
                                if (lvl < 0.02f) 0f else if (dd < 1f) 1f - dd * .55f else if (dd < 2f) (2f - dd) * .3f else 0f
                            }
                            else -> barFade(pos - (row + 1))
                        }
                        if (b > 0.02f) ((b * 255).toInt() and 0xF8).coerceAtLeast(8) else 0   // quantised: tiny changes don't redraw
                    }
                }
                vals[row] = v; sig = sig * 31 + v
            }
            sig = sig * 31 + lit
            if (sig == surf.colSig[c]) continue
            surf.colSig[c] = sig; any = true
            // repaint this column: card colour over the grid cells (inside the rounded corners), then its dots
            val x0 = ox + cell * c
            p.color = cardColor
            cv.drawRect(x0, oy, x0 + cell, oy + cell * rows, p)
            val cx = x0 + cell * .5f
            for (row in 0 until rows) {
                val v = vals[row]
                p.color = when {
                    v == 0 -> offColor
                    v and (1 shl 9) != 0 -> capColor
                    else -> Color.argb(v and 0xFF, Color.red(lit), Color.green(lit), Color.blue(lit))
                }
                cv.drawCircle(cx, oy + cell * (rows - row - .5f), rad, p)
            }
        }
        surf.changed = any || fresh
        return surf.bmp!!
    }

    /** bars brightness for a dot [dist] rows below the top of the bar (negative = above it) */
    private fun barFade(dist: Float): Float = if (dist >= 0f) {
        val i0 = dist.toInt().coerceAtMost(BAR_FADE.size - 1); val i1 = minOf(i0 + 1, BAR_FADE.size - 1)
        BAR_FADE[i0] + (BAR_FADE[i1] - BAR_FADE[i0]) * (dist - i0).coerceIn(0f, 1f)
    } else if (dist > -1f) (1 + dist) * BAR_FADE[0] else 0f

    /** Ring: 2 x cols spokes (bands mirrored left/right) of dots growing outward from a circle. Full redraw per frame. */
    private fun ring(surf: Surface, p: Paint, w: Int, h: Int, levels: FloatArray, cols: Int, lit: Int): Bitmap {
        val cv = surf.begin(w, h)
        surf.vizKey = ""   // grid styles must start fresh after this
        p.reset(); p.isAntiAlias = true
        p.color = 0xFF19191A.toInt()
        val r = minOf(w, h) * 0.12f
        cv.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, p)
        val cx = w / 2f; val cy = h / 2f; val big = minOf(w, h) / 2f
        val inner = big * .34f; val steps = 5; val stepLen = (big * .9f - inner) / steps
        val spokes = cols * 2
        val dot = minOf(stepLen * .32f, (2 * Math.PI * inner / spokes).toFloat() * .32f)
        for (k in 0 until spokes) {
            // band index mirrored so bass sits at the top and both halves match
            val band = if (k < cols) k else spokes - 1 - k
            val lvl = levels.getOrElse(band) { 0f }.coerceIn(0f, 1f)
            val a = Math.PI * 2 * (k + .5) / spokes - Math.PI / 2
            val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
            for (sIdx in 0 until steps) {
                val b = barFade(lvl * steps - (sIdx + 1))
                p.color = if (b > 0.02f) Color.argb((b * 255).toInt(), Color.red(lit), Color.green(lit), Color.blue(lit)) else 0xFF232325.toInt()
                val rr = inner + stepLen * (sIdx + .5f)
                cv.drawCircle(cx + dx * rr, cy + dy * rr, dot, p)
            }
        }
        surf.changed = true
        return surf.bmp!!
    }

    /** a faint "play" triangle drawn with the grid dots while the visualizer is stopped */
    private fun playHint(c: Int, row: Int, cols: Int, rows: Int): Boolean {
        val u = (c + .5f) / cols; val v = (row + .5f) / rows
        return u in 0.28f..0.74f && Math.abs(v - .5f) <= 0.27f * (0.74f - u) / 0.46f
    }

    fun push(ctx: Context, bmp: Bitmap, ids: IntArray, layout: Int = R.layout.widget_image, setup: ((RemoteViews) -> Unit)? = null) {
        val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
        val rv = RemoteViews(ctx.packageName, layout)
        rv.setImageViewBitmap(R.id.img, bmp)
        setup?.invoke(rv)
        for (id in ids) mgr.updateAppWidget(id, rv)
    }

    /** Frame update: only swaps the bitmap on the already-inflated widget (no re-inflate in the launcher). */
    fun pushFrame(ctx: Context, bmp: Bitmap, ids: IntArray) {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_image)
        rv.setImageViewBitmap(R.id.img, bmp)
        android.appwidget.AppWidgetManager.getInstance(ctx).partiallyUpdateAppWidget(ids, rv)
    }

    /** Widget size in px plus px-per-dp, from its options. Capped so the bitmap stays small enough for RemoteViews. */
    fun sizePx(ctx: Context, id: Int, defW: Int, defH: Int, cap: Float = 1000f): Triple<Int, Int, Float> {
        val o = android.appwidget.AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
        val land = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val wDp = o.getInt(if (land) android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defW).coerceAtLeast(60)
        val hDp = o.getInt(if (land) android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, defH).coerceAtLeast(60)
        var d = ctx.resources.displayMetrics.density
        val big = maxOf(wDp * d, hDp * d)
        if (big > cap) d *= cap / big
        return Triple((wDp * d).toInt(), (hDp * d).toInt(), d)
    }

    fun grid(bandsPref: Int, w: Int, h: Int) = Grid.of(bandsPref, w, h)
}
