package dev.toast.nothingwidgets

import kotlin.math.max
import kotlin.math.pow

/**
 * cava-style post-processing for band levels:
 *  - auto sensitivity (shrinks on overshoot, slowly grows when quiet),
 *  - instant attack, gravity fall (accelerating drop from the last peak),
 *  - monstercat-style neighbour smoothing so bars move together like a wave of bars.
 */
class Cava(private val cols: Int = 8) {
    private var sens = 1f
    private val peak = FloatArray(cols)
    private val fallT = FloatArray(cols)
    private val out = FloatArray(cols)
    private val shaped = FloatArray(cols)
    var gravity = 2.2f          // 1/s: full fall from 1.0 takes about 1/gravity seconds (accelerating)
    var neighbour = 1.55f       // monstercat falloff per column

    /** raw: 0..1 per band, dt in seconds, gain: user sensitivity. Returns continuous 0..1 heights. */
    fun process(raw: FloatArray, dt: Float, gain: Float): FloatArray {
        var over = false; var maxV = 0f
        for (i in 0 until cols) {
            val v = raw[i] * gain * sens
            if (v > 1f) over = true
            maxV = max(maxV, v)
            if (v >= out[i]) { out[i] = v.coerceAtMost(1f); peak[i] = out[i]; fallT[i] = 0f }
            else {
                fallT[i] += dt
                val f = (fallT[i] * gravity)
                out[i] = max(v, peak[i] * (1f - f * f).coerceAtLeast(0f))
            }
        }
        if (over) sens = (sens * 0.97f).coerceAtLeast(0.25f)
        else if (maxV in 0.08f..0.7f) sens = (sens * (1f + 0.25f * dt)).coerceAtMost(3f)
        // monstercat smoothing
        for (i in 0 until cols) {
            var m = out[i]
            for (j in 0 until cols) if (j != i) m = max(m, out[j] / neighbour.pow(Math.abs(i - j).toFloat()))
            shaped[i] = m
        }
        return shaped
    }
}
