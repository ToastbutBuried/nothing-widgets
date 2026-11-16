package dev.toast.nothingwidgets

import kotlin.math.max
import kotlin.math.pow

/**
 * cava-style post-processing for band levels:
 *  - per-band whitening + auto sensitivity (shrinks on overshoot, slowly grows when quiet),
 *  - instant attack, gravity fall (accelerating drop from the last peak),
 *  - monstercat-style neighbour smoothing,
 *  - peak caps that hold briefly and then fall.
 */
class Cava(val cols: Int = 8) {
    private var sens = 1f
    private val peak = FloatArray(cols)
    private val fallT = FloatArray(cols)
    private val out = FloatArray(cols)
    private val avg = FloatArray(cols) { 0.3f }
    val shaped = FloatArray(cols)
    /** peak cap height per column (0..1) */
    val caps = FloatArray(cols)
    private val capHold = FloatArray(cols)
    private val capFall = FloatArray(cols)
    var gravity = 2.2f
    var neighbour = 1.55f
    /** mean raw energy of the last frame, used by the battery saver */
    var energy = 0f; private set

    fun process(raw: FloatArray, dt: Float, gain: Float): FloatArray {
        var over = false; var maxV = 0f; var e = 0f
        for (i in 0 until cols) {
            e += raw[i]
            avg[i] += (raw[i] - avg[i]) * (dt / 2.5f).coerceAtMost(1f)
            val v = raw[i] / (0.22f + avg[i] * 1.1f) * 0.55f * gain * sens
            if (v > 1f) over = true
            maxV = max(maxV, v)
            if (v >= out[i]) { out[i] = v.coerceAtMost(1f); peak[i] = out[i]; fallT[i] = 0f }
            else {
                fallT[i] += dt
                val f = fallT[i] * gravity
                out[i] = max(v, peak[i] * (1f - f * f).coerceAtLeast(0f))
            }
        }
        energy = e / cols
        if (over) sens = (sens * 0.97f).coerceAtLeast(0.25f)
        else if (maxV in 0.08f..0.7f) sens = (sens * (1f + 0.25f * dt)).coerceAtMost(3f)
        for (i in 0 until cols) {
            var m = out[i]
            for (j in 0 until cols) if (j != i) m = max(m, out[j] / neighbour.pow(Math.abs(i - j).toFloat()))
            shaped[i] = m
            // peak cap: jump up with the bar, hold 150 ms, then fall slower than the bar
            if (m >= caps[i]) { caps[i] = m; capHold[i] = 0.15f; capFall[i] = 0f }
            else if (capHold[i] > 0f) capHold[i] -= dt
            else { capFall[i] += dt; caps[i] = max(m, caps[i] - capFall[i] * capFall[i] * 3f) }
        }
        return shaped
    }
}

/** Pure grid maths (kept out of Renderers so JVM tests can use it). */
object Grid {
    /** Grid shape for a widget of w x h: columns follow the bands setting (0 = from aspect), rows fill the height. */
    fun of(bandsPref: Int, w: Int, h: Int): Pair<Int, Int> {
        val aspect = w.toFloat() / h
        val cols = if (bandsPref > 0) bandsPref else Math.round(8 * aspect).coerceIn(4, 16)
        val rows = Math.round(cols / aspect).coerceIn(4, 16)
        return cols to rows
    }
}
