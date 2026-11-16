package dev.toast.nothingwidgets

import android.graphics.Paint

/** Live demo of the grid using the current style, colour, sensitivity, band count and peaks. */
class PreviewView(ctx: android.content.Context) : android.view.View(ctx) {
    private var cava = Cava(8)
    private var raw = FloatArray(8)
    private var last = android.os.SystemClock.elapsedRealtime()
    private var nextBeat = 0L
    private val rnd = java.util.Random()
    private val surf = Surface()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: android.graphics.Canvas) {
        val now = android.os.SystemClock.elapsedRealtime()
        val dt = ((now - last) / 1000f).coerceIn(0.005f, 0.2f); last = now
        val bands = Prefs.bands(context)
        // preview is shown wide when bands > 8, square otherwise
        val h = height; val w = if (bands > 8) width else minOf(width, height)
        val (cols, rows) = Renderers.grid(bands, w, h)
        if (cava.cols != cols) { cava = Cava(cols); raw = FloatArray(cols) }
        if (now >= nextBeat) { // fake music: beats with random band energy
            for (i in 0 until cols) raw[i] = (rnd.nextFloat() * (1f - i * .5f / cols)).coerceIn(0f, 1f) * 0.8f
            nextBeat = now + 160 + rnd.nextInt(260)
        }
        for (i in 0 until cols) raw[i] *= 0.9f
        val bars = Prefs.bars(context)
        val g = Prefs.gain(context) / 1.5f
        val lv = if (bars) cava.process(raw, dt, g) else FloatArray(cols) { (raw[it] * g).coerceIn(0f, 1f) }
        val bmp = Renderers.viz(surf, paint, w, h, lv, if (Prefs.peaks(context)) cava.caps else null, cols, rows, Prefs.red(context), false, bars)
        c.drawBitmap(bmp, (width - w) / 2f, 0f, null)
        postInvalidateOnAnimation()
    }
}
