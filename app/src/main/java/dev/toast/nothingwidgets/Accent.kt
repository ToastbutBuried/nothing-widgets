package dev.toast.nothingwidgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color

/** Accent colour: Nothing red, or (setting) the most vivid colour of the current album cover. */
object Accent {
    /** set by the media service whenever the cover changes; null = cover too grey/dark or none */
    @Volatile var cover: Int? = null

    fun current(c: Context): Int = if (Prefs.accentCover(c)) cover ?: Renderers.RED else Renderers.RED

    /** Most vivid hue of the cover, pushed bright enough to read on black. Off the main thread is fine. */
    fun fromBitmap(b: Bitmap?): Int? {
        if (b == null || b.isRecycled) return null
        val s = try { Bitmap.createScaledBitmap(b, 24, 24, true) } catch (_: Exception) { return null }
        val px = IntArray(24 * 24); s.getPixels(px, 0, 24, 0, 0, 24, 24)
        if (s !== b) s.recycle()
        val weight = FloatArray(12); val hx = FloatArray(12); val hy = FloatArray(12)
        val hsv = FloatArray(3)
        for (p in px) {
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.18f || hsv[2] < 0.25f) continue      // greys, near-black
            val w = hsv[1] * hsv[2]
            val bucket = (hsv[0] / 30f).toInt().coerceIn(0, 11)
            weight[bucket] += w
            val rad = Math.toRadians(hsv[0].toDouble())
            hx[bucket] += (Math.cos(rad) * w).toFloat(); hy[bucket] += (Math.sin(rad) * w).toFloat()
        }
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null
        if (weight[best] < 24 * 24 * 0.012f) return null          // (almost) no colour at all: stay red
        val hue = ((Math.toDegrees(Math.atan2(hy[best].toDouble(), hx[best].toDouble())) + 360) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.75f, 1f))
    }
}
