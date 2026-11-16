package dev.toast.nothingwidgets

import kotlin.math.*

object Fft {
    /** In-place radix-2 FFT; re/im sizes must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci; val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** 8 band levels 0..1 from PCM samples, same bin mapping as the prototype: pow(c/8,1.6) over 60% of bins. */
    fun bands(pcm: ShortArray, n: Int = 512, cols: Int = 8): FloatArray {
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            re[i] = (if (i < pcm.size) pcm[i] / 32768.0 else 0.0) * w
        }
        fft(re, im)
        val half = n / 2
        val out = FloatArray(cols)
        for (c in 0 until cols) {
            val i = (((c.toDouble() / cols).pow(1.6)) * half * 0.6).toInt() + 1
            val mag = sqrt(re[i] * re[i] + im[i] * im[i]) / (n / 4.0)
            val db = 20 * log10(mag + 1e-9)
            out[c] = ((db + 70) / 60).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    /**
     * 8 band levels from Visualizer.getFft bytes (re,im pairs). Linear magnitude averaged per band, like cava:
     * no dB squashing, so loud and quiet moments really differ. Cava (auto sensitivity) scales it afterwards.
     * Higher bands are weighted up because music has much less energy there.
     */
    fun bandsFromFft(fft: ByteArray, cols: Int = 8): FloatArray {
        val bins = fft.size / 2
        val out = FloatArray(cols)
        for (c in 0 until cols) {
            val lo = (((c.toDouble() / cols).pow(2.0)) * bins * 0.5).toInt().coerceAtLeast(1)
            val hi = (((((c + 1).toDouble() / cols)).pow(2.0)) * bins * 0.5).toInt().coerceAtLeast(lo + 1).coerceAtMost(bins - 1)
            var sum = 0.0
            for (i in lo..hi) { val r = fft[2 * i].toDouble(); val im = fft[2 * i + 1].toDouble(); sum += sqrt(r * r + im * im) }
            val mean = sum / (hi - lo + 1)
            val weight = 1.0 + c * 0.35
            out[c] = (mean / 60.0 * weight).pow(0.8).coerceIn(0.0, 1.5).toFloat()
        }
        return out
    }
}
