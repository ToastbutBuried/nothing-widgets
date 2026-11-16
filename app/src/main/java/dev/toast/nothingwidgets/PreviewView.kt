package dev.toast.nothingwidgets

/** Live demo of the 8x8 grid using the current colour and sensitivity. */
class PreviewView(ctx: android.content.Context) : android.view.View(ctx) {
    private val cava = Cava()
    private val raw = FloatArray(8)
    private var last = android.os.SystemClock.elapsedRealtime()
    private var nextBeat = 0L
    private val rnd = java.util.Random()
    override fun onDraw(c: android.graphics.Canvas) {
        val now = android.os.SystemClock.elapsedRealtime()
        val dt = ((now - last) / 1000f).coerceIn(0.005f, 0.2f); last = now
        if (now >= nextBeat) { // fake music: beats with random band energy
            for (i in 0 until 8) raw[i] = (rnd.nextFloat() * (1f - i * .06f)).coerceIn(0f, 1f) * 0.8f
            nextBeat = now + 160 + rnd.nextInt(260)
        }
        for (i in 0 until 8) raw[i] *= 0.9f
        val bars = Prefs.bars(context)
        val lv = if (bars) cava.process(raw, dt, Prefs.gain(context) / 1.5f) else FloatArray(8) { (raw[it] * Prefs.gain(context) / 1.5f).coerceIn(0f, 1f) }
        val s = minOf(width, height)
        c.drawBitmap(Renderers.viz(s, s, lv, Prefs.red(context), false, bars), (width - s) / 2f, 0f, null)
        postInvalidateOnAnimation()
    }
}
