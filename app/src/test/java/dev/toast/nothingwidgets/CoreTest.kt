package dev.toast.nothingwidgets

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CoreTest {
    @Test fun lrcParsesAndIndexes() {
        val l = LrcParser.parse("[00:02.00]a\n[00:06.50]b\n[01:00.00]c")
        assertEquals(3, l.size); assertEquals(6.5, l[1].t, 1e-9)
        assertEquals(-1, LrcParser.index(l, 1.0)); assertEquals(1, LrcParser.index(l, 10.0)); assertEquals(2, LrcParser.index(l, 99.0))
    }
    @Test fun fftPeaksInRightBand() {
        val n = 512
        val hi = ShortArray(n) { (sin(2 * PI * 4000 * it / 44100) * 20000).toInt().toShort() }
        val lo = ShortArray(n) { (sin(2 * PI * 200 * it / 44100) * 20000).toInt().toShort() }
        val bh = Fft.bands(hi); val bl = Fft.bands(lo)
        assertTrue(bl.indices.maxByOrNull { bl[it] }!! < bh.indices.maxByOrNull { bh[it] }!!)
    }
}

class CavaTest {
    @Test fun attackIsInstantAndFallAccelerates() {
        val c = Cava()
        val up = c.process(FloatArray(8) { 0.6f }, 0.03f, 1f).copyOf()
        assertEquals(0.6f, up[0], 0.01f)
        val a = c.process(FloatArray(8), 0.05f, 1f)[0]
        val b = c.process(FloatArray(8), 0.05f, 1f)[0]
        val d = c.process(FloatArray(8), 0.05f, 1f)[0]
        assertTrue(a < 0.6f && b < a && d < b && (a - b) < (b - d))
    }
}
